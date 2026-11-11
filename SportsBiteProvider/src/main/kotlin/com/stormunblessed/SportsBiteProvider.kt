package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.stormunblessed.APIMatch
import com.stormunblessed.EventImage
import com.stormunblessed.EventImageRequest
import com.stormunblessed.EventImagesRequest
import com.stormunblessed.EventImagesResponse
import com.stormunblessed.SBStream
import com.stormunblessed.SfSource
import com.stormunblessed.SfSourcesResponse
import com.stormunblessed.SfStreamsResponse
import com.stormunblessed.SportsBiteMatches
import com.stormunblessed.defaultPoster
import com.stormunblessed.imageId
import com.stormunblessed.loadSourceNameExtractor
import com.stormunblessed.normalizeSourceServer
import com.stormunblessed.standardSourceName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class SportsBiteProvider : MainAPI() {
    override var mainUrl = "https://sportsbite.org"
    override var name = "SportsBite"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Live,
    )

    override val mainPage = mainPageOf(
        "live" to "Live",
        "popular" to "Popular",
        "football" to "Football",
        "basketball" to "Basketball",
        "american-football" to "Am. Football",
        "hockey" to "Hockey",
        "tennis" to "Tennis",
        "fighting" to "Fighting",
        "wrestling" to "Wrestling",
        "baseball" to "Baseball",
        "cricket" to "Cricket",
        "golf" to "Golf",
        "all" to "All Sports",
    )

    /** GET /api/forestgump/matches -> every day's events flattened. Empty list on any failure. */
    private suspend fun fetchMatches(): List<APIMatch> {
        return runCatching {
            val text = app.get("$mainUrl/api/forestgump/matches").text
            AppUtils.tryParseJson<SportsBiteMatches>(text)?.days?.flatMap { it.events.orEmpty() }
        }.getOrNull() ?: emptyList()
    }

    /**
     * POST /api/event-images/batch -> team/league logos per event id.
     * The batch "poster" field is a resize-proxy url that currently 404s, so prefer
     * the plain /img/c/<hash>.webp team badges which serve fine.
     */
    private suspend fun fetchImages(events: List<APIMatch>): Map<String, String> {
        if (events.isEmpty()) return emptyMap()
        return runCatching {
            val body = EventImagesRequest(
                events.map {
                    EventImageRequest(
                        id = it.imageId(),
                        title = it.title,
                        league = it.category,
                        sport = it.sport,
                        home = it.teams?.home?.name.orEmpty(),
                        away = it.teams?.away?.name.orEmpty(),
                    )
                }
            ).toJson()
            val res = app.post(
                "$mainUrl/api/event-images/batch",
                requestBody = body.toRequestBody("application/json".toMediaType()),
                headers = mapOf("Accept" to "application/json")
            )
            AppUtils.tryParseJson<EventImagesResponse>(res.text)?.images?.mapNotNull { (id, img) ->
                (img.homeLogo ?: img.awayLogo)?.absoluteImageUrl()?.let { id to it }
            }?.toMap()
        }.getOrNull() ?: emptyMap()
    }

    private fun String.absoluteImageUrl(): String =
        if (this.startsWith("http")) this else "$mainUrl$this"

    private fun APIMatch.toSearchResult(poster: String?): SearchResponse {
        return newLiveSearchResponse(this.title ?: "Unknown", this.toJson(), TvType.Live) {
            this.posterUrl = poster ?: this@toSearchResult.poster ?: defaultPoster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = fetchMatches().let { all ->
            when (request.data) {
                "all" -> all
                "live" -> all.filter { it.live == true }
                "popular" -> all.filter { it.popular == true }
                else -> all.filter { it.sport == request.data || it.category == request.data }
            }
        }.sortedBy { it.kickoffMs ?: Long.MAX_VALUE }
        val posters = fetchImages(events)
        val home = events.map { it.toSearchResult(posters[it.imageId()]) }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val events = fetchMatches().filter {
            it.title?.contains(query, ignoreCase = true) == true
        }
        val posters = fetchImages(events)
        return events.map { it.toSearchResult(posters[it.imageId()]) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val info = AppUtils.tryParseJson<APIMatch>(url) ?: return null
        val poster = fetchImages(listOf(info))[info.imageId()]
        return newLiveStreamLoadResponse(info.title ?: "Unknown", url, url) {
            this.posterUrl = poster ?: info.poster ?: defaultPoster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = AppUtils.tryParseJson<APIMatch>(data) ?: return false
        val streams = info.streams.orEmpty().filter { !it.manifest_url.isNullOrBlank() }
        val probes = Probes()

        // StreamFree carries the same fixtures and serves plain TS, so its sources come first:
        // the 247 CDN (cdn.hdm1.net) refuses entire countries, and the site's own lanes can only
        // be played inside its JS player.
        runCatching { loadStreamFree(info.title, streams.firstOrNull()?.language, probes, callback) }
        streams.amap { stream ->
            runCatching { resolveStream(stream, probes, subtitleCallback, callback) }
        }
        return true
    }

    /**
     * Every sportsbite stream is an iframe player whose page embeds the stream url as
     * `var source = window.atob("<base64>")`. Since mid-2026 that value is site-relative
     * (`/247/<canal>`, `/mx/<id>?v=high`, `/selfhost/play/<id>`), so it must be resolved against
     * the site before it can be fetched — otherwise every event ended up with no links at all.
     */
    private suspend fun resolveStream(
        stream: SBStream,
        probes: Probes,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = stream.manifest_url ?: return
        val server = stream.source ?: stream.name
        val lang = stream.language
        if (embed.contains(".m3u8")) {
            emitHls(server, lang, embed, "$mainUrl/", probes, callback)
            return
        }
        val media = resolveMediaUrl(embed, 0)
        if (media != null) {
            emitHls(server, lang, media, embed, probes, callback)
        } else {
            loadSourceNameExtractor(lang, embed, "$mainUrl/", subtitleCallback, callback)
        }
    }

    /**
     * Follows the embed page (and, if needed, the page it points at) until an HLS playlist is
     * found. Returns the playlist url, or null when the page is not backed by one.
     */
    private suspend fun resolveMediaUrl(pageUrl: String, depth: Int): String? {
        val body = runCatching {
            app.get(
                pageUrl,
                referer = "$mainUrl/",
                headers = mapOf("Sec-Fetch-Dest" to "iframe")
            ).text
        }.getOrNull() ?: return null

        if (body.trimStart().startsWith("#EXTM3U")) return pageUrl
        if (depth >= 2) return null

        val raw = sourceFromPage(body) ?: return null
        val next = absoluteUrl(raw) ?: return null
        if (next == pageUrl) return null

        // The player endpoint (`/247/<canal>`, `/selfhost/play/<id>`, …) answers with the
        // playlist itself, so this returns immediately in the common case.
        return resolveMediaUrl(next, depth + 1)
    }

    /** `var source = window.atob("…")` first, then the usual `file`/`stream_url` keys. */
    private fun sourceFromPage(page: String): String? {
        val encoded = Regex("""window\.atob\(\s*["']([A-Za-z0-9+/=_-]+)["']\s*\)""")
            .find(page)?.groupValues?.get(1)
        encoded?.let { base64Decode(it).trim() }?.takeIf { it.isNotEmpty() }?.let { return it }

        for (regex in STREAM_KEYS) {
            val value = regex.find(page)?.groupValues?.get(1)?.trim()
            if (!value.isNullOrEmpty() && !value.startsWith("{")) return value
        }
        return null
    }

    /** Site-relative player urls (`/247/x`) have to be turned into absolute ones. */
    private fun absoluteUrl(source: String): String? {
        val value = source.trim()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/${value.trimStart('.', '/')}"
        }
    }

    /**
     * Emits one link per variant of [playlistUrl] with the standard label.
     *
     * Links that cannot play are dropped instead of being offered:
     *  - lanes that hand the real segment url to their JS player in a `#r2=` / `#sf=` fragment
     *    (the plain path answers 404), and
     *  - the matchix lane, whose segments are webp-wrapped TS.
     * With [checkSegments] the first segment is also fetched once per host, so a CDN that
     * refuses the request (cdn.hdm1.net answers 403 outside its allowed countries) does not end
     * up as an endless spinner in the player.
     */
    private suspend fun emitHls(
        server: String?,
        lang: String?,
        playlistUrl: String,
        referer: String,
        probes: Probes,
        callback: (ExtractorLink) -> Unit,
        checkSegments: Boolean = true
    ) {
        val links = runCatching {
            M3u8Helper.generateM3u8(
                normalizeSourceServer(server),
                playlistUrl,
                referer,
                name = standardSourceName(lang, server)
            )
        }.getOrNull().orEmpty()

        for (link in links) {
            if (probes.accepts(link, checkSegments)) callback(link)
        }
    }

    /** Verdict per stream host, so a blocked CDN is only probed once per event. */
    private inner class Probes {
        private val hosts = ConcurrentHashMap<String, Boolean>()

        suspend fun accepts(link: ExtractorLink, checkSegments: Boolean): Boolean {
            val body = runCatching {
                app.get(link.url, headers = link.headers, referer = link.referer, timeout = 20).text
            }.getOrNull() ?: return true // transient failure: do not drop what we cannot judge

            if (!body.contains("#EXTM3U")) return false
            if (body.contains("#r2=") || body.contains("#sf=")) return false
            if (TIKTOK_SEGMENT.containsMatchIn(body)) return false
            if (!checkSegments) return true

            val segment = firstSegment(body, link.url) ?: return true
            val host = hostOf(segment) ?: return true
            hosts[host]?.let { return it }

            // A Range request keeps the probe cheap; CDNs that refuse the segment (cdn.hdm1.net
            // answers 403 outside its allowed countries) do so before sending any media.
            val ranged = runCatching {
                app.get(
                    segment,
                    headers = mapOf("Range" to "bytes=0-1"),
                    referer = link.url,
                    timeout = 25,
                ).code in 200..299
            }.getOrNull() ?: return true

            // Some CDNs refuse Range but serve the segment, so retry plainly before dropping it.
            val reachable = ranged || runCatching {
                app.get(segment, referer = link.url, timeout = 25).code in 200..299
            }.getOrNull() ?: true

            hosts[host] = reachable
            return reachable
        }
    }

    /** First non-comment playlist entry, resolved against the playlist url. */
    private fun firstSegment(playlist: String, playlistUrl: String): String? {
        val entry = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        return runCatching { URL(URL(playlistUrl), entry).toString() }.getOrNull()
    }

    private fun hostOf(url: String): String? = runCatching { URL(url).host }.getOrNull()

    // ---------------------------------------------------------------------------------------
    // StreamFree: the same fixtures, but its segments are plain MPEG-TS served over HTTPS.
    // ---------------------------------------------------------------------------------------

    private suspend fun loadStreamFree(
        eventTitle: String?,
        lang: String?,
        probes: Probes,
        callback: (ExtractorLink) -> Unit
    ) {
        val match = streamFreeMatch(eventTitle) ?: return
        val sources = runCatching {
            AppUtils.tryParseJson<SfSourcesResponse>(
                app.get("$SF_WEB/api/v1/sources/${match.first}", timeout = 20).text
            )?.sources
        }.getOrNull().orEmpty()

        sources.amap { source -> runCatching { emitStreamFree(source, lang, probes, callback) } }
    }

    private suspend fun emitStreamFree(
        source: SfSource,
        lang: String?,
        probes: Probes,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = source.url ?: return
        val page = runCatching { app.get(embed, referer = "$SF_WEB/", timeout = 20).text }.getOrNull()
            ?: return
        val quality = Regex("""const QUALITY = '([^']*)'""").find(page)?.groupValues?.get(1) ?: return
        val (token, expires, nonce) = streamFreeToken(page, quality) ?: return

        val variant = embed.trimEnd('/').substringAfterLast('/')
        val origin = embed.substringBefore("/embed/").ifEmpty { SF_WEB }
        val playlist = "$origin/live-cdn/$variant/index.m3u8?_t=$token&_e=$expires&_n=$nonce"
        emitHls("StreamFree", lang, playlist, embed, probes, callback, checkSegments = false)
    }

    /** `const _0x = {"1080p": {"_e": …, "_n": …, "_t": …}}` -> the token for this quality. */
    private fun streamFreeToken(page: String, quality: String): Triple<String, Long, String>? {
        val qualityBlock = Regex("""["']""" + Regex.escape(quality) + """["']\s*:\s*\{[^}]*}""")
            .find(page)?.value ?: return null

        val token = Regex("""["']_t["']\s*:\s*["']([^"']+)["']""")
            .find(qualityBlock)?.groupValues?.get(1) ?: return null
        val expires = Regex("""["']_e["']\s*:\s*(\d+)""")
            .find(qualityBlock)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val nonce = Regex("""["']_n["']\s*:\s*["']([^"']+)["']""")
            .find(qualityBlock)?.groupValues?.get(1) ?: return null
        return Triple(token, expires, nonce)
    }

    /** Same fixture on StreamFree: key + category, matched on the normalized title. */
    private suspend fun streamFreeMatch(eventTitle: String?): Pair<String, String>? {
        val wanted = normalizeTitle(eventTitle) ?: return null
        val catalog = streamFreeCatalog()
        catalog.firstOrNull { it.third == wanted }?.let { return it.first to it.second }

        // The two sites word some titles differently ("X vs Y" vs "X vs. Y (W)"), so fall back
        // to a containment match when every team token lines up.
        val wantedTokens = wanted.split(' ').filter { it != "vs" && it != "v" }.toSet()
        if (wantedTokens.size < 2) return null
        return catalog.firstOrNull { (_, _, name) ->
            val tokens = name.split(' ').filter { it != "vs" && it != "v" }.toSet()
            tokens.containsAll(wantedTokens) || wantedTokens.containsAll(tokens)
        }?.let { it.first to it.second }
    }

    /** Cached `strmfree.pk/streams` listing: (key, category, normalized name). */
    private suspend fun streamFreeCatalog(): List<Triple<String, String, String>> {
        val cached = streamFreeCache
        if (cached != null && System.currentTimeMillis() - cached.first < 10 * 60_000L) return cached.second

        val body = runCatching { app.get("$SF_WEB/streams", timeout = 20).text }.getOrNull() ?: return emptyList()
        val parsed = AppUtils.tryParseJson<SfStreamsResponse>(body)?.streams.orEmpty()
        val list = parsed.flatMap { (category, streams) ->
            streams.orEmpty().mapNotNull { stream ->
                val key = stream.stream_key ?: return@mapNotNull null
                val name = normalizeTitle(stream.name) ?: return@mapNotNull null
                Triple(key, stream.category ?: category, name)
            }
        }
        if (list.isNotEmpty()) streamFreeCache = System.currentTimeMillis() to list
        return list
    }

    private fun normalizeTitle(title: String?): String? =
        title?.lowercase()
            ?.replace(Regex("""[^a-z0-9]+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val SF_WEB = "https://strmfree.pk"

        private val STREAM_KEYS = listOf(
            Regex("""["']?(?:stream_url|streamUrl|playbackUrl|playbackURL|file)["']?\s*[:=]\s*["']([^"']+)["']"""),
        )
        private val TIKTOK_SEGMENT = Regex("""p\d+-common-sign\.tiktokcdn\.com/""")

        @Volatile
        private var streamFreeCache: Pair<Long, List<Triple<String, String, String>>>? = null
    }
}
