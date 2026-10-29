package com.stormunblessed

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Extension name this module's link labels must end with (AGENTS.md "Source-name standard").
 * Keep in sync with the provider's `override var name`.
 */
const val EXTENSION_NAME = "PPV Sports"

/**
 * Standard visible label for a stream: "<Idioma> - <Servidor> - <Extensión>",
 * e.g. "English - Prime Video - PPV Sports".
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

/**
 * Every ppv.st stream is an "External Player Frame" iframe on a rotating host whose page builds
 * its HLS url in obfuscated JS + wasm (a POST /fetch answer decoded client side), so there is
 * nothing to parse over plain HTTP. Load the page the way a browser does and intercept the
 * manifest request instead.
 */
suspend fun resolveEmbedM3u8(embed: String, referer: String?): String? {
    val m3u8Resolver = WebViewResolver(
        interceptUrl = Regex(""".*\.m3u8.*"""),
        additionalUrls = listOf(Regex(""".*\.m3u8.*""")),
        useOkhttp = false,
        timeout = 60_000L
    )
    val response = app.get(embed, referer = referer, interceptor = m3u8Resolver)
    return response.url.takeIf { it.contains(".m3u8") }
}
