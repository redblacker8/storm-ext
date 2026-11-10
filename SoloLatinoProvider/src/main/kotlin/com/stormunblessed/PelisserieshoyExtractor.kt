package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Custom player for https://player.pelisserieshoy.com (SoloLatino's "Principal" server).
 *
 * Flow (reverse-engineered from the site's JS):
 *  1. GET /f/{id} with Referer https://sololatino.net/ to get the player page.
 *  2. Extract the token `_t`, the scrambled `rt` parts `_qk`, and content id `_qc`.
 *  3. rt = _qk[1]+_qk[3]+_qk[0]+_qk[2]; ao = https://sololatino.net.
 *  4. POST /s.php a=1  -> server list (grouped by language in langs_s).
 *  5. POST /s.php a=click -> unlock (avoids the "no_click" rejection).
 *  6. POST /s.php a=2 (per server, with tk = FNV-1a(rt+"|clk|"+qc)) -> {type,u|url}.
 */
object PelisserieshoyExtractor {
    private const val MAIN_URL = "https://player.pelisserieshoy.com"
    private const val REFERER = "https://sololatino.net/"
    private const val AO = "https://sololatino.net"

    /** FNV-1a 32-bit, unsigned, hex without zero-padding — matches the player's _qh(). */
    private fun fnv1a(s: String): String {
        var h = 2166136261u
        for (c in s) {
            h = h xor c.code.toUInt()
            h = h * 16777619u
        }
        return h.toString(16)
    }

    suspend fun load(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageHeaders = mapOf(
            "Referer" to REFERER,
            "Origin" to MAIN_URL,
        )
        val html = app.get(url, headers = pageHeaders).document.html()

        val token = Regex("""const _t = '([a-f0-9]+)'""").find(html)?.groupValues?.get(1)
            ?: return
        val qk = Regex("""_qk=\[([^\]]*)\]""").find(html)?.groupValues?.get(1)
            ?.let { Regex("\"([^\"]*)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
            ?: return
        val qc = Regex("""_qc="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()

        if (qk.size < 4) return
        val rt = qk[1] + qk[3] + qk[0] + qk[2]

        val postHeaders = pageHeaders + ("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8")

        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

        // a=1: scan available servers
        val scan = app.post(
            "$MAIN_URL/s.php",
            headers = postHeaders,
            data = mapOf(
                "a" to "1",
                "tok" to token,
                "rt" to enc(rt),
                "tk" to "",
                "ao" to enc(AO),
            )
        ).parsedSafe<PlayerScanResponse>() ?: return

        // a=click: unlock streams (otherwise a=2 replies "no_click")
        app.post(
            "$MAIN_URL/s.php",
            headers = postHeaders,
            data = mapOf(
                "a" to "click",
                "tok" to token,
                "rt" to enc(rt),
                "tk" to "",
                "ao" to enc(AO),
            )
        )

        val tk = fnv1a("$rt|clk|$qc")

        // Group servers by language; fall back to a single "LAT" group if langs_s is absent.
        val langServers: List<Pair<String, List<Pair<String, String>>>> =
            scan.langsS?.entries?.map { (lang, servers) ->
                lang to servers.mapNotNull { s -> if (s.size >= 2) s[0] to s[1] else null }
            }?.filter { it.second.isNotEmpty() }?.takeIf { it.isNotEmpty() }
            ?: listOf(
                "LAT" to (scan.s?.mapNotNull { s -> if (s.size >= 2) s[0] to s[1] else null }
                    ?: emptyList())
            )

        langServers.forEach { (lang, servers) ->
            servers.amap { (serverName, serverId) ->
                val resolve = app.post(
                    "$MAIN_URL/s.php",
                    headers = postHeaders,
                    data = mapOf(
                        "a" to "2",
                        "v" to serverId,
                        "tok" to token,
                        "rt" to enc(rt),
                        "tk" to tk,
                        "ao" to enc(AO),
                    )
                ).parsedSafe<PlayerResolveResponse>() ?: return@amap

                when {
                    resolve.type == "iframe" && !resolve.url.isNullOrBlank() -> {
                        loadSourceNameExtractor(lang, resolve.url, url, subtitleCallback, callback)
                    }
                    !resolve.u.isNullOrBlank() -> {
                        val stream = resolve.u.let { if (it.startsWith("/")) "$MAIN_URL$it" else it }
                        callback.invoke(
                            newExtractorLink(
                                normalizeSourceServer(serverName),
                                standardSourceName(lang, serverName),
                                stream
                            ) {
                                this.referer = url
                            }
                        )
                    }
                }
            }
        }
    }
}

data class PlayerScanResponse(
    val s: List<List<String>>? = null,
    @JsonProperty("langs_s") val langsS: Map<String, List<List<String>>>? = null,
)

data class PlayerResolveResponse(
    val type: String? = null,
    val u: String? = null,
    val url: String? = null,
)
