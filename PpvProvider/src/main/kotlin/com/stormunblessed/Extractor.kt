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
    val cleaned = server?.replace(Regex("[^\\p{L}\\p{N} .&+_-]"), "")
        ?.replace(Regex(" {2,}"), " ")
        ?.trim()
        .orEmpty()
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

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * The embed player chooses its HLS engine from the user agent and, on Android, hands the
 * playlist to the native media stack; those requests never reach
 * `WebViewClient.shouldInterceptRequest`, so a plain "wait for the .m3u8 request" resolver times
 * out. Whatever engine is used, the decrypted url is already inside the jwplayer instance, so a
 * small injected watcher polls for it and requests it as an image; that request does go through
 * the WebView and can be intercepted.
 */
private val M3U8_PROBE_SCRIPT = """
    (function () {
      if (window.__ppvM3u8Done) return;

      function itemUrl(item) {
        if (!item) return null;
        if (item.file) return item.file;
        if (item.sources && item.sources.length > 0) return item.sources[0].file;
        return null;
      }

      function playerUrl(api) {
        if (!api) return null;
        try {
          if (typeof api.getPlaylist === 'function') {
            var pl = api.getPlaylist();
            if (pl && pl.length > 0) {
              var u = itemUrl(pl[0]);
              if (u) return u;
            }
          }
        } catch (e) {}
        try {
          if (typeof api.getPlaylistItem === 'function') {
            var it = itemUrl(api.getPlaylistItem());
            if (it) return it;
          }
        } catch (e) {}
        try {
          if (typeof api.getConfig === 'function') {
            var c = api.getConfig();
            if (c) {
              if (c.file) return c.file;
              if (c.playlist && c.playlist.length > 0) {
                var cu = itemUrl(c.playlist[0]);
                if (cu) return cu;
              }
            }
          }
        } catch (e) {}
        return null;
      }

      function step() {
        try {
          var u = null;
          if (typeof jwplayer === 'function') {
            var apis = [];
            try { apis.push(jwplayer()); } catch (e) {}
            try { apis.push(jwplayer('player')); } catch (e) {}
            for (var i = 0; i < apis.length; i++) {
              if (!u) u = playerUrl(apis[i]);
              try {
                if (apis[i] && typeof apis[i].play === 'function' && !window.__ppvPlayTried) {
                  apis[i].play();
                  window.__ppvPlayTried = true;
                }
              } catch (e) {}
            }
          }
          if (!u) {
            try {
              var v = document.querySelector('video');
              if (v) u = v.currentSrc || v.src;
            } catch (e) {}
          }
          if (u && u.indexOf('.m3u8') !== -1) {
            window.__ppvM3u8Done = true;
            (new Image()).src = u;
            return;
          }
          return;
        } catch (e) {
          return;
        }
      }

      if (!window.__ppvM3u8Watch) {
        window.__ppvM3u8Watch = setInterval(step, 500);
      }
      return step();
    })();
""".trimIndent()

/**
 * The player page builds its HLS url in obfuscated JS + wasm (a POST /fetch answer decoded client
 * side), so there is nothing to parse over plain HTTP. Load the page like a browser, let the
 * watcher above surface the decrypted url, and intercept that request.
 */
suspend fun resolveEmbedM3u8(embed: String, referer: String?): String? {
    val m3u8Resolver = WebViewResolver(
        interceptUrl = Regex(""".*\.m3u8.*"""),
        additionalUrls = listOf(Regex(""".*\.m3u8.*""")),
        userAgent = DESKTOP_USER_AGENT,
        script = M3U8_PROBE_SCRIPT,
        useOkhttp = false,
        timeout = 60_000L
    )
    val response = app.get(embed, referer = referer, interceptor = m3u8Resolver)
    return response.url.takeIf { it.contains(".m3u8") }
}
