package com.stormunblessed

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EmbedSports : ExtractorApi() {
    override val name = "EmbedSports"
    override val mainUrl = "https://embed.st"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink> {
        val page = app.get(url, referer = referer).text

        val iframeSrc = Regex("""src="(https://embedhd\.st[^"]+)""").find(page)?.groupValues?.get(1)
        if (iframeSrc != null) {
            return extractFromIframeChain(iframeSrc, url)
        }

        return extractFromWebView(url, referer)
    }

    private suspend fun extractFromIframeChain(iframeSrc: String, referer: String): List<ExtractorLink> {
        val hdPage = app.get(iframeSrc, referer = referer).text
        val fid = Regex("""fid\s*=\s*"([^"]+)""").find(hdPage)?.groupValues?.get(1)
            ?: return emptyList()

        val maestroUrl = "https://exposestrat.com/maestrohd1.php?player=desktop&live=$fid"
        val maestroPage = app.get(maestroUrl, referer = iframeSrc).text

        val arrayStart = "return(["
        val joinEnd = "].join(\"\")"
        val startIdx = maestroPage.indexOf(arrayStart)
        val endIdx = maestroPage.indexOf(joinEnd, startIdx)
        if (startIdx == -1 || endIdx == -1) return emptyList()

        val arrayContent = maestroPage.substring(startIdx + arrayStart.length - 1, endIdx + joinEnd.length)
        val chars = Regex("\"([^\"]*)\"").findAll(arrayContent).map { it.groupValues[1] }.toList()
        if (chars.isEmpty()) return emptyList()

        val streamUrl = chars.joinToString("").replace("\\/", "/")

        return listOf(
            newExtractorLink(name, name, streamUrl) {
                this.type = ExtractorLinkType.M3U8
                this.referer = "https://exposestrat.com/"
            }
        )
    }

    private suspend fun extractFromWebView(url: String, referer: String?): List<ExtractorLink> {
        val m3u8Resolver = WebViewResolver(
            interceptUrl = Regex(""".*\.m3u8.*"""),
            additionalUrls = listOf(Regex(""".*\.m3u8.*""")),
            useOkhttp = false,
            timeout = 60_000L
        )
        val response = app.get(url, referer = referer, interceptor = m3u8Resolver)
        val finalUrl = response.url
        if (finalUrl.contains(".m3u8")) {
            return listOf(
                newExtractorLink(name, name, finalUrl) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = "$mainUrl/"
                }
            )
        }
        return emptyList()
    }
}

/**
 * Extension name this module's link labels must end with (AGENTS.md "Source-name standard").
 * Keep in sync with the provider's `override var name`.
 */
const val EXTENSION_NAME = "Streamed Sports"

/**
 * Standard visible label for a stream: "<Idioma> - <Servidor> - <Extensión>",
 * e.g. "English - Streamking - Streamed Sports".
 */
fun standardSourceName(lang: String?, server: String?): String =
    listOf(normalizeSourceLang(lang), normalizeSourceServer(server), EXTENSION_NAME)
        .joinToString(" - ")

/** Canonical language labels, most common first: Latino, Español, English. Blank/null -> provider default (English). */
fun normalizeSourceLang(lang: String?): String {
    val raw = lang?.trim().orEmpty()
    if (raw.isEmpty()) return "English"
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
    val cleaned = server?.replace(Regex("[^\\p{L}\\p{N} .&+_-]"), "")?.trim().orEmpty()
    if (cleaned.isEmpty()) return "Directo"
    val needsCapital = cleaned.all { it.isLowerCase() || !it.isLetter() }
    return if (needsCapital) cleaned.replaceFirstChar { it.uppercaseChar() } else cleaned
}

/**
 * Forwards an embedded url to the matching extractor and relabels the result with the
 * standard "<Idioma> - <Servidor> - <Extensión>" name while keeping `link.source` untouched.
 */
suspend fun loadSourceNameExtractor(
    lang: String?,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            val name = standardSourceName(lang, link.source)
            callback.invoke(
                newExtractorLink(
                    link.source,
                    name,
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}