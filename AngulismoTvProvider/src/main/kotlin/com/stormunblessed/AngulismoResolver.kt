package com.stormunblessed

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URL

const val EXTENSION_NAME = "AngulismoTV"

/** Every link of this provider is Spanish/Latino audio, the site has no language metadata. */
private const val LINK_LANG = "Latino"

fun standardSourceName(lang: String?, server: String?): String =
    listOf(normalizeSourceLang(lang), normalizeSourceServer(server), EXTENSION_NAME)
        .joinToString(" - ")

/** Canonical language labels, most common first: Latino, Español, English. Blank/null -> Latino. */
fun normalizeSourceLang(lang: String?): String {
    val raw = lang?.trim().orEmpty()
    if (raw.isEmpty()) return "Latino"
    val flat = raw.lowercase()
    return when {
        flat == "mx" || flat.endsWith("-mx") || flat.contains("lat") || flat.contains("419") -> "Latino"
        flat == "en" || flat.startsWith("en-") || flat.contains("eng") || flat.contains("ingl") -> "English"
        flat == "es" || flat.startsWith("es-") || flat.contains("esp") ||
                flat.contains("spanish") || flat.contains("castellano") -> "Español"

        flat.contains("sub") -> "Subtitulado"
        else -> raw.replaceFirstChar { it.uppercaseChar() }
    }
}

/** Stream source/host display name: drops emoji/decorations, capitalizes all-lowercase ids, falls back to "Directo". */
fun normalizeSourceServer(server: String?): String {
    val cleaned = server?.replace(Regex("[^\\p{L}\\p{N} .&+_-]"), "")
        ?.replace(Regex(" {2,}"), " ")
        ?.trim()
        .orEmpty()
    if (cleaned.isEmpty()) return "Directo"
    val needsCapital = cleaned.all { it.isLowerCase() || !it.isLetter() }
    return if (needsCapital) cleaned.replaceFirstChar { it.uppercaseChar() } else cleaned
}

/**
 * Turns the iframe urls found in datos.json into playable links.
 *
 * The site itself plays them inside an iframe, so every source here is either a direct
 * playlist or a third-party player page. The chain is ordered from cheapest to most
 * expensive: direct media -> host specific decoders -> generic page scan -> extractor
 * registry -> (only for a few known JS-only players) a WebView sniffer.
 *
 * The host specific decoders are the ones that survived from the old DeporTV provider.
 */
