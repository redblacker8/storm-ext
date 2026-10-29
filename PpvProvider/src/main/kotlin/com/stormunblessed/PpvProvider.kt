package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class PpvProvider : MainAPI() {
    override var mainUrl = "https://ppv.st"
    override var name = "PPV Sports"
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
        "24-7" to "24/7 Channels",
        "American Football" to "Am. Football",
        "Australian Football" to "Aussie Football",
        "Basketball" to "Basketball",
        "Cricket" to "Cricket",
        "Football" to "Football",
        "Golf" to "Golf",
        "Ice Hockey" to "Hockey",
        "Rugby" to "Rugby",
        "Wrestling" to "Wrestling",
        "all" to "All Sports",
    )

    /**
     * The site rotates its api between these domains (its own runtime config lists them as
     * `apiDomains`, with api.ppv.cx as the backup `apiBase2`). Try the main one first and
     * remember whichever answers for the rest of the session.
     */
    private val apiBases = listOf(
        "https://api.ppv.st/api",
        "https://api.ppv.cx/api",
        "https://api.ppv.tj/api",
        "https://api.ppvs.pk/api",
        "https://api.ppv.rw/api",
        "https://api.ppv.ms/api",
        "https://api.ppv.bi/api",
        "https://api.ppv.ug/api",
    )
    private var preferredApi: String = apiBases.first()

    /** GET <mirror>/api<path>, walking the mirror list until one answers. Null when they all fail. */
    private suspend fun apiGet(path: String): String? {
        val bases = listOf(preferredApi) + apiBases.filter { it != preferredApi }
        for (base in bases) {
            val text = runCatching {
                val res = app.get("$base$path")
                res.text.takeIf { res.code == 200 && it.isNotBlank() }
            }.getOrNull() ?: continue
            preferredApi = base
            return text
        }
        return null
    }

    /** GET /api/streams -> every category's rows flattened into catalog items. Empty list on any failure. */
    private suspend fun fetchCatalog(): List<PpvItem> {
        val text = apiGet("/streams") ?: return emptyList()
        return runCatching {
            AppUtils.tryParseJson<PpvStreamsResponse>(text)?.streams
                ?.flatMap { it.streams.orEmpty() }
                ?.map { it.toItem() }
        }.getOrNull() ?: emptyList()
    }

    /** GET /api/streams/<uri_name> -> event details (description, sources). Null on any failure. */
    private suspend fun fetchDetail(info: PpvItem): PpvDetail? {
        val uri = info.uri_name ?: return null
        return runCatching {
            AppUtils.tryParseJson<PpvDetailResponse>(apiGet("/streams/$uri") ?: "")?.data
        }.getOrNull()
    }

    private fun PpvItem.toSearchResult(): SearchResponse {
        return newLiveSearchResponse(this.name ?: "Unknown", this.toJson(), TvType.Live) {
            this.posterUrl = this@toSearchResult.poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val now = APIHolder.unixTime
        val all = fetchCatalog()
        val events = when (request.data) {
            "all" -> all.sortedBy { it.starts_at ?: Long.MAX_VALUE }
            "live" -> all.filter { it.isLiveAt(now) }.sortedBy { it.starts_at ?: Long.MAX_VALUE }
            "popular" -> all.sortedByDescending { it.viewers ?: 0 }
            "24-7" -> all.filter { it.always_live == 1 }
            else -> all.filter { it.category_name == request.data }
                .sortedBy { it.starts_at ?: Long.MAX_VALUE }
        }
        val home = events.map { it.toSearchResult() }
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
        return fetchCatalog().filter {
            it.name?.contains(query, ignoreCase = true) == true ||
                    it.tag?.contains(query, ignoreCase = true) == true ||
                    it.source_tag?.contains(query, ignoreCase = true) == true
        }.map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val info = AppUtils.tryParseJson<PpvItem>(url) ?: return null
        val detail = fetchDetail(info)
        return newLiveStreamLoadResponse(info.name ?: "Unknown", url, url) {
            this.posterUrl = info.poster
            this.plot = detailPlot(info, detail)
        }
    }

    /** The api serves a template ("Watch {name} in HD ..."), fill it in; fall back to tag + channel. */
    private fun detailPlot(info: PpvItem, detail: PpvDetail?): String? {
        val template = detail?.description
        return if (template.isNullOrBlank()) {
            listOfNotNull(info.tag, info.source_tag).joinToString(" · ").ifBlank { null }
        } else {
            template
                .replace("{name}", info.name.orEmpty())
                .replace("{host}", mainUrl.removePrefix("https://"))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = AppUtils.tryParseJson<PpvItem>(data) ?: return false
        feedsOf(info).amap { feed ->
            runCatching { resolveStream(feed, subtitleCallback, callback) }
        }
        return true
    }

    /** One playable feed: the main player plus every alternate substream (SkyCast, ...). */
    private data class Feed(val server: String?, val lang: String?, val url: String)

    private fun feedsOf(info: PpvItem): List<Feed> {
        val feeds = mutableListOf<Feed>()
        if (!info.iframe.isNullOrBlank()) {
            feeds += Feed(info.source_tag, info.locale, info.iframe)
        }
        info.substreams.orEmpty().forEach { sub ->
            if (!sub.iframe.isNullOrBlank()) {
                feeds += Feed(
                    sub.source_tag ?: info.source_tag,
                    sub.locale ?: info.locale,
                    sub.iframe
                )
            }
        }
        return feeds
    }

    /**
     * Direct .m3u8 urls go straight to M3u8Helper; the external player frames are resolved in a
     * WebView (their HLS url is built by obfuscated client code, see resolveEmbedM3u8), and
     * anything unresolved is handed to the generic extractors as a last resort.
     */
    private suspend fun resolveStream(
        feed: Feed,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = feed.url
        if (embed.contains(".m3u8")) {
            generateLinks(feed.server, feed.lang, embed, "$mainUrl/", callback)
            return
        }
        val m3u8 = runCatching { resolveEmbedM3u8(embed, "$mainUrl/") }.getOrNull()
        if (!m3u8.isNullOrBlank()) {
            generateLinks(feed.server, feed.lang, m3u8, embed, callback)
        } else {
            loadSourceNameExtractor(feed.lang, embed, "$mainUrl/", subtitleCallback, callback)
        }
    }

    private suspend fun generateLinks(
        server: String?,
        lang: String?,
        m3u8: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        M3u8Helper.generateM3u8(
            normalizeSourceServer(server),
            m3u8,
            referer,
            name = standardSourceName(lang, server)
        ).forEach(callback)
    }
}
