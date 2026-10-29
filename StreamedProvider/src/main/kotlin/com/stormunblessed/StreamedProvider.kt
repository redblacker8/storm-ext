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
import com.stormunblessed.SportsBiteMatches
import com.stormunblessed.defaultPoster
import com.stormunblessed.imageId
import com.stormunblessed.loadSourceNameExtractor
import com.stormunblessed.normalizeSourceServer
import com.stormunblessed.standardSourceName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://sportsbite.org"
    override var name = "Streamed Sports"
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
            this.posterUrl = poster ?: defaultPoster
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
            this.posterUrl = poster ?: defaultPoster
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
        streams.amap { stream ->
            runCatching { resolveStream(stream, subtitleCallback, callback) }
        }
        return true
    }

    /**
     * Every sportsbite stream is an iframe player whose page embeds the HLS url as
     * `var source = window.atob("<base64>")`; fetch it and hand the manifest to M3u8Helper.
     * Direct .m3u8 links are passed through, anything else falls back to the generic extractors.
     */
    private suspend fun resolveStream(
        stream: SBStream,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = stream.manifest_url ?: return
        val server = stream.source ?: stream.name
        val lang = stream.language
        if (embed.contains(".m3u8")) {
            generateLinks(server, lang, embed, "$mainUrl/", callback)
            return
        }
        val html = app.get(
            embed,
            referer = "$mainUrl/",
            headers = mapOf("Sec-Fetch-Dest" to "iframe")
        ).text
        val encoded = Regex("""window\.atob\("([A-Za-z0-9+/=]+)"\)""")
            .find(html)?.groupValues?.get(1)
        val m3u8 = encoded?.let { base64Decode(it) }
        if (!m3u8.isNullOrBlank() && m3u8.startsWith("http")) {
            generateLinks(server, lang, m3u8, embed, callback)
        } else {
            loadSourceNameExtractor(lang, embed, "$mainUrl/", subtitleCallback, callback)
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