class AngulismoResolver(
    private val app: Requests,
    private val subtitleCallback: (SubtitleFile) -> Unit,
) {
    companion object {
        private val IFRAME_HEADERS = mapOf("Sec-Fetch-Dest" to "iframe")
        private const val MAX_DEPTH = 2
        private const val HTTP_TIMEOUT = 8L // NiceHttp timeout is in seconds
        private const val WEBVIEW_TIMEOUT = 10_000L
        private const val HLS_PROBE_TIMEOUT = 6_000L
        private const val DASH_PROBE_TIMEOUT = 6_000L

        /** WebView creation is expensive; a whole event can hold 20 iframe feeds. */
        private val webViewSlots = Semaphore(2)

        /** Manifest probes: enough parallelism to stay fast, few enough to not hammer a CDN. */
        private val manifestSlots = Semaphore(6)

        private val MEDIA_REGEX = Regex(
            """https?://[^\s"'<>\\]+?\.(m3u8|mpd|mp4)(\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val PLAYBACK_REGEXES = listOf(
            Regex("""var\s+playbackURL\s*=\s*["']([^"']+)["']"""),
            Regex("""["'](?:stream_url|streamUrl|playbackUrl|file|source)["']\s*:\s*["']([^"']+)["']"""),
        )

        /** Players that build their url entirely in obfuscated JS: let a WebView get it. */
        private val WEBVIEW_HOSTS = listOf(
            "welivesportz", "welivesports", "tvlibre", "bestleague.pl",
            "ksdjugfsddeports", "junkieembeds",
        )
    }

    suspend fun resolve(feed: AngulismoFeed, callback: (ExtractorLink) -> Unit) {
        val url = normalizeUrl(feed.url) ?: return
        runCatching {
            when {
                isDirectMedia(url) -> emitMedia(feed, url, null, callback)
                url.contains("bestleague.life/tok.html") || url.contains("bestleague.life/cvatt.html") ->
                    loadBestleague(feed, url, callback)
                url.contains("/mpdk/") -> loadMpdk(feed, url, callback)
                url.contains("live1.php") -> loadLive1(feed, url, callback)
                url.contains("global1.php") || url.contains("global2.php") ->
                    loadGlobalPhp(feed, url, callback)

                url.contains("sudamerica") -> loadScriptScope(feed, url, 0, callback)
                else -> resolvePage(feed, url, 0, callback)
            }
        }
    }

    // ---------------------------------------------------------------- direct media

    private fun isDirectMedia(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mp4")
    }

    private suspend fun emitMedia(
        feed: AngulismoFeed,
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val clean = url.substringBefore("?").lowercase()
        when {
            clean.endsWith(".mpd") -> emitDash(feed, url, referer, null, null, callback)
            clean.endsWith(".m3u8") -> emitHls(feed, url, referer, callback)
            else -> callback(
                newExtractorLink(sourceOf(feed), nameOf(feed), url) {
                    this.referer = referer.orEmpty()
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun emitHls(
        feed: AngulismoFeed,
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val source = sourceOf(feed)
        val name = nameOf(feed)

        // A playlist that cannot be fetched/parsed here will not play in ExoPlayer either, and
        // handing it over only leaves the user with a source that spins forever. Probe it with a
        // couple of header combinations and drop it if none works.
        suspend fun probe(withReferer: String?, withHeaders: Map<String, String>): List<ExtractorLink> =
            runCatching {
                withTimeoutOrNull(HLS_PROBE_TIMEOUT) {
                    M3u8Helper.generateM3u8(
                        source,
                        url,
                        withReferer.orEmpty(),
                        headers = withHeaders,
                        name = name,
                    )
                }
            }.getOrNull().orEmpty()

        val origin = referer?.let { "https://${runCatching { URL(it).host }.getOrNull().orEmpty()}" }
        // 1) as the player would request it, 2) same but sending Referer/Origin on the playlist
        // fetch too, 3) without any referer (some hosts reject a foreign one).
        var links = probe(referer, emptyMap())
        if (links.isEmpty() && !referer.isNullOrEmpty()) {
            links = probe(referer, mapOf("Referer" to referer, "Origin" to origin.orEmpty()))
        }
        if (links.isEmpty() && !referer.isNullOrEmpty()) links = probe(null, emptyMap())
        links.forEach(callback)
    }

    private suspend fun emitDash(
        feed: AngulismoFeed,
        url: String,
        referer: String?,
        keyId: String?,
        key: String?,
        callback: (ExtractorLink) -> Unit,
        skipWithoutKeys: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ) {
        val source = sourceOf(feed)
        val name = nameOf(feed)
        // The player already sends `referer`; a second Referer entry in the map would duplicate it.
        val linkHeaders = if (referer.isNullOrBlank()) headers
        else headers.filterKeys { !it.equals("Referer", ignoreCase = true) }
        if (keyId.isNullOrBlank() || key.isNullOrBlank()) {
            // Encrypted manifests we could not pair with a key can only spin in the player.
            if (skipWithoutKeys) return
            callback(
                newExtractorLink(source, name, url, type = ExtractorLinkType.DASH) {
                    this.referer = referer.orEmpty()
                    this.headers = linkHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }
        callback(
            newDrmExtractorLink(source, name, url, ExtractorLinkType.DASH, CLEARKEY_UUID) {
                this.kid = keyId
                this.key = key
                this.referer = referer.orEmpty()
                this.headers = linkHeaders
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /** One way of requesting a manifest: url + the headers the CDN expects with it. */
    private data class DashTarget(
        val url: String,
        val referer: String?,
        val headers: Map<String, String> = emptyMap(),
    )

    /** Returns the manifest body, or null when the response is not an actual DASH manifest. */
    private suspend fun probeDash(target: DashTarget): String? = manifestSlots.withPermit {
        runCatching {
            val res = withTimeoutOrNull(DASH_PROBE_TIMEOUT) {
                app.get(
                    target.url,
                    headers = target.headers + IFRAME_HEADERS,
                    timeout = HTTP_TIMEOUT,
                )
            } ?: return@runCatching null
            if (res.code in 200..299) res.text.takeIf { it.contains("<MPD", true) } else null
        }.getOrNull()
    }

    /**
     * Emits the first candidate that answers with a real DASH manifest **and** whose KID matches
     * the key we hold (a manifest with a different KID can never be decrypted with that key).
     * Dead edges and error pages are simply skipped: they can only spin in the player.
     */
    private suspend fun emitFirstReachableDash(
        feed: AngulismoFeed,
        targets: List<DashTarget>,
        keyIdHex: String?,
        kidB64Fallback: String?,
        keyB64: String?,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (keyB64.isNullOrBlank() || targets.isEmpty()) return false
        val expectedKid = keyIdHex?.replace("-", "")?.lowercase()
        // Probes run in parallel; list order still decides which candidate wins.
        val probes = targets.amap { target -> probeDash(target)?.let { target to it } }
        val hit = probes.firstNotNullOfOrNull { pair ->
            val (target, manifest) = pair ?: return@firstNotNullOfOrNull null
            val manifestKid = dashKidHex(manifest)
            when {
                expectedKid == null || manifestKid == null -> target to (manifestKid ?: expectedKid)
                manifestKid == expectedKid -> target to manifestKid
                else -> null
            }
        } ?: return false
        val (target, kidHex) = hit
        emitDash(
            feed, target.url, target.referer,
            kidHex?.let { hexToBase64Url(it) } ?: kidB64Fallback, keyB64, callback,
            skipWithoutKeys = true, headers = target.headers,
        )
        return true
    }

    /** `cenc:default_KID="9f32..."` / `urn:uuid:9f32-..."` -> plain lowercase hex. */
    private fun dashKidHex(manifest: String): String? {
        val raw = Regex("""default_KID\s*=\s*"([0-9a-fA-F-]{32,36})"""").find(manifest)?.groupValues?.get(1)
            ?: Regex("""urn:uuid:([0-9a-fA-F-]{32,36})""").find(manifest)?.groupValues?.get(1)
            ?: return null
        return raw.replace("-", "").lowercase()
    }

    private fun sourceOf(feed: AngulismoFeed): String = normalizeSourceServer(feedLabel(feed))

    private fun nameOf(feed: AngulismoFeed): String = standardSourceName(LINK_LANG, feedLabel(feed))

    private fun feedLabel(feed: AngulismoFeed): String =
        listOfNotNull(feed.channel.takeIf { it.isNotBlank() }, feed.option?.takeIf { it.isNotBlank() })
            .joinToString(" ")

    // ------------------------------------------------------- bestleague (DASH+ClearKey)

    /**
     * bestleague.life players, both variants ship the whole config in the page:
     *  - tok.html (extension player) with `{cdn, token}` edges:
     *      https://<cdn>.cvattv.com.ar/<token>/live/c<N>eds/<canal>/SA_Live_dash_enc/<canal>.mpd
     *  - cvatt.html (JW Player) with fixed `cdn`/`chromecast` hosts:
     *      https://<host>.cvattv.com.ar/live/c<N>eds/<canal>/SA_Live_dash_enc_C/<canal>.mpd
     * In both cases `<canal>` is atob(get) and the ClearKey pair depends on the same get value.
     */
    private suspend fun loadBestleague(
        feed: AngulismoFeed,
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val code = url.param("get") ?: return
        val channel = decodeBase64(code)
        val page = runCatching { app.get(url, headers = IFRAME_HEADERS, timeout = HTTP_TIMEOUT).text }.getOrNull()
        if (channel.isNullOrBlank() || page.isNullOrBlank()) return

        val number = bestleagueNumber(page, code)
        val keys = bestleagueKeys(page, code)
        // tok.html hands its extension a `headers=<base64 json>` blob with the Origin/Referer the
        // CDN demands ({"origin":"https://portal.app.flow.com.ar"}).
        val edgeHeaders = bestleagueEdgeHeaders(page)
        val edges = Regex("""cdn:\s*"([^"]+)",\s*token:\s*"([^"]+)"""")
            .findAll(page)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        // The tokens are IP-bound and name their channel + tier in the JWT; prefer the matching
        // one. Without a usable token the plain CDN shape still works.
        val withPath = edges.map { (cdn, token) -> Triple(cdn, token, bestleagueTokenPath(token)) }
        val tokenEdge = (withPath.firstOrNull { it.third?.second?.equals(channel, true) == true }
            ?: withPath.firstOrNull())
        val tier = tokenEdge?.third?.takeIf { it.second.equals(channel, true) }?.first ?: number

        // Manifest shapes seen for this CDN:
        //  - public working lists: plain cdn host, `SA_Live_dash_enc_2A`, no token, no headers
        //  - this site's JW page:  `SA_Live_dash_enc_C`
        //  - the token JWT itself: `/live/c<N>eds/<canal>/SA_Live_dash_enc_/`
        //  - the old page template: `SA_Live_dash_enc`
        // All are probed in parallel and the KID decides which one is real.
        val dirs = listOf(
            "SA_Live_dash_enc_2A",
            "SA_Live_dash_enc",
            "SA_Live_dash_enc_C",
            "SA_Live_dash_enc_",
        )
        val targets = ArrayList<DashTarget>()
        listOf("cdn", "chromecast").forEach { host ->
            dirs.forEach { dir ->
                targets += DashTarget(
                    "https://$host.cvattv.com.ar/live/c${tier}eds/$channel/$dir/$channel.mpd",
                    null,
                )
            }
        }
        if (tokenEdge != null) {
            val (cdn, token, _) = tokenEdge
            dirs.forEach { dir ->
                targets += DashTarget(
                    "https://$cdn.cvattv.com.ar/$token/live/c${tier}eds/$channel/$dir/$channel.mpd",
                    edgeHeaders?.second ?: url,
                    edgeHeaders?.first ?: emptyMap(),
                )
            }
        }
        val keyIdHex = keys?.first
        val kidB64 = keyIdHex?.let { hexToBase64Url(it) }
        val keyB64 = keys?.second?.let { hexToBase64Url(it) }
        val emitted = emitFirstReachableDash(feed, targets, keyIdHex, kidB64, keyB64, callback)
        // Second chance: same manifests, but with the Origin/Referer the site's extension sends.
        if (!emitted && edgeHeaders != null) {
            val withHeaders = targets
                .filter { it.referer == null }
                .map { it.copy(referer = edgeHeaders.second, headers = edgeHeaders.first) }
            emitFirstReachableDash(feed, withHeaders, keyIdHex, kidB64, keyB64, callback)
        }
    }

    /** `&headers=<base64 {"origin":..,"referer":..}>` from the extension url -> headers + referer. */
    private fun bestleagueEdgeHeaders(page: String): Pair<Map<String, String>, String?>? {
        val encoded = Regex("""[?&]headers=([A-Za-z0-9+/=%_-]+)""")
            .find(page)?.groupValues?.get(1) ?: return null
        val json = decodeBase64(encoded) ?: return null
        val origin = Regex(""""origin"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val referer = Regex(""""referer"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        if (origin.isNullOrBlank() && referer.isNullOrBlank()) return null
        val headers = buildMap {
            if (!origin.isNullOrBlank()) put("Origin", origin)
            if (!referer.isNullOrBlank()) put("Referer", referer)
        }
        return headers to referer
    }

    /** Reads tier + channel (`/live/c6eds/ESPN2_Arg/SA_Live_dash_enc_/`) out of the edge JWT. */
    private fun bestleagueTokenPath(token: String): Pair<Int, String>? {
        val payload = token.removePrefix("tok_").split(".").getOrNull(1) ?: return null
        val json = decodeBase64(payload) ?: return null
        val path = Regex(""""path"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
        val parts = path.split("/").filter { it.isNotEmpty() }
        val tier = parts.getOrNull(1)?.removePrefix("c")?.removeSuffix("eds")?.toIntOrNull()
        val channel = parts.getOrNull(2)
        if (tier == null || channel.isNullOrBlank()) return null
        return tier to channel
    }

    /** The `number` chain is `if (getURL == "..." || ...) number = N; ... else number = 3;` */
    private fun bestleagueNumber(page: String, code: String): Int {
        // tok.html declares `var number;` before the chain, cvatt.html does not, so anchor on
        // the first `getURL ==` instead and stop before the player setup that follows it.
        val start = page.indexOf("getURL ==")
        if (start < 0) return 3
        val rest = page.substring(start)
        val end = listOf("// Función", "var mt", "var mpd")
            .map { rest.indexOf(it) }
            .filter { it > 0 }
            .minOrNull() ?: rest.length
        val region = rest.substring(0, end)
        val fromChain = Regex("""(?:if|else\s+if)\s*\((.*?)\)\s*number\s*=\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(region)
            .firstOrNull { it.groupValues[1].contains("\"$code\"") }
            ?.groupValues?.get(2)?.toIntOrNull()
        return fromChain
            ?: Regex("""else\s+number\s*=\s*(\d+)""").find(region)?.groupValues?.get(1)?.toIntOrNull()
            ?: 3
    }

    /** `if (getURL == "CODE") { keyId = "..."; key = "..."; }` -> raw hex KID + key. */
    private fun bestleagueKeys(page: String, code: String): Pair<String, String>? {
        val block = Regex(
            """getURL\s*==\s*"${Regex.escape(code)}"[\s\S]{0,240}?keyId\s*=\s*"([0-9a-fA-F]{32})"[\s\S]{0,120}?key\s*=\s*"([0-9a-fA-F]{32})""""
        ).find(page) ?: return null
        return block.groupValues[1].lowercase() to block.groupValues[2].lowercase()
    }

    /** bestleague.world/mpdk/?get=<base64 mpd>&key=<base64 kid hex>&key2=<base64 key hex> */
    private suspend fun loadMpdk(feed: AngulismoFeed, url: String, callback: (ExtractorLink) -> Unit) {
        val mpd = decodeBase64(url.param("get")).orEmpty()
        if (!mpd.startsWith("http")) return
        val kid = licenseKey(url.param("key"))
        val key = licenseKey(url.param("key2"))
        emitFirstReachableDash(
            feed, listOf(DashTarget(mpd, url)),
            null, kid, key, callback,
        )
    }

    // ------------------------------------------------------------- legacy player pages

    /** streamtp / streamx061 `global1.php|global2.php`: playbackURL is often built with atob(). */
    private suspend fun loadGlobalPhp(
        feed: AngulismoFeed,
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val res = runCatching { app.get(url, headers = IFRAME_HEADERS, timeout = HTTP_TIMEOUT) }.getOrNull()
        val text = res?.text?.replace("\\/", "/").orEmpty()
        val direct = findMediaUrl(text)
        val link = direct
            ?: res?.document?.select("script")?.map { it.data() }
                ?.firstOrNull { it.contains("playbackURL") }?.let { evalPlaybackScript(it) }
            ?: findPlaybackUrl(text)
        if (link.isNullOrBlank()) {
            resolvePage(feed, url, 1, callback)
            return
        }
        emitMedia(feed, link, url, callback)
    }

    /** streamxhd `live1.php`: the url is split into a numeric base64 array (decodeStreamXHDUrl). */
    private suspend fun loadLive1(
        feed: AngulismoFeed,
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val res = runCatching { app.get(url, headers = IFRAME_HEADERS, timeout = HTTP_TIMEOUT) }.getOrNull()
        val script = res?.document?.select("script")?.joinToString("\n") { it.data() }.orEmpty()
        val text = res?.text?.replace("\\/", "/").orEmpty()
        val link = decodeStreamXHDUrl(script) ?: findMediaUrl(text)
        if (link.isNullOrBlank()) {
            resolvePage(feed, url, 1, callback)
            return
        }
        emitMedia(feed, link, url, callback)
    }

    /**
     * sudamericafy / sudamericaplay: the player config (dash url + ClearKey pair) is built by
     * obfuscated inline scripts. Run them in Rhino with a mocked DOM and read the variables.
     */
    private suspend fun loadScriptScope(
        feed: AngulismoFeed,
        url: String,
        depth: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val res = runCatching { app.get(url, headers = IFRAME_HEADERS, timeout = HTTP_TIMEOUT) }.getOrNull()
        if (res == null) {
            if (depth < MAX_DEPTH) resolvePage(feed, url, depth + 1, callback)
            return
        }
        val text = res.text.replace("\\/", "/")
        val scripts = res.document.select("script").map { it.data() }.filter { it.isNotBlank() }
        var stream: String? = null
        var keyIdHex: String? = null
        var keyHex: String? = null

        if (scripts.isNotEmpty()) {
            val rhino = Context.enter()
            rhino.setInterpretedMode(true)
            val scope = rhino.initStandardObjects()
            try {
                scope.put("atob", scope, atobFunction())
                val fakeEl = rhino.newObject(scope, "Object")
                val noop = object : BaseFunction() {
                    override fun call(
                        cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>,
                    ): Any = org.mozilla.javascript.Undefined.instance
                }
                val fakeDoc = rhino.newObject(scope, "Object")
                fakeDoc.put("getElementById", fakeDoc, object : BaseFunction() {
                    override fun call(
                        cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>,
                    ): Any = fakeEl
                })
                fakeDoc.put("createElement", fakeDoc, object : BaseFunction() {
                    override fun call(
                        cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>,
                    ): Any = cx.newObject(s, "Object")
                })
                fakeDoc.put("getElementsByTagName", fakeDoc, object : BaseFunction() {
                    override fun call(
                        cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>,
                    ): Any = cx.newArray(s, arrayOf<Any>(fakeEl))
                })
                fakeDoc.put("addEventListener", fakeDoc, object : BaseFunction() {
                    override fun call(
                        cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>,
                    ): Any {
                        (args.getOrNull(1) as? org.mozilla.javascript.Function)
                            ?.let { fn -> runCatching { fn.call(cx, s, s, emptyArray()) } }
                        return org.mozilla.javascript.Undefined.instance
                    }
                })
                val fakeHead = rhino.newObject(scope, "Object")
                fakeHead.put("appendChild", fakeHead, noop)
                fakeDoc.put("head", fakeDoc, fakeHead)
                val fakeBody = rhino.newObject(scope, "Object")
                fakeBody.put("appendChild", fakeBody, noop)
                fakeDoc.put("body", fakeDoc, fakeBody)
                fakeDoc.put("write", fakeDoc, noop)
                scope.put("document", scope, fakeDoc)

                val fakeWindow = rhino.newObject(scope, "Object")
                fakeWindow.put("document", fakeWindow, fakeDoc)
                scope.put("window", scope, fakeWindow)
                scope.put("navigator", scope, rhino.newObject(scope, "Object"))
                scope.put("location", scope, rhino.newObject(scope, "Object"))
                scope.put("self", scope, scope)
                scope.put("top", scope, scope)
                scope.put("parent", scope, scope)
                scope.put("setTimeout", scope, object : BaseFunction() {
                    override fun call(
                        cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>,
                    ): Any {
                        (args.getOrNull(0) as? org.mozilla.javascript.Function)
                            ?.let { fn -> runCatching { fn.call(cx, s, s, emptyArray()) } }
                        return 0.0
                    }
                })
                scope.put("setInterval", scope, scope.get("setTimeout", scope))

                val cleanScripts = scripts.joinToString("\n") { s ->
                    s.replace(Regex("document\\.domain\\s*=.*?;"), "")
                        .replace(Regex("window\\.location\\.(href|replace)\\s*=.*?;"), "")
                        .replace(Regex("!function\\(\\)\\{try\\{.*?\\}\\}\\(\\);"), "")
                }
                runCatching { rhino.evaluateString(scope, cleanScripts, "angulismo", 1, null) }

                stream = listOf("streamUrl", "url", "playbackURL", "src", "stream_url")
                    .firstNotNullOfOrNull { key ->
                        val value = scope.get(key, scope)
                        if (value == Scriptable.NOT_FOUND) null
                        else value?.toString()?.takeIf { it.startsWith("http") }
                    }
                keyIdHex = scope.get("ck_id", scope).takeIf { it != Scriptable.NOT_FOUND }?.toString()
                keyHex = scope.get("ck_key", scope).takeIf { it != Scriptable.NOT_FOUND }?.toString()
            } catch (_: Exception) {
            } finally {
                rhino.close()
            }
        }

        val link = stream ?: findMediaUrl(text) ?: findPlaybackUrl(text)
        if (link.isNullOrBlank()) {
            if (depth < MAX_DEPTH) resolvePage(feed, url, depth + 1, callback)
            return
        }
        val clean = link.substringBefore("?").lowercase()
        if (clean.endsWith(".mpd")) {
            val origin = "https://${runCatching { URL(url).host }.getOrNull().orEmpty()}"
            emitFirstReachableDash(
                feed,
                listOf(DashTarget(link, url, mapOf("Referer" to url, "Origin" to origin))),
                keyIdHex,
                keyIdHex?.let { hexToBase64Url(it) },
                keyHex?.let { hexToBase64Url(it) },
                callback,
            )
        } else {
            emitMedia(feed, link, url, callback)
        }
    }

    // --------------------------------------------------------------- generic fallback

    private suspend fun resolvePage(
        feed: AngulismoFeed,
        url: String,
        depth: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (depth > MAX_DEPTH) {
            fallback(feed, url, callback)
            return
        }
        val res = runCatching { app.get(url, headers = IFRAME_HEADERS, timeout = HTTP_TIMEOUT) }.getOrNull()
        if (res == null) {
            fallback(feed, url, callback)
            return
        }
        if (res.text.trimStart().startsWith("#EXTM3U")) {
            emitHls(feed, url, url, callback)
            return
        }
        val text = res.text.replace("\\/", "/")
        val media = findMediaUrl(text) ?: findPlaybackUrl(text)
        if (!media.isNullOrBlank()) {
            // bestleague.pl style pages: Clappr + shaka `clearKeys: {'<kid>':'<key>'}`
            val keys = if (media.contains(".mpd", true)) clearKeysOf(text) else null
            if (keys != null) emitDash(feed, media, url, keys.first, keys.second, callback)
            else emitMedia(feed, media, url, callback)
            return
        }
        // JS-only players: the static iframe on those pages is an ad, not the stream.
        if (loadWithWebView(feed, url, callback)) return
        val iframe = res.document.selectFirst("iframe[src]")?.attr("src")?.trim().orEmpty()
        if (iframe.isNotEmpty()) {
            val next = absolute(url, iframe)
            if (next != null && next != url) {
                resolvePage(feed, next, depth + 1, callback)
                return
            }
        }
        fallback(feed, url, callback)
    }

    private fun isWebViewHost(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull().orEmpty()
        return WEBVIEW_HOSTS.any { host.contains(it) }
    }

    private suspend fun loadWithWebView(
        feed: AngulismoFeed,
        url: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!isWebViewHost(url)) return false
        val pattern = Regex("""\.(m3u8|mpd)(\?|$)""", RegexOption.IGNORE_CASE)
        val resolver = WebViewResolver(
            interceptUrl = pattern,
            additionalUrls = listOf(pattern),
            useOkhttp = false,
            timeout = WEBVIEW_TIMEOUT,
        )
        val intercepted = runCatching {
            webViewSlots.withPermit { app.get(url, interceptor = resolver).url }
        }.getOrNull()
        if (intercepted.isNullOrBlank() || !pattern.containsMatchIn(intercepted)) return false
        emitMedia(feed, intercepted, url, callback)
        return true
    }

    private suspend fun fallback(
        feed: AngulismoFeed,
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val found = mutableListOf<ExtractorLink>()
        loadExtractor(url, url, subtitleCallback) { link -> found += link }
        found.forEach { link ->
            callback(
                newExtractorLink(link.source, standardSourceName(LINK_LANG, link.source), link.url) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }

    // ------------------------------------------------------------- generic extractors

    /** Playlists only: `.mp4` matches inside player pages are almost always ad pre-rolls. */
    private fun findMediaUrl(text: String): String? {
        val all = MEDIA_REGEX.findAll(text).map { it.value }.toList()
        return all.firstOrNull { it.contains(".m3u8", true) }
            ?: all.firstOrNull { it.contains(".mpd", true) }
    }

    private fun findPlaybackUrl(text: String): String? =
        PLAYBACK_REGEXES.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
                ?.replace("\\/", "/")
        }

    /** shaka `clearKeys: { '<kid hex>': '<key hex>' }` -> base64url pair. */
    private fun clearKeysOf(text: String): Pair<String, String>? {
        val block = Regex("""clearKeys\s*:\s*\{([^}]*)\}""").find(text)?.groupValues?.get(1) ?: return null
        val pair = Regex("""['"]([0-9a-fA-F]{32})['"]\s*:\s*['"]([0-9a-fA-F]{32})['"]""").find(block)
            ?: return null
        val kid = hexToBase64Url(pair.groupValues[1]) ?: return null
        val key = hexToBase64Url(pair.groupValues[2]) ?: return null
        return kid to key
    }

    /** Older global*.php builds: `var playbackURL = atob("...") + ...` (see DeporTV). */
    private fun evalPlaybackScript(script: String): String? {
        val clean = script.substringBefore("var p2pConfig")
        val rhino = Context.enter()
        rhino.setInterpretedMode(true)
        val scope = rhino.initStandardObjects()
        return try {
            scope.put("atob", scope, atobFunction())
            rhino.evaluateString(scope, clean, "playbackURL", 1, null)
            scope.get("playbackURL", scope)?.toString()?.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        } finally {
            rhino.close()
        }
    }

    /** streamxhd: urls are stored as a shuffled `[[index, base64 number]]` array + two key fns. */
    private fun decodeStreamXHDUrl(script: String): String? {
        val arrMatch = Regex("""=\[\[(\d+),"([A-Za-z0-9+/=]+)"\]""").find(script) ?: return null
        val arrStart = arrMatch.range.first
        val sectionEnd = listOfNotNull(
            "adsPayload".let { val i = script.indexOf(it, arrStart); if (i > 0) i else null },
            "bootAds".let { val i = script.indexOf(it, arrStart); if (i > 0) i else null },
        ).minOrNull() ?: (arrStart + 15000)
        val arrSection = script.substring(arrStart, minOf(sectionEnd, script.length))
        val entries = Regex("""\[(\d+),"([A-Za-z0-9+/=]+)"\]""").findAll(arrSection)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .toList()
        if (entries.isEmpty()) return null
        val keyFns = Regex("""function\s+\w+\s*\(\s*\)\s*\{\s*return\s+(\d+)\s*;?\s*\}""")
            .findAll(script).map { it.groupValues[1].toInt() }.take(2).toList()
        if (keyFns.size < 2) return null
        val k = keyFns[0] + keyFns[1]
        return entries.sortedBy { it.first }.mapNotNull { (_, b64) ->
            val decoded = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull() ?: return@mapNotNull null
            val digits = decoded.filter { it.isDigit() }
            val num = digits.toIntOrNull() ?: return@mapNotNull null
            (num - k).toChar()
        }.joinToString("")
    }

    // --------------------------------------------------------------------- utilities

    private fun atobFunction(): BaseFunction = object : BaseFunction() {
        override fun call(
            cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>,
        ): Any = decodeBase64(args.getOrNull(0)?.toString().orEmpty()).orEmpty()
    }

    private fun normalizeUrl(raw: String): String? {
        var url = raw.trim().replace("&amp;", "&")
        if (url.isEmpty()) return null
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http")) return null
        return url
    }

    private fun absolute(base: String, maybeRelative: String): String? {
        if (maybeRelative.startsWith("http")) return maybeRelative
        if (maybeRelative.startsWith("//")) return "https:$maybeRelative"
        val host = runCatching { URL(base).host }.getOrNull() ?: return null
        return if (maybeRelative.startsWith("/")) "https://$host$maybeRelative" else "https://$host/$maybeRelative"
    }

    private fun String.param(name: String): String? {
        val query = substringAfter("?", "").substringBefore("#")
        if (query.isEmpty()) return null
        return query.split("&").firstOrNull { it.substringBefore("=") == name }?.substringAfter("=")
    }

    private fun decodeBase64(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().replace(' ', '+')
        val flags = if (normalized.contains('-') || normalized.contains('_')) {
            Base64.URL_SAFE
        } else {
            Base64.DEFAULT
        }
        return runCatching { String(Base64.decode(normalized, flags)) }.getOrNull()
            ?: runCatching { String(Base64.decode(normalized, Base64.DEFAULT)) }.getOrNull()
    }

    /** DRM params can be either raw hex or base64-of-hex; both end up as base64url bytes. */
    private fun licenseKey(value: String?): String? {
        if (value.isNullOrBlank()) return null
        hexToBase64Url(value)?.let { return it }
        return hexToBase64Url(decodeBase64(value).orEmpty())
    }

    private fun hexToBase64Url(hex: String?): String? {
        if (hex.isNullOrBlank() || hex.length % 2 != 0) return null
        if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}
