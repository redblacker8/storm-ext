package com.stormunblessed

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
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
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.ui.player.CS3IPlayer.Companion.preferredAudioTrackLanguage
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import android.util.Base64
import android.widget.Toast
import android.util.Log
import okio.Buffer
import java.util.Locale

/**
 * CloudStream provider for Amazon Prime Video.
 *
 * Uses the mobile/CDP API (getDataByTransform) for catalog data:
 *   GET {atvUrl}/cdp/mobile/getDataByTransform/v1/{transform}?{params}
 *   with x-amzn-identity-auth-token (from at-main-av cookie)
 *
 * Auth:
 *   - Atza token (at-main-av cookie) → mobile API for browsing
 *   - Refresh token → Bearer token → mobile API + playback
 *   - Cookies → HTML scraping (legacy fallback for primevideo.com)
 */
class PrimeVideoProvider(private val api: PrimeVideoApi) : MainAPI() {

    override var mainUrl = "https://www.primevideo.com"
    override var name = "Prime Video"
    override var lang = "mx"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val jsonMapper = ObjectMapper()

    companion object {
        private val WIDEVINE_UUID: java.util.UUID = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        private const val LOGIN_REQUIRED = "Prime Video requiere una cuenta activa. " +
            "Inicia sesión en: Ajustes del proveedor (icono de engranaje) -> Prime Video Login."
    }

    // Navigation tabs.
    // NOTE: mainPageOf uses (data, name) order — the first element is what
    // getMainPage receives in `request.data`. Getting this backwards makes
    // every tab fall into the default branch (home/home).
    override val mainPage: List<MainPageData> = mainPageOf(
        "home" to "Inicio",
        "sports" to "Deportes",
        "movies" to "Películas",
        "series" to "Series",
        "live" to "TV en Vivo",
    )

    // ────────────────────────────────────────────────────────────────────
    // Mobile API page fetch
    // ────────────────────────────────────────────────────────────────────

