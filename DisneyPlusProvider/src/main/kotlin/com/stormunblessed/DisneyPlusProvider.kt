package com.stormunblessed

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import java.util.UUID

class DisneyPlusProvider(private val api: DisneyApi) : MainAPI() {

    override var mainUrl = "https://www.disneyplus.com"
    override var name = "Disney+"
    override var lang = "mx"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val mapper = ObjectMapper()

    /** HLS master URL -> generated DASH manifest, served by [getVideoInterceptor]. */
    private val dashManifests = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Subtitle proxy URL -> HLS subtitle playlist, served by [getVideoInterceptor]. */
    private val subtitleSources = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val subtitleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        private const val LOGIN_REQUIRED = "Disney+ requiere una cuenta con suscripción activa. " +
            "Inicia sesión en: Ajustes del proveedor (icono de engranaje) -> Disney+ Login."
    }

    override val mainPage: List<MainPageData> = mainPageOf(
        "Inicio" to "home",
        "Películas" to "movies",
        "Series" to "series",
        "Originales" to "originals",
        "ESPN" to "espn",
    )

    private fun requireLogin() {
        if (!api.isLoggedIn) {
            throw ErrorLoadingException(LOGIN_REQUIRED)
        }
    }

    private fun getActions(item: JsonNode): Map<String, JsonNode> {
        val actions = linkedMapOf<String, JsonNode>()
        val arr = item["actions"] ?: return actions
        for (row in arr) {
            when (row["type"]?.asText()) {
                "playback" -> actions["playback"] = row
                "browse" -> actions["browse"] = row
                "modal" -> actions["modal"] = row
            }
        }
        val fallback = actions["browse"] ?: actions["modal"]
        if (actions["playback"] == null && fallback != null) {
            actions["playback"] = fallback
        }
        return actions
    }

    private fun JsonNode?.nodeList(): List<JsonNode> {
        if (this == null || !isArray) return emptyList()
        val out = mutableListOf<JsonNode>()
        forEach { out.add(it) }
        return out
    }

    private fun JsonNode?.textOrNull(): String? =
        this?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() && it != "null" }

    /** Content deeplink ids already carry the `entity-` prefix; don't add it twice. */
    private fun pageIdFor(deeplinkId: String): String =
        if (deeplinkId.startsWith("entity-") || deeplinkId.startsWith("page-")) deeplinkId
        else "entity-$deeplinkId"

    /** Adds the playback resource/type to a stored url so loadLinks does not need the raw deeplink. */
    private fun withPlaybackInfo(url: String, action: JsonNode?): String {
        val resourceId = action?.get("resourceId").textOrNull() ?: return url
        val fields = mutableMapOf<String, Any?>()
        runCatching { mapper.readTree(url) }.getOrNull()?.fields()?.forEach { (key, value) ->
            fields[key] = value.asText()
        }
        fields["resourceId"] = resourceId
        action?.get("contentType").textOrNull()?.let { fields["contentType"] = it }
        return mapper.writeValueAsString(fields)
    }

    /**
     * Disney artwork is nested as artwork.<variant>.<type>.<ratio>.imageId,
     * for example artwork.standard.tile.0.71.imageId.
     */
    private fun artworkUrl(
        visuals: JsonNode?,
        variant: String,
        imageType: String,
        ratio: String,
        width: Int = 800,
    ): String? {
        if (visuals == null) return null
        val entry = visuals["artwork"]?.get(variant)?.get(imageType)?.get(ratio) ?: return null
        val imageId = entry["imageId"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        return "${DisneyConstants.IMAGE_URL}/$imageId/scale?width=$width&aspectRatio=$ratio"
    }

    private fun posterUrl(visuals: JsonNode?): String? =
        artworkUrl(visuals, "standard", "tile", "0.71")
            ?: artworkUrl(visuals, "standard", "tile", "0.67")
            ?: artworkUrl(visuals, "standard", "thumbnail", "1.78")
            ?: artworkUrl(visuals, "hero", "background", "0.67")

    private fun backgroundUrl(visuals: JsonNode?): String? =
        artworkUrl(visuals, "hero", "background", "1.78")
            ?: artworkUrl(visuals, "details", "background", "1.78")
            ?: artworkUrl(visuals, "standard", "background", "1.78")
            ?: artworkUrl(visuals, "standard", "background", "1.33")

    private fun mediaType(visuals: JsonNode): TvType {
        if (visuals["badging"]?.get("airingEventState") != null) return TvType.Live
        return when (visuals["type"]?.asText()) {
            "Program", "TvShow", "Series" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun toSearchResponse(item: JsonNode): SearchResponse? {
        val visuals = item["visuals"] ?: return null
        val title = visuals["title"]?.asText() ?: visuals["name"]?.asText() ?: return null
        val play = getActions(item)["playback"] ?: return null
        val deeplinkId = play["deeplinkId"].textOrNull()
        val contentId = play["legacyPartnerFeed"]?.get("dmcContentId").textOrNull()
            ?: play["partnerFeed"]?.get("dmcContentId").textOrNull()
        val ref = deeplinkId ?: contentId ?: return null
        val payload = mutableMapOf<String, String>("deeplinkId" to ref)
        play["pageId"].textOrNull()?.let { payload["pageId"] = it }
        val url = mapper.writeValueAsString(payload)
        return newMovieSearchResponse(title, url, mediaType(visuals)) {
            this.posterUrl = posterUrl(visuals)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        requireLogin()
        val refId = request.data.ifBlank { "home" }.lowercase()
        val isEspn = refId == "espn" || request.name.contains("espn", ignoreCase = true)
        // The home screen loads every tab at once and fails as a whole if one throws.
        // A tab that is not available for this account/region must not break the rest.
        val rows = try {
            loadRows(refId, isEspn)
        } catch (e: Exception) {
            if (refId == "home") throw e
            emptyList()
        }
        return newHomePageResponse(rows, false)
    }

    private suspend fun loadRows(refId: String, isEspn: Boolean): List<HomePageList> {
        val deeplink = api.deeplink(refId)
        val browse = deeplink["actions"]?.firstOrNull { it["type"]?.asText() == "browse" }
            ?: throw ErrorLoadingException("Disney+ no devolvió acción browse para '$refId'")
        val pageId = browse["pageId"]?.asText()
            ?: throw ErrorLoadingException("Disney+ sin pageId para '$refId'")
        // `limit` is the number of items per row, not the number of rows.
        val pageJson = api.page(pageId, limit = 20, enhancedLimit = 99)

        val containers = pageJson["containers"].nodeList().filter {
            isShelf(it, includeHero = isEspn)
        }

        if (isEspn) {
            // ESPN exposes many shelves; collapse them into a single "live now" row.
            // Only keep items with a real playback action: the rest are promos or
            // require an ESPN subscription and would fail with "no links found".
            val live = linkedMapOf<String, SearchResponse>()
            val playable = linkedMapOf<String, SearchResponse>()
            for (container in containers) {
                for (item in container["items"].nodeList()) {
                    if (!hasPlaybackAction(item)) continue
                    val parsed = toSearchResponse(item) ?: continue
                    playable.putIfAbsent(parsed.url, parsed)
                    if (isLivePlayable(item)) live.putIfAbsent(parsed.url, parsed)
                }
            }
            val events = if (live.isNotEmpty()) live.values.toList() else playable.values.toList()
            return if (events.isEmpty()) emptyList() else listOf(HomePageList("ESPN En Vivo", events, false))
        }

        val rows = mutableListOf<HomePageList>()
        for (container in containers) {
            val name = container["visuals"]?.get("name")?.asText() ?: refId
            val parsed = container["items"].nodeList().mapNotNull { toSearchResponse(it) }
            if (parsed.isNotEmpty()) rows.add(HomePageList(name, parsed, false))
        }
        return rows
    }

    private fun isShelf(container: JsonNode, includeHero: Boolean = false): Boolean {
        val type = container["type"]?.asText()
        if (type == "details" || type == "episodes") return false
        val style = container["style"]?.get("name")?.asText() ?: ""
        if (style.contains("brand", true) || style.contains("continue_watching", true)) return false
        if (!includeHero && style.contains("hero", true)) return false
        return container["items"] != null
    }

    private fun hasPlaybackAction(item: JsonNode): Boolean =
        item["actions"]?.any { it["type"]?.asText() == "playback" } == true

    private fun isLivePlayable(item: JsonNode): Boolean {
        val playback = item["actions"]?.firstOrNull { it["type"]?.asText() == "playback" } ?: return false
        val contentType = playback["contentType"]?.asText()
        if (contentType == "linear" || contentType == "live") return true
        return item["visuals"]?.get("badging")?.get("airingEventState") != null
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        requireLogin()
        val pageJson = api.search(query)
        val containers = pageJson["containers"] ?: return null
        val container = containers.firstOrNull { it.has("items") } ?: containers.firstOrNull() ?: return null
        val items = container["items"] ?: return null
        return items.mapNotNull { toSearchResponse(it) }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        requireLogin()
        val data = mapper.readTree(url) ?: return null
        val deeplinkId = data["deeplinkId"].textOrNull() ?: return null
        val pageId = data["pageId"].textOrNull() ?: pageIdFor(deeplinkId)
        val pageJson = api.page(pageId, limit = 1, enhancedLimit = 15)
        return parseDetails(pageJson, url)
    }

    private suspend fun parseDetails(pageJson: JsonNode, url: String): LoadResponse? {
        val containers = pageJson["containers"] ?: return null
        var detailsNode: JsonNode? = null
        var episodesNode: JsonNode? = null
        for (c in containers) {
            when (c["type"]?.asText()) {
                "details" -> detailsNode = c
                "episodes" -> episodesNode = c
            }
        }
        // The details container only carries text; artwork lives on the page-level
        // visuals (and sometimes on the "set" container).
        val detailsVisuals = detailsNode?.get("visuals")
        val pageVisuals = pageJson["visuals"]
        val setVisuals = containers.firstOrNull { it["type"]?.asText() == "set" }?.get("visuals")
        val visuals = detailsVisuals ?: pageVisuals ?: return null
        val title = detailsVisuals?.get("title")?.asText()
            ?: pageVisuals?.get("title")?.asText()
            ?: return null
        val plot = detailsVisuals?.get("description")?.get("medium")?.asText()
            ?: detailsVisuals?.get("description")?.get("brief")?.asText()
            ?: pageVisuals?.get("description")?.get("medium")?.asText()
            ?: pageVisuals?.get("description")?.get("brief")?.asText()
            ?: ""
        val artworkSources = listOfNotNull(pageVisuals, setVisuals, detailsVisuals)
        val poster = artworkSources.firstNotNullOfOrNull { posterUrl(it) }
        val background = artworkSources.firstNotNullOfOrNull { backgroundUrl(it) }

        val isSeries = episodesNode != null || visuals["type"]?.asText() == "Program"
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            for (season in episodesNode?.get("seasons").nodeList()) {
                val seasonId = season["id"]?.asText() ?: continue
                val seasonData = api.season(seasonId)
                for (ep in seasonData["items"].nodeList()) {
                    val epVisuals = ep["visuals"] ?: continue
                    val epTitle = epVisuals["episodeTitle"]?.asText() ?: continue
                    val play = getActions(ep)["playback"] ?: continue
                    val epDeeplink = play["deeplinkId"].textOrNull() ?: continue
                    val epUrl = withPlaybackInfo(
                        mapper.writeValueAsString(mapOf("deeplinkId" to epDeeplink)),
                        play,
                    )
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = epVisuals["seasonNumber"]?.asInt()
                                ?: season["visuals"]?.get("seasonNumber")?.asInt()
                            this.episode = epVisuals["episodeNumber"]?.asInt()
                            this.posterUrl = artworkUrl(epVisuals, "standard", "thumbnail", "1.78")
                                ?: artworkUrl(epVisuals, "standard", "tile", "1.78")
                                ?: poster
                            this.description = epVisuals["description"]?.get("medium")?.asText()
                                ?: epVisuals["description"]?.get("brief")?.asText()
                            this.runTime = (epVisuals["durationMs"]?.asLong() ?: 0L).div(1000).toInt()
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
            }
        } else {
            val movieData = withPlaybackInfo(url, getActions(pageJson)["playback"])
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
            }
        }
    }

    /** Fallback for data stored without a playback resource: read it from the entity page. */
    private suspend fun resolvePlaybackAction(deeplinkId: String?): JsonNode? {
        if (deeplinkId.isNullOrBlank()) return null
        val pageJson = runCatching {
            api.page(pageIdFor(deeplinkId), limit = 1, enhancedLimit = 15)
        }.getOrNull() ?: return null
        return getActions(pageJson)["playback"]
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        requireLogin()
        val json = try {
            mapper.readTree(data) ?: return false
        } catch (e: Exception) {
            return false
        }
        var action: JsonNode? = null
        val storedResourceId = json["resourceId"].textOrNull()
        val resourceId = storedResourceId ?: run {
            action = resolvePlaybackAction(json["deeplinkId"].textOrNull())
            action?.get("resourceId").textOrNull()
        } ?: return false
        val contentType = json["contentType"].textOrNull() ?: action?.get("contentType").textOrNull()
        val isLinear = contentType == "linear" || contentType == "live"

        val playback = api.playback(resourceId)
        val source = playback["stream"]["sources"]?.firstOrNull() ?: return false
        // Live feeds can report either field; try the expected one first.
        val streamUrl = if (isLinear) {
            source["slide"]?.get("url")?.asText() ?: source["complete"]?.get("url")?.asText()
        } else {
            source["complete"]?.get("url")?.asText() ?: source["slide"]?.get("url")?.asText()
        }
        if (streamUrl == null) return false

        val (vodLicense, linearLicense) = api.widevineLicenseEndpoints()
        val licenseUrl = if (isLinear) linearLicense else vodLicense

        // The media CDN rejects any request that carries an `authorization` header
        // (the signed URL is the auth), while the Widevine endpoint requires it.
        // Add it per-host in getVideoInterceptor instead of on the link itself.
        val manifestHeaders = mapOf(
            "User-Agent" to DisneyConstants.userAgent(),
        )

        // Disney+ is HLS-only, but CloudStream plays DRM exclusively through DASH.
        // Convert the HLS master to a DASH manifest and serve it via the interceptor.
        val preferredAudio = runCatching { api.profileContext().appLanguage }.getOrNull()
        val dash = runCatching { DisneyDash.buildManifest(streamUrl, preferredAudio) }.getOrNull()
        if (dash != null) dashManifests[streamUrl] = dash

        // HLS subtitles are WebVTT segments; expose one proxy .vtt URL per language
        // and let the interceptor merge the segments when the user picks one.
        val subtitles = runCatching { DisneyDash.buildSubtitles(streamUrl) }.getOrDefault(emptyList())
        subtitles.forEachIndexed { index, subtitle ->
            val proxyUrl = "https://disney.local/subtitles/$index.vtt"
            subtitleSources[proxyUrl] = subtitle.playlistUrl
            subtitleCallback.invoke(newSubtitleFile(subtitle.lang, proxyUrl))
        }

        val link = newDrmExtractorLink(
            "Disney+",
            "Disney+",
            streamUrl,
            if (dash != null) ExtractorLinkType.DASH else ExtractorLinkType.M3U8,
            WIDEVINE_UUID,
        ) {
            this.licenseUrl = licenseUrl
            this.headers = manifestHeaders
            this.quality = Qualities.Unknown.value
        }
        callback.invoke(link)
        return true
    }

    /**
     * Disney's media CDN returns 400 to any request that includes an `authorization`
     * header, but the Widevine license endpoint requires it. Scope the token to the
     * license host only.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val manifest = dashManifests[extractorLink.url]
        val manifestUrl = extractorLink.url.toHttpUrlOrNull()
        return Interceptor { chain ->
            val request = chain.request()

            val subtitlePlaylist = subtitleSources[request.url.toString()]
            if (subtitlePlaylist != null) {
                val key = request.url.toString()
                val vtt = subtitleCache[key] ?: runBlocking {
                    DisneyDash.buildSubtitleVtt(subtitlePlaylist)
                }?.also { subtitleCache[key] = it }
                if (!vtt.isNullOrEmpty()) {
                    val body = vtt.toResponseBody("text/vtt".toMediaType())
                    return@Interceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/vtt")
                        .header("Content-Length", body.contentLength().toString())
                        .header("Accept-Ranges", "none")
                        .body(body)
                        .build()
                }
            }

            val isManifest = manifest != null && manifestUrl != null &&
                request.url.host == manifestUrl.host &&
                request.url.encodedPath == manifestUrl.encodedPath

            if (isManifest && manifest != null) {
                val body = manifest.toResponseBody("application/dash+xml".toMediaType())
                return@Interceptor Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/dash+xml")
                    .header("Content-Length", body.contentLength().toString())
                    .header("Accept-Ranges", "none")
                    .body(body)
                    .build()
            }

            val builder = request.newBuilder()
            if (request.url.host == "disney.playback.edge.bamgrid.com") {
                api.accessToken?.takeIf { it.isNotBlank() }?.let {
                    builder.header("authorization", "Bearer $it")
                }
            } else {
                builder.removeHeader("authorization")
            }
            chain.proceed(builder.build())
        }
    }
}