    /**
     * Maps a tab name to the mobile API transform params.
     */
    private fun pageTypeAndId(refId: String): Pair<String, String> {
        return when (refId) {
            "sports" -> "home" to "Sports"  // Sports tab
            "movies" -> "movie" to "home"   // Movies browse
            "series" -> "tv" to "home"      // TV shows browse
            // NOTE: pageId is case-sensitive. "LiveTV"/"LiveEvents" make the
            // transform throw and return no collections; "live" returns the
            // "Live and upcoming events" row.
            "live" -> "home" to "live"      // Live TV
            else -> "home" to "home"        // Default / home
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Main page
    // ────────────────────────────────────────────────────────────────────

    private fun requireLogin() {
        if (!api.isLoggedIn) throw ErrorLoadingException(LOGIN_REQUIRED)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        requireLogin()
        val refId = request.data.ifBlank { "home" }.lowercase()
        val isSports = refId == "sports"

        val (pageType, pageId) = pageTypeAndId(refId)

        suspend fun fetchLanding(pt: String, pid: String) =
            api.getMobilePage("dv-android/landing/v1.js", "&pageType=$pt&pageId=$pid")

        var resource = try {
            fetchLanding(pageType, pageId)
        } catch (e: Exception) {
            Log.w("PrimeVideoHP", "Mobile API: ${e.message}")
            throw ErrorLoadingException(
                e.message ?: "Prime Video: no se pudo cargar la página. Verifica el token de acceso.",
            )
        }

        var collections = resource["collections"]?.takeIf { it.size() > 0 }

        // The dedicated live page can come back empty while Sports still lists
        // the "Live and upcoming events" row, so fall back to it.
        if (collections == null && refId == "live") {
            resource = try {
                fetchLanding("home", "Sports")
            } catch (e: Exception) {
                Log.w("PrimeVideoHP", "Live fallback (Sports): ${e.message}")
                resource
            }
            collections = resource["collections"]?.takeIf { it.size() > 0 }
        }

        Log.i(
            "PrimeVideoHP",
            "tab=$refId page=$pageType/$pageId collections=${collections?.size() ?: 0}",
        )

        if (collections == null) {
            throw ErrorLoadingException(
                if (refId == "live" || isSports) {
                    "Prime Video: no hay eventos en vivo en este momento."
                } else {
                    "Prime Video: sin secciones para la región actual. " +
                        "Cambia la región en Ajustes del proveedor → Prime Video Login."
                },
            )
        }

        val rows = mutableListOf<HomePageList>()
        for (c in collections) {
            // Skip Amazon's hero/banner carousel: it only carries promo art (8:3)
            // with placeholder text ("Carousel Title") and duplicates the title rows.
            if (isBannerCollection(c)) continue

            val headerText = c["headerText"].textOrNull() ?: c["text"].textOrNull() ?: "Sección"
            if (headerText.isBlank()) continue

            val items = c["collectionItemList"]
            if (items == null || items.size() == 0) continue

            val parsed = items.mapNotNull { toSearchResponse(it) }
            if (parsed.isEmpty()) continue

            if (isSports) {
                // Use Amazon's collection header so the rows have distinct names.
                val liveItems = parsed.filter { it.type == TvType.Live }
                if (liveItems.isNotEmpty()) {
                    rows.add(HomePageList(headerText, liveItems, isHorizontalImages = true))
                }
                val scheduleItems = parsed.filter { it.type != TvType.Live }
                if (scheduleItems.isNotEmpty()) {
                    rows.add(HomePageList("$headerText · Programación", scheduleItems, isHorizontalImages = true))
                }
            } else {
                rows.add(HomePageList(headerText, parsed, isHorizontalImages = true))
            }
        }

        Log.i("PrimeVideoHP", "tab=$refId rows=${rows.size} ${rows.map { "${it.name}(${it.list.size})" }}")
        return newHomePageResponse(rows, hasNext = false)
    }

    /**
     * True for Amazon's hero/banner carousel: a collection whose items are all
     * promo art (heroTitle / imageTextLink) or whose header is the placeholder
     * "Carousel Title". Those items have no title metadata and an 8:3 aspect.
     */
    private fun isBannerCollection(collection: JsonNode): Boolean {
        val header = collection["headerText"].textOrNull()
        if (header != null && header.equals("Carousel Title", ignoreCase = true)) return true

        val items = collection["collectionItemList"]?.takeIf { it.isArray } ?: return true
        if (items.size() == 0) return true

        var banners = 0
        var total = 0
        for (item in items) {
            total++
            when (item["type"].textOrNull()?.lowercase()) {
                "herotitle", "imagetextlink", "hero", "buttonlink" -> banners++
            }
        }
        return banners == total
    }

    // ────────────────────────────────────────────────────────────────────
    // Entity → SearchResponse
    // ────────────────────────────────────────────────────────────────────

    private fun toSearchResponse(item: JsonNode): SearchResponse? {
        // Mobile API items: {"type":"titleCard","model":{...}}
        val model = item["model"]?.takeIf { it.isObject } ?: item
        // Also handle {type, model} or direct model from collectionItemList
        val effective = item["type"].textOrNull()
            ?.let { model } ?: item

        // NOTE: NullNode.asText() returns the "null" string, so always go
        // through textOrNull() (which also drops a literal "null").
        val title = effective["title"].textOrNull()
            ?: effective["displayTitle"].textOrNull()
            ?: effective["heading"].textOrNull()
            ?: effective["headline"].textOrNull()
            ?: effective["text"].textOrNull()
            ?: effective["accessibilityDescription"].textOrNull()
            ?: return null

        // Content type
        val contentType = effective["contentType"].textOrNull()?.lowercase() ?: ""
        val isLive = contentType == "live_event" || contentType == "liveevent" ||
            contentType == "event" ||
            effective["entityType"].textOrNull()?.lowercase() == "live_event"

        // Image URL — from various possible locations
        val posterUrl = extractPosterUrl(effective)

        // Title ID (GTI format: amzn1.dv.gti.xxxx)
        val titleId = effective["id"].textOrNull()
            ?: effective["titleId"].textOrNull()
            ?: effective["linkAction"]?.get("titleId").textOrNull()
            ?: return null

        // Serialize payload
        // `isLive` is required by loadLinks to pick the LiveStreaming params;
        // without it live events go out as Feature and Amazon answers
        // PRS.InvalidRequest ("no links found").
        val payload = mutableMapOf<String, Any>(
            "url" to titleId,
            "title" to title,
            "isLive" to isLive,
        )
        posterUrl?.let { payload["poster"] = it }

        val serialized = jsonMapper.writeValueAsString(payload)

        if (isLive) {
            return newLiveSearchResponse(title, serialized, TvType.Live) {
                this.posterUrl = posterUrl
            }
        }

        val tvType = when (contentType) {
            "season", "episode", "series" -> TvType.TvSeries
            "live_event", "liveevent", "event" -> TvType.Live
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, serialized, tvType) {
            this.posterUrl = posterUrl
        }
    }

    private fun extractPosterUrl(node: JsonNode): String? {
        // Mobile API image locations:
        // model.imageUrl — direct URL
        node["imageUrl"].textOrNull()?.let { return it }
        // titleImageUrls — map of named images (keys: WIDE, LEGACY, TITLE_LOGO, etc.)
        val titleImages = node["titleImageUrls"]?.takeIf { it.isObject }
        if (titleImages != null) {
            // Prefer WIDE (wide banner), then LEGACY, then TITLE_LOGO
            for (key in listOf("WIDE", "WIDE_PRIME", "LEGACY", "LEGACY_PRIME", "TITLE_LOGO", "hero", "poster", "thumbnail")) {
                titleImages[key].textOrNull()?.let { return it }
            }
        }
        // images — old format
        val images = node["images"]?.takeIf { !it.isNull }
        if (images != null) {
            images["cover"]?.get("url").textOrNull()?.let { return it }
            images["boxart"]?.get("url").textOrNull()?.let { return it }
            images["hero"]?.get("url").textOrNull()?.let { return it }
            images["titleLogo"]?.get("url").textOrNull()?.let { return it }
        }
        // background image
        node["backgroundImageUrl"].textOrNull()?.let { return it }
        return node["image"]?.get("url").textOrNull()
            ?: node["imageSrc"].textOrNull()
    }

    // ────────────────────────────────────────────────────────────────────
    // Search
    // ────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        requireLogin()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        // Storefront search. The mobile search transform always answers
        // "We didn't find any matches" for browser sessions, so this comes first.
        val results = try {
            api.getWebPageBody("/search?phrase=$encoded")
                ?.let { webEntities(it).mapNotNull { entity -> toWebSearchResponse(entity) } }
                .orEmpty()
        } catch (e: Exception) {
            Log.w("PrimeVideoSearch", "Web search failed: ${e.message}")
            emptyList()
        }
        if (results.isNotEmpty()) return results.toNewSearchResponseList()

        // Fallback: mobile API (works on device/Bearer sessions)
        val resource = try {
            api.getMobilePage("dv-android/search/searchInitial/v3.js", "&pageType=search&pageId=search&phrase=$encoded")
        } catch (e: Exception) {
            Log.w("PrimeVideoSearch", "Search failed: ${e.message}")
            return null
        }

        val items = resource["collections"]?.firstOrNull { it.has("collectionItemList") }
            ?.get("collectionItemList")
        if (items == null || items.size() == 0) return null

        return items.mapNotNull { toSearchResponse(it) }.toNewSearchResponseList()
    }

    // ────────────────────────────────────────────────────────────────────
    // Storefront (HTML hydration) parsing
    // ────────────────────────────────────────────────────────────────────

    /** All `entities` from every container of a storefront page (search/home). */
    private fun webEntities(body: JsonNode?): List<JsonNode> {
        val containers = body?.get("containers")?.takeIf { it.isArray } ?: return emptyList()
        val out = mutableListOf<JsonNode>()
        for (container in containers) {
            val entities = container["entities"]?.takeIf { it.isArray } ?: continue
            entities.forEach { out.add(it) }
        }
        return out
    }

    private fun webPoster(node: JsonNode?): String? {
        val images = node?.get("images")?.takeIf { it.isObject } ?: return null
        // Detail pages: `packshot`/`titleshot` are the vertical (2:3) box art,
        // `covershot`/`heroshot` are 16:9 frames that get cropped on poster cards.
        for (key in listOf("packshot", "titleshot", "covershot", "heroshot", "titleLogo")) {
            images[key].textOrNull()?.let { return it }
        }
        // Search/grid pages use a composited card image under `cover`
        val cover = images["cover"]?.let { c ->
            c["url"].textOrNull() ?: c.textOrNull()
        } ?: return null
        return portraitImageUrl(cover)
    }

    /**
     * Search/grid cards only expose a 16:9 composited `cover`. Asking Amazon's
     * image service for the same asset on a 2:3 canvas returns the vertical key
     * art instead of the cropped landscape frame.
     */
    private fun portraitImageUrl(url: String): String {
        val base = url.substringBefore("._")
        if (base == url) return url
        return "$base._UR600,900_CLs%7C600,900%7C_.jpg"
    }

    private fun toWebSearchResponse(entity: JsonNode): SearchResponse? {
        val title = entity["displayTitle"].textOrNull() ?: return null
        val titleId = entity["titleID"].textOrNull() ?: return null

        val entityType = entity["entityType"].textOrNull()?.lowercase().orEmpty()
        val isLive = entityType.contains("live") || entityType.contains("event")
        val tvType = when {
            isLive -> TvType.Live
            entityType.contains("tv") || entityType.contains("season") ||
                entityType.contains("series") || entityType.contains("episode") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val posterUrl = webPoster(entity)
        val payload = mutableMapOf<String, Any>(
            "url" to titleId,
            "title" to title,
            "isLive" to isLive,
        )
        posterUrl?.let { payload["poster"] = it }
        entity["releaseYear"].textOrNull()?.let { payload["year"] = it }
        val serialized = jsonMapper.writeValueAsString(payload)

        if (isLive) {
            return newLiveSearchResponse(title, serialized, TvType.Live) {
                this.posterUrl = posterUrl
            }
        }
        return newMovieSearchResponse(title, serialized, tvType) {
            this.posterUrl = posterUrl
        }
    }

    /** First value of a `{key: value}` object node. */
    private fun firstChild(node: JsonNode?): JsonNode? {
        if (node == null || !node.isObject) return null
        val iterator = node.elements()
        return if (iterator.hasNext()) iterator.next() else null
    }

    /**
     * Builds the detail response out of a storefront detail page
     * (`/detail/<asin or gti>`), including every season of a series.
     */
    /** Metadata pulled out of a storefront detail page. */
    private data class WebDetailInfo(
        val title: String? = null,
        val plot: String? = null,
        val poster: String? = null,
        val year: Int? = null,
        val tags: List<String>? = null,
        val seasonNumber: Int? = null,
        val titleType: String = "",
    )

    private fun webDetailInfo(body: JsonNode): WebDetailInfo {
        val atfState = body["atf"]?.get("state")
        val btfState = body["btf"]?.get("state")

        val header = firstChild(atfState?.get("detail")?.get("headerDetail"))
            ?: firstChild(btfState?.get("detail")?.get("headerDetail"))
            ?: return WebDetailInfo()

        return WebDetailInfo(
            title = header["title"].textOrNull(),
            plot = header["synopsis"].textOrNull(),
            poster = webPoster(header),
            year = header["releaseYear"]?.asInt()?.takeIf { it > 0 },
            tags = header["genres"]?.takeIf { it.isArray }
                ?.mapNotNull { it["text"].textOrNull() }
                ?.takeIf { it.isNotEmpty() },
            seasonNumber = header["seasonNumber"]?.asInt()?.takeIf { it > 0 },
            titleType = header["titleType"].textOrNull()?.lowercase().orEmpty(),
        )
    }

    private suspend fun parseWebDetail(
        body: JsonNode,
        dataUrl: String,
        fallbackTitle: String,
        fallbackPoster: String?,
    ): LoadResponse? {
        val atfState = body["atf"]?.get("state")
        val btfState = body["btf"]?.get("state")

        val info = webDetailInfo(body)
        val title = info.title ?: fallbackTitle
        val plot = info.plot
        val poster = info.poster ?: fallbackPoster
        val year = info.year
        val tags = info.tags
        val seasonNumber = info.seasonNumber
        val titleType = info.titleType

        // `self` maps each populated GTI to its sequence number.
        fun episodeState(): JsonNode? = btfState ?: atfState

        fun episodesOf(state: JsonNode?, season: Int): List<Episode> {
            val cardIds = state?.get("episodeList")?.get("cardTitleIds")?.takeIf { it.isArray }
                ?: return emptyList()
            val self = state["self"]
            val out = mutableListOf<Episode>()
            var index = 0
            for (card in cardIds) {
                index++
                val gti = card.asText()?.takeIf { it.isNotBlank() } ?: continue
                val number = self?.get(gti)?.get("sequenceNumber")?.asInt()?.takeIf { it > 0 } ?: index
                val payload = jsonMapper.writeValueAsString(mapOf("url" to gti, "title" to title))
                out.add(newEpisode(payload) {
                    this.name = "Episodio $number"
                    this.season = season
                    this.episode = number
                    this.posterUrl = poster
                })
            }
            return out
        }

        val episodes = mutableListOf<Episode>()
        val currentSeason = seasonNumber ?: 1
        episodes.addAll(episodesOf(episodeState(), currentSeason))

        if (episodes.isNotEmpty()) {
            // Add the remaining seasons; the episode list only covers the
            // season the page defaults to.
            val seasons = atfState?.get("seasons")?.let { firstChild(it) }?.takeIf { it.isArray }
                ?: btfState?.get("seasons")?.let { firstChild(it) }?.takeIf { it.isArray }
            if (seasons != null && seasons.size() > 1) {
                for (season in seasons) {
                    val number = season["sequenceNumber"]?.asInt()?.takeIf { it > 0 } ?: continue
                    if (number == currentSeason) continue
                    if (episodes.size > 1000) break
                    val link = season["seasonLink"].textOrNull()?.substringBefore("?")
                        ?: continue
                    val seasonBody = try {
                        api.getWebPageBody(link)
                    } catch (e: Exception) {
                        Log.w("PrimeVideoLoad", "Season $number failed: ${e.message}")
                        null
                    } ?: continue
                    val seasonState = seasonBody["btf"]?.get("state") ?: seasonBody["atf"]?.get("state")
                    episodes.addAll(episodesOf(seasonState, number))
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, dataUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                }
            }
        }

        if (titleType.contains("episode")) {
            // A single episode page still plays as a movie-like item.
            return newMovieLoadResponse(title, dataUrl, TvType.TvSeries, dataUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Load (detail)
    // ────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        requireLogin()
        val data = try { jsonMapper.readTree(url) } catch (e: Exception) { null } ?: return null

        val titleId = data["url"].textOrNull() ?: return null
        val title = data["title"].textOrNull() ?: "Prime Video"
        val poster = data["poster"].textOrNull()
        val isLive = data["isLive"]?.asBoolean(false) ?: false

        // Live events get a LiveStreamLoadResponse so the UI shows them as live
        // instead of "Movie / Play Movie".
        if (isLive) {
            val info = try {
                api.getWebPageBody("/detail/$titleId")?.let { webDetailInfo(it) }
            } catch (e: Exception) {
                Log.w("PrimeVideoLoad", "Live detail failed: ${e.message}")
                null
            }

            // The app always shows a generic "No Links Found" when playback fails,
            // so surface the ownership/availability problem here instead.
            val note = try {
                api.getPlaybackResources(titleId, true, mapOf(
                    "desiredResources" to "PlaybackUrls",
                    "videoMaterialType" to "LiveStreaming",
                ))
                null
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("NoRights", ignoreCase = true) ->
                        "⚠ Tu cuenta no tiene acceso a este evento en vivo."
                    msg.contains("InvalidRequest", ignoreCase = true) ->
                        "ℹ Este evento aún no tiene señal en vivo (puede estar por empezar o ya terminado)."
                    else -> null
                }
            }

            return newLiveStreamLoadResponse(info?.title ?: title, url, url) {
                this.posterUrl = info?.poster ?: poster
                this.plot = listOfNotNull(note, info?.plot).joinToString("\n\n")
                this.year = info?.year
                this.tags = info?.tags
            }
        }

        // Storefront detail. The mobile detail transform requires a customerId
        // that the mobile API never hands out, so it always fails.
        try {
            val body = api.getWebPageBody("/detail/$titleId")
            if (body != null) {
                parseWebDetail(body, url, title, poster)?.let { return it }
            }
        } catch (e: Exception) {
            Log.w("PrimeVideoLoad", "Web detail failed: ${e.message}")
        }

        return loadMobile(titleId, title, url, poster)
    }

    private suspend fun loadMobile(
        titleId: String,
        title: String,
        url: String,
        poster: String?,
    ): LoadResponse? {
        // Try mobile API detail endpoint
        return try {
            Log.i("PrimeVideoLoad", "loading titleId=$titleId")
            val extraParams = "&itemId=$titleId&pageType=detail&pageId=detail"
            val resource = api.getMobilePage("dv-android/detail/v2/user/v2.5.js", extraParams)
            parseDetail(resource, title, url, poster)
        } catch (e: Exception) {
            Log.w("PrimeVideoLoad", "Detail fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            // Fall back: try ATF/JSTL format
            try {
                val extraParams = "&itemId=$titleId&capabilities="
                val resource = api.getMobilePage("android/atf/v3.jstl", extraParams)
                parseDetail(resource, title, url, poster)
            } catch (e2: Exception) {
                Log.w("PrimeVideoLoad", "ATF detail also failed: ${e2.message}")
                // Final fallback: movie response
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }
        }
    }

    private suspend fun parseDetail(
        resource: JsonNode,
        title: String,
        dataUrl: String,
        poster: String?,
    ): LoadResponse? {
        // Detail pages from mobile API may have:
        // - collections[].collectionItemList[] with episodes
        // - direct items array
        // - contentMetadata for plot/description

        val collections = resource["collections"]?.takeIf { it.isArray && it.size() > 0 }
        val metadata = resource["contentMetadata"] ?: resource["pageMetadata"] ?: resource
        val plot = metadata["description"].textOrNull()
            ?: metadata["synopsis"].textOrNull()
            ?: metadata["plot"].textOrNull()
            ?: ""

        // Collect all episodes/items from all collections
        val episodes = mutableListOf<Episode>()
        if (collections != null) {
            for (c in collections) {
                val items = c["collectionItemList"]
                if (items == null || items.size() == 0) continue
                for (item in items) {
                    val ep = buildEpisode(item, poster)
                    if (ep != null) episodes.add(ep)
                }
            }
        }

        // Also check if resource itself has items
        val directItems = resource["collectionItemList"] ?: resource["items"] ?: resource["entities"]
        if (directItems != null && directItems.isArray && episodes.isEmpty()) {
            for (item in directItems) {
                val ep = buildEpisode(item, poster)
                if (ep != null) episodes.add(ep)
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, dataUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // Movie/single item
        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun buildEpisode(item: JsonNode, defaultPoster: String?): Episode? {
        val model = item["model"]?.takeIf { it.isObject } ?: item
        val effective = model

        val epTitle = effective["title"].textOrNull()
            ?: effective["displayTitle"].textOrNull()
            ?: "Episode"
        
        val titleId = effective["id"].textOrNull()
            ?: effective["linkAction"]?.get("titleId").textOrNull()
            ?: item["id"].textOrNull()
            ?: return null

        val epPayload = jsonMapper.writeValueAsString(mapOf(
            "url" to titleId,
            "title" to epTitle,
        ))

        return newEpisode(epPayload) {
            this.name = epTitle
            this.season = effective["seasonNumber"]?.asInt()
            this.episode = effective["episodeNumber"]?.asInt()
                ?: effective["sequenceNumber"]?.asInt()
            this.posterUrl = extractPosterUrl(effective) ?: defaultPoster
            this.description = effective["synopsis"].textOrNull()
                ?: effective["description"].textOrNull()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // LoadLinks (playback)
    // ────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        requireLogin()

        val json = try { jsonMapper.readTree(data) } catch (e: Exception) { null } ?: return false
        val titleId = json["url"].textOrNull() ?: return false
        val title = json["title"].textOrNull() ?: "Prime Video"

        // For GTI format, we need to pass it as the ASIN parameter
        val asin = api.extractAsin(titleId)
        if (asin.isNullOrBlank() || asin.length < 10) {
            Log.w("PrimeVideoPL", "Invalid titleId: $titleId")
            return false
        }

        // For GTI format (amzn1.dv.gti.xxx), pass the full GTI as asin
        val actualAsin = if (titleId.startsWith("amzn1.dv.gti")) titleId else asin

        // Detect if content is live
        val isLive = json["isLive"]?.asBoolean(false) ?: false

        // 1. Get playback manifest
        // NOTE: "Timeline" is rejected by Amazon (PRS.InvalidRequest) for web
        // devices, so only ask for the resources we actually use. Live events
        // only accept PlaybackUrls.
        //
        // Amazon marks upcoming/finished events as LIVE_EVENT too, but those
        // only answer videoMaterialType=Feature (replay). So: try live first and
        // fall back to Feature when there is no live signal.
        fun asUrl(node: JsonNode?): String? = when {
            node == null || node.isNull -> null
            node.isTextual -> node.asText().takeIf { it.isNotBlank() }
            node.isObject -> asUrl(node["url"])
            else -> null
        }

        fun extractManifestUrl(node: JsonNode?): String? {
            if (node == null || node.isNull) return null
            val infoList = node["avUrlInfoList"]
            if (infoList != null && infoList.isArray && infoList.size() > 0) {
                asUrl(infoList[0])?.let { return it }
            }
            asUrl(node["urls"]?.get("manifest"))?.let { return it }
            asUrl(node["url"])?.let { return it }
            asUrl(node["cdn"])?.let { return it }
            return null
        }

        // Amazon may hand back either a plain string or an object with the URL
        // inside, e.g. urls.manifest = { "url": "...", "drm": "CENC", ... }.
        fun findManifest(data: JsonNode?): String? {
            if (data == null) return null
            val playbackUrls = data["playbackUrls"]
            val urlSets = playbackUrls?.get("urlSets")
            val defaultId = playbackUrls?.get("defaultUrlSetId").textOrNull()

            var found = defaultId?.let { extractManifestUrl(urlSets?.get(it)) }
            if (found == null && urlSets != null && urlSets.isObject) {
                for (field in urlSets.fields()) {
                    found = extractManifestUrl(field.value)
                    if (found != null) break
                }
            }
            if (found == null) {
                found = extractManifestUrl(data["audioVideoUrls"]?.get("avCdnUrlSets")?.firstOrNull())
            }
            return found
        }

        var livePlayback = isLive
        var playbackData: JsonNode? = null
        var manifestUrl: String? = null

        while (true) {
            val failed = try {
                playbackData = api.getPlaybackResources(actualAsin, livePlayback, mapOf(
                    "desiredResources" to if (livePlayback) "PlaybackUrls"
                    else "PlaybackUrls,SubtitleUrls,ForcedNarratives",
                    "videoMaterialType" to if (livePlayback) "LiveStreaming" else "Feature",
                ))
                false
            } catch (e: Exception) {
                Log.w(
                    "PrimeVideoPL",
                    "GetPlaybackResources${if (livePlayback) " (live)" else ""} failed: ${e.message}",
                )
                // Live: tell the user why instead of a bare "no links found".
                if (livePlayback && e.message?.contains("NoRights", ignoreCase = true) == true) {
                    val msg = "Prime Video: este evento no está incluido en tu suscripción."
                    runCatching { CommonActivity.showToast(msg, Toast.LENGTH_LONG) }
                    throw ErrorLoadingException(msg)
                }
                true
            }

            manifestUrl = if (failed) null else findManifest(playbackData)
            if (manifestUrl != null) break

            if (!livePlayback) {
                Log.w(
                    "PrimeVideoPL",
                    "No manifest URL found. playbackUrls=" +
                        (playbackData?.get("playbackUrls")?.toString()?.take(300) ?: "null"),
                )
                // Upcoming event: neither live signal nor replay yet.
                if (isLive) {
                    val msg = "Prime Video: este evento todavía no tiene señal disponible."
                    runCatching { CommonActivity.showToast(msg, Toast.LENGTH_LONG) }
                    throw ErrorLoadingException(msg)
                }
                return false
            }

            // No live signal: retry as VOD/replay.
            Log.i("PrimeVideoPL", "No live manifest, retrying with videoMaterialType=Feature")
            livePlayback = false
        }

        val playback = playbackData ?: return false
        val manifest = manifestUrl ?: return false

        // Parse subtitles (the response uses `subtitleUrls`, older shapes used `subtitles`)
        val subtitles = playback["subtitleUrls"]?.takeIf { it.isArray }
            ?: playback["subtitles"]?.takeIf { it.isArray }
        if (subtitles != null) {
            for (sub in subtitles) {
                val subUrl = sub["url"].textOrNull() ?: continue
                val subLang = sub["languageCode"].textOrNull()
                    ?: sub["language"].textOrNull()
                    ?: sub["lang"].textOrNull()
                    ?: sub["displayName"].textOrNull()
                    ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(subLang, subUrl))
            }
        }

        // 2. Build license URL (same host/device type as the playback request)
        val licenseUrl = buildString {
            append(api.cdpHost)
            append("/cdp/catalog/GetPlaybackResources")
            append("?asin=").append(actualAsin)
            append("&deviceTypeID=").append(api.cdpDeviceTypeId)
            append("&firmware=1")
            append("&deviceID=").append(api.deviceId32)
            append("&marketplaceID=").append(api.marketplaceId)
            append("&format=json&version=2")
            append("&gascEnabled=").append(api.usePrimeVideo.toString().lowercase())
            append("&resourceUsage=ImmediateConsumption&consumptionType=Streaming")
            append("&deviceDrmOverride=CENC")
            append("&deviceStreamingTechnologyOverride=DASH")
            append("&deviceProtocolOverride=Https")
            append("&deviceBitrateAdaptationsOverride=CVBR%2CCBR")
            append("&audioTrackId=all")
            append("&languageFeature=MLFv2")
            // Must match the stream we actually got: a live event that fell
            // back to Feature needs a Feature license.
            append("&videoMaterialType=").append(if (livePlayback) "LiveStreaming" else "Feature")
            append("&desiredResources=Widevine2License")
            append("&supportedDRMKeyScheme=DUAL_KEY")
        }

        // 3. Build auth headers (cookies + optional Bearer/Atza token)
        val auth = api.authHeaders()
        val linkHeaders = PrimeVideoConstants.androidHeaders() + mapOf(
            "Content-Type" to "application/octet-stream",
        ) + auth

        // 4. Emit DRM link
        val link = newDrmExtractorLink(
            "Prime Video",
            "Latino - Prime Video",
            manifest,
            ExtractorLinkType.DASH,
            WIDEVINE_UUID,
        ) {
            this.licenseUrl = licenseUrl
            this.headers = linkHeaders
            this.quality = Qualities.Unknown.value
            this.referer = api.baseUrl
        }
        callback.invoke(link)
        return true
    }

    // ────────────────────────────────────────────────────────────────────
    // DRM interceptor
    // ────────────────────────────────────────────────────────────────────

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val isPost = request.method.equals("POST", ignoreCase = true)
            val urlStr = request.url.toString()
            val isLicenseRequest = isPost && (
                urlStr.contains("Widevine2License", ignoreCase = true) ||
                request.url.encodedPath.contains("GetPlaybackResources")
            )

            if (!isLicenseRequest) {
                val response = chain.proceed(request)
                if (!isPost) applyPreferredAudioTrack(response, urlStr)
                return@Interceptor response
            }

            try {
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val challengeBytes = buffer.readByteArray()

                if (challengeBytes.isEmpty()) {
                    return@Interceptor chain.proceed(request)
                }

                // Amazon expects the challenge as base64url (ISA's {CHA-B64U}).
                // Standard base64 emits '+' and '/', and '+' is decoded as a space
                // in form-urlencoded bodies, which corrupts the challenge and makes
                // GetPlaybackResources answer PRS.InvalidRequest.
                val challengeB64 = Base64.encodeToString(
                    challengeBytes,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
                val formBody = "widevine2Challenge=$challengeB64"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())

                val transformed = request.newBuilder()
                    .method("POST", formBody)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                Log.d("PrimeVideoDRM", "license POST ${challengeBytes.size}B")

                val response = chain.proceed(transformed)

                val peek = try {
                    response.peekBody(8L * 1024 * 1024).string()
                } catch (e: Exception) { "" }

                if (peek.trimStart().startsWith("{")) {
                    val json = try { jsonMapper.readTree(peek) } catch (e: Exception) { null }
                    val licenseB64 = json?.get("widevine2License")?.get("license")?.asText()
                        ?: json?.get("license")?.asText()
                    if (!licenseB64.isNullOrBlank()) {
                        val licenseBytes = try {
                            Base64.decode(licenseB64, Base64.DEFAULT)
                        } catch (e: Exception) { null }
                        if (licenseBytes != null && licenseBytes.isNotEmpty()) {
                            Log.i("PrimeVideoDRM", "license ok (${licenseBytes.size}B)")
                            response.body?.close()
                            return@Interceptor response.newBuilder()
                                .removeHeader("Content-Length")
                                .removeHeader("Content-Type")
                                .header("Content-Type", "application/octet-stream")
                                .header("Content-Length", licenseBytes.size.toString())
                                .body(licenseBytes.toResponseBody(
                                    "application/octet-stream".toMediaType()
                                ))
                                .build()
                        }
                    }
                    Log.w(
                        "PrimeVideoDRM",
                        "license refused ${response.code}: " + peek.take(240).replace("\n", " "),
                    )
                }

                return@Interceptor response
            } catch (e: Exception) {
                Log.w("PrimeVideoDRM", "license transform failed: ${e.message}")
                return@Interceptor chain.proceed(request)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Audio language defaults
    // ────────────────────────────────────────────────────────────────────

    /**
     * Selects the audio track that best matches the language chosen in the app.
     *
     * Amazon marks its main audio track (usually English or German) with the
     * DASH "main" role, which ExoPlayer prefers over the system/app language.
     * Reading the languages from the manifest and setting the player's
     * preferred audio language overrides that default.
     */
    private fun applyPreferredAudioTrack(response: Response, urlStr: String) = runCatching {
        val isDash = urlStr.substringBefore('?').endsWith(".mpd", ignoreCase = true) ||
            response.header("Content-Type")?.contains("dash", ignoreCase = true) == true
        if (!isDash) return@runCatching

        val manifest = response.peekBody(2L * 1024 * 1024).string()
        if (!manifest.contains("<MPD", ignoreCase = true)) return@runCatching

        val available = audioLanguagesFromManifest(manifest)
        if (available.isEmpty()) return@runCatching

        val selected = closestAudioLanguage(available) ?: return@runCatching
        if (selected != preferredAudioTrackLanguage) {
            preferredAudioTrackLanguage = selected
            Log.d(
                "PrimeVideoAudio",
                "audio '$selected' (app=${appLanguageTag()}, available=$available)",
            )
        }
    }.onFailure { e ->
        Log.d("PrimeVideoAudio", "audio preference skipped: ${e.message}")
    }

    /** Audio languages declared by the DASH manifest, in document order. */
    private fun audioLanguagesFromManifest(manifest: String): List<String> {
        val audioMime = Regex("mimeType\\s*=\\s*\"audio/", RegexOption.IGNORE_CASE)
        val audioType = Regex("contentType\\s*=\\s*\"audio\"", RegexOption.IGNORE_CASE)
        val langAttr = Regex("\\blang\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val setStarts = Regex("<AdaptationSet\\b[^>]*>", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .toList()

        val result = LinkedHashSet<String>()
        setStarts.forEachIndexed { index, set ->
            val end = setStarts.getOrNull(index + 1)?.range?.first ?: manifest.length
            val scope = manifest.substring(set.range.first, end)
            if (!audioMime.containsMatchIn(scope) && !audioType.containsMatchIn(scope)) {
                return@forEachIndexed
            }
            langAttr.findAll(scope).forEach { match ->
                match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let(result::add)
            }
        }
        return result.toList()
    }

    /** Language selected in the app (Settings → General → App language). */
    private fun appLanguageTag(): String? = runCatching {
        CloudStreamApp.context
            ?.getDefaultSharedPrefs()
            ?.getString("app_locale", null)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Closest available audio track to the app language, keeping Amazon's tag. */
    private fun closestAudioLanguage(available: List<String>): String? {
        val candidates = appLanguageCandidates()
        if (candidates.isEmpty()) return null

        val normalized = available.map { it to normalizeLanguageTag(it) }

        // Exact match first (app "es" + track "es", app "pt-BR" + track "pt-br").
        for (candidate in candidates) {
            normalized.firstOrNull { it.second == candidate }?.let { return it.first }
        }

        // Then the same language in any region (app "es" + track "es-419").
        for (candidate in candidates) {
            val language = candidate.substringBefore('-')
            normalized.firstOrNull { it.second.substringBefore('-') == language }
                ?.let { return it.first }
        }
        return null
    }

    private fun normalizeLanguageTag(tag: String): String =
        tag.trim().lowercase(Locale.ROOT).replace('_', '-')

    /**
     * Ordered language tags to try, including the regional variants Amazon
     * commonly uses for its audio tracks.
     */
    private fun appLanguageCandidates(): List<String> {
        val locale = appLanguageTag()?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isEmpty()) return emptyList()

        val country = locale.country.uppercase(Locale.ROOT)
        val candidates = LinkedHashSet<String>()
        if (country.isNotEmpty()) candidates += "$language-$country".lowercase(Locale.ROOT)

        when (language) {
            "es" -> {
                if (country != "ES") candidates += "es-419" // Latin American Spanish
                candidates += listOf("es-es", "es")
            }
            "pt" -> candidates += listOf("pt-br", "pt-pt", "pt")
            "en" -> candidates += listOf("en-us", "en-gb", "en")
            "fr" -> candidates += listOf("fr-fr", "fr-ca", "fr")
            "de" -> candidates += listOf("de-de", "de")
            "it" -> candidates += listOf("it-it", "it")
            "ja" -> candidates += listOf("ja-jp", "ja")
            "ko" -> candidates += listOf("ko-kr", "ko")
            "zh" -> candidates += listOf("zh-cn", "zh-tw", "zh")
            else -> candidates += language
        }
        return candidates.toList()
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private fun JsonNode?.nodeList(): List<JsonNode> {
        if (this == null || !isArray) return emptyList()
        val out = mutableListOf<JsonNode>()
        forEach { out.add(it) }
        return out
    }

    private fun JsonNode?.textOrNull(): String? =
        this?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonNode?.labelText(): String? {
        if (this == null || isNull) return null
        if (isTextual) return asText().takeIf { it.isNotBlank() && it != "null" }
        return this["text"].textOrNull()
            ?: this["name"].textOrNull()
            ?: this["title"].textOrNull()
            ?: asText().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun String.unescapeHtml(): String? {
        if (isNullOrBlank()) return null
        return this
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
    }
}