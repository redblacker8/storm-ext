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
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    /**
     * A DASH manifest served by [getVideoInterceptor]. Live entries are rebuilt from
     * the HLS playlist on each refresh so ExoPlayer always sees the current live edge.
     */
    private data class DashSource(
        val hlsUrl: String,
        val preferredAudio: String?,
        val live: Boolean,
        val xml: String,
        val builtAt: Long,
        val availabilityStartTime: String? = null,
        val presentationTimeOffset: Long? = null,
        /** Cached master metadata so a live refresh skips the master playlist. */
        val plan: DisneyDash.Plan? = null,
        val subtitles: List<DisneyDash.SubtitleRendition> = emptyList(),
    )

    private val dashManifests = java.util.concurrent.ConcurrentHashMap<String, DashSource>()

    /** Subtitle proxy URL -> HLS subtitle playlist, served by [getVideoInterceptor]. */
    private val subtitleSources = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val subtitleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        private const val LOGIN_REQUIRED = "Disney+ requiere una cuenta con suscripción activa. " +
            "Inicia sesión en: Ajustes del proveedor (icono de engranaje) -> Disney+ Login."
        private const val ROW_LIMIT = 20

        /** ESPN shelves are larger than normal rows; load enough to include live events. */
        private const val ESPN_LIMIT = 25
        private const val ENHANCED_LIMIT = 25

        /** Minimum delay between live manifest rebuilds, in milliseconds. */
        private const val LIVE_REFRESH_MS = 3_000L
    }

    /** One paginable row of a Disney+ page, backed by an Explore set. */
    private data class ShelfRef(
        val name: String,
        val setId: String?,
        val cursor: String?,
        val hasMore: Boolean,
    )

    /** Row refs and cursors for the last page-1 load of each tab. */
    private val shelfRefs = java.util.concurrent.ConcurrentHashMap<String, List<ShelfRef>>()

    override val mainPage: List<MainPageData> = mainPageOf(
        "ESPN" to "espn",
        "Inicio" to "home",
        "Películas" to "movies",
        "Series" to "series",
        "Originales" to "originals",
    )

    private fun requireLogin() {
        if (!api.isLoggedIn) {
            throw ErrorLoadingException(LOGIN_REQUIRED)
        }
    }

    private fun getActions(item: JsonNode, depth: Int = 0): Map<String, JsonNode> {
        val actions = linkedMapOf<String, JsonNode>()
        val arr = item["actions"] ?: return actions
        for (row in arr) {
            val type = row["type"]?.asText()?.lowercase()
            when {
                type in setOf("playback", "play", "watch", "tune", "resume") ->
                    if (actions["playback"] == null) actions["playback"] = row
                type in setOf("browse", "page") ->
                    if (actions["browse"] == null) actions["browse"] = row
                type == "modal" || type == "upsell" || row["modalType"] != null -> {
                    if (actions["modal"] == null) actions["modal"] = row
                    // Modal actions wrap their own action list, which is where the
                    // real playback action lives (same as Disney's clients).
                    if (depth < 3) {
                        val nested = getActions(row, depth + 1)
                        if (actions["playback"] == null) nested["playback"]?.let { actions["playback"] = it }
                        if (actions["browse"] == null) nested["browse"]?.let { actions["browse"] = it }
                    }
                    if (actions["playback"] == null && row["resourceId"] != null) {
                        actions["playback"] = row
                    }
                }
                // Live event actions often omit `type` and only carry the playback
                // fields directly.
                row["resourceId"] != null ->
                    if (actions["playback"] == null) actions["playback"] = row
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

    /** Some guide responses key channels/views by id instead of returning an array. */
    private fun JsonNode?.objectValues(): List<JsonNode> {
        if (this == null || !isObject) return emptyList()
        val out = mutableListOf<JsonNode>()
        forEach { out.add(it) }
        return out
    }

    private fun JsonNode?.textOrNull(): String? =
        this?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * Guide payloads wrap labels as objects (`{"text": "ESPN"}`) while other
     * endpoints use plain strings. Read whichever form is present.
     */
    private fun JsonNode?.labelText(): String? {
        if (this == null || isNull) return null
        if (isTextual) return asText().takeIf { it.isNotBlank() && it != "null" }
        return this["text"].textOrNull()
            ?: this["name"].textOrNull()
            ?: this["title"].textOrNull()
            ?: asText().takeIf { it.isNotBlank() && it != "null" }
    }

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
            ?: artworkUrl(visuals, "standard", "tile", "1.78")
            ?: artworkUrl(visuals, "standard", "thumbnail", "1.78")
            ?: artworkUrl(visuals, "hero", "background", "0.67")

    private fun backgroundUrl(visuals: JsonNode?): String? =
        artworkUrl(visuals, "hero", "background", "1.78")
            ?: artworkUrl(visuals, "details", "background", "1.78")
            ?: artworkUrl(visuals, "standard", "background", "1.78")
            ?: artworkUrl(visuals, "standard", "background", "1.33")

    private fun isLiveEventState(state: JsonNode?): Boolean {
        if (state == null || state.isNull) return false
        if (state.isTextual) {
            val text = state.asText().lowercase()
            return text.contains("live") || text.contains("progress")
        }
        val text = state["state"].textOrNull()?.lowercase()
            ?: state["status"].textOrNull()?.lowercase()
            ?: state["eventState"].textOrNull()?.lowercase()
        if (text == null) {
            // Some payloads only carry the live timeline.
            return state["timeline"] != null
        }
        return text.contains("live") || text.contains("progress") || text == "on_air"
    }

    private fun mediaType(visuals: JsonNode): TvType {
        val eventState = visuals["badging"]?.get("airingEventState")
            ?: visuals["airingEventState"]
        if (isLiveEventState(eventState)) return TvType.Live
        val contentType = visuals["contentType"]?.asText()?.lowercase()
        if (contentType == "live" || contentType == "linear") return TvType.Live
        return when (visuals["type"]?.asText()) {
            "Program", "TvShow", "Series" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun toSearchResponse(item: JsonNode): SearchResponse? {
        val visuals = item["visuals"] ?: return null
        val title = visuals["title"]?.asText() ?: visuals["name"]?.asText() ?: return null
        val eventState = visuals["badging"]?.get("airingEventState")
            ?: visuals["airingEventState"]
            ?: item["airingEventState"]
        // Skip scheduled upcoming broadcast tiles that have not started and have no playback.
        if (eventState != null && !isLiveEventState(eventState) && !hasPlaybackAction(item)) {
            return null
        }

        val actions = getActions(item)
        val play = actions["playback"] ?: return null
        val browse = actions["browse"]
        val deeplinkId = play["deeplinkId"].textOrNull()
        val contentId = play["legacyPartnerFeed"]?.get("dmcContentId").textOrNull()
            ?: play["partnerFeed"]?.get("dmcContentId").textOrNull()
        val resourceId = play["resourceId"].textOrNull()
        val pageId = play["pageId"].textOrNull()
            ?: browse?.get("pageId").textOrNull()
            ?: browse?.get("deeplinkId").textOrNull()?.takeIf {
                it.startsWith("entity-") || it.startsWith("page-")
            }
        if (deeplinkId == null && contentId == null && resourceId == null && pageId == null) return null

        val isLive = isLivePlayable(item)
        val payload = mutableMapOf<String, String>()
        (deeplinkId ?: contentId)?.let { payload["deeplinkId"] = it }
        resourceId?.let { payload["resourceId"] = it }
        (play["contentType"].textOrNull() ?: item["contentType"].textOrNull())
            ?.let { payload["contentType"] = it }
        pageId?.let { payload["pageId"] = it }
        // Live events and channel tiles may use a channelId as deeplinkId with no entity
        // page. Always include title/poster on live items so load() has full fallback metadata.
        if (isLive || (deeplinkId == null && contentId == null)) {
            payload["title"] = title
            posterUrl(visuals)?.let { payload["poster"] = it }
        }

        val url = mapper.writeValueAsString(payload)
        val type = if (isLive) TvType.Live else mediaType(visuals)
        return newMovieSearchResponse(title, url, type) {
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
            loadRows(refId, isEspn, page)
        } catch (e: Exception) {
            if (refId == "home") throw e
            emptyList()
        }
        // ESPN is a live hub, not a paged catalogue.
        val hasNext = !isEspn && rows.any { it.list.isNotEmpty() } &&
            shelfRefs[refId]?.any { it.hasMore } == true
        return newHomePageResponse(rows, hasNext)
    }

    private fun browsePageId(deeplink: JsonNode): String? =
        deeplink["actions"]?.firstOrNull {
            it["type"]?.asText() == "browse" && it["pageId"] != null
        }?.get("pageId")?.asText()
            ?: deeplink["actions"]?.firstOrNull { it["pageId"] != null }?.get("pageId")?.asText()

    private suspend fun resolvePageId(refId: String): String =
        browsePageId(api.deeplink(refId))
            ?: throw ErrorLoadingException("Disney+ sin pageId para '$refId'")

    private suspend fun loadRows(
        refId: String,
        isEspn: Boolean,
        page: Int,
    ): List<HomePageList> {
        if (isEspn) {
            if (page > 1) return emptyList()
            Log.i("DisneyESPN", "loadRows(espn) start, page=$page")
            // Combine both sources concurrently: the hub page carries real event cards
            // while the live guide knows about linear channels the page does not list
            // as events. Hub-page entries win a title clash: they open the richer page.
            val (pageResult, guideResult) = coroutineScope {
                val pageDeferred = async { runCatching { loadEspnPageEvents() } }
                val guideDeferred = async { runCatching { loadEspnGuideEvents() } }
                pageDeferred.await() to guideDeferred.await()
            }
            pageResult.exceptionOrNull()?.let {
                Log.e("DisneyESPN", "loadEspnPageEvents failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            val (pageLive, pagePlayable) = pageResult.getOrDefault(emptyList<SearchResponse>() to emptyList())
            Log.i("DisneyESPN", "page events: live=${pageLive.size} playable=${pagePlayable.size}")

            guideResult.exceptionOrNull()?.let {
                Log.e("DisneyESPN", "loadEspnGuideEvents failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            val guideEvents = guideResult.getOrDefault(emptyList())
            Log.i("DisneyESPN", "guide events: ${guideEvents.size}")

            val events = linkedMapOf<String, SearchResponse>()
            pageLive.forEach { events.putIfAbsent(espnDedupeKey(it), it) }
            guideEvents.forEach { events.putIfAbsent(espnDedupeKey(it), it) }
            Log.i("DisneyESPN", "merged events: ${events.size}")
            if (events.isNotEmpty()) {
                return listOf(HomePageList("ESPN En Vivo", events.values.toList(), false))
            }
            // Nothing live anywhere: fall back to the full page catalogue so the tab
            // is not empty (kept separate from the large playable set otherwise).
            val fallback = linkedMapOf<String, SearchResponse>()
            pagePlayable.forEach { fallback.putIfAbsent(espnDedupeKey(it), it) }
            Log.i("DisneyESPN", "fallback playable events: ${fallback.size}")
            return if (fallback.isEmpty()) {
                emptyList()
            } else {
                listOf(HomePageList("ESPN", fallback.values.toList(), false))
            }
        }

        val pageId = resolvePageId(refId)
        if (page <= 1) {
            val pageJson = api.page(pageId, limit = ROW_LIMIT, enhancedLimit = ENHANCED_LIMIT)
            return loadFirstPage(refId, pageJson)
        }

        return loadNextPage(refId, pageId, page)
    }

    private fun loadFirstPage(refId: String, pageJson: JsonNode): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()
        val refs = mutableListOf<ShelfRef>()
        for (container in pageJson["containers"].nodeList()) {
            if (!isShelf(container)) continue
            val name = container["visuals"]?.get("name")?.asText() ?: refId
            val rawItems = container["items"].nodeList()
            // Keep the first batch at ROW_LIMIT so the next page can use offset
            // consistently even when the server ignores the limit parameter.
            val items = if (rawItems.size > ROW_LIMIT) rawItems.subList(0, ROW_LIMIT) else rawItems
            val parsed = items.mapNotNull { toSearchResponse(it) }
            if (parsed.isEmpty()) continue

            val setId = container["id"].textOrNull()
            // The Android Explore API reports row pagination as `pagination.hasMore`;
            // the web client uses `cursorPagination.next`. Support both, and fall
            // back to "row is full" when neither is present.
            val cursor = container["cursorPagination"]?.get("next").textOrNull()
            val hasMore = setId != null && when {
                cursor != null -> true
                container["pagination"] != null ->
                    container["pagination"]["hasMore"]?.asBoolean(false) == true
                else -> rawItems.size >= ROW_LIMIT
            }
            refs.add(ShelfRef(name, setId, cursor, hasMore))
            rows.add(HomePageList(name, parsed, false))
        }
        shelfRefs[refId] = refs
        return rows
    }

    private suspend fun loadNextPage(
        refId: String,
        pageId: String,
        page: Int,
    ): List<HomePageList> {
        // If the provider was recreated between page 1 and page 2, rebuild the row
        // list from the page metadata. Subsequent requests then use offsets.
        val refs = shelfRefs[refId] ?: run {
            val pageJson = api.page(pageId, limit = 1, enhancedLimit = ENHANCED_LIMIT)
            pageJson["containers"].nodeList()
                .filter { isShelf(it) }
                .mapNotNull { container ->
                    val setId = container["id"].textOrNull() ?: return@mapNotNull null
                    val name = container["visuals"]?.get("name")?.asText() ?: refId
                    ShelfRef(name, setId, null, true)
                }
        }

        if (refs.isEmpty()) return emptyList()

        val rows = mutableListOf<HomePageList>()
        val updated = mutableListOf<ShelfRef>()
        for (ref in refs) {
            if (!ref.hasMore) {
                rows.add(HomePageList(ref.name, emptyList(), false))
                updated.add(ref)
                continue
            }

            val setId = ref.setId
            if (setId == null) {
                updated.add(ref.copy(hasMore = false, cursor = null))
                rows.add(HomePageList(ref.name, emptyList(), false))
                continue
            }

            // Prefer the cursor returned by the previous batch. If the cursor call
            // fails (older endpoints), fall back to offset pagination.
            var setJson = ref.cursor?.takeIf { it.isNotBlank() }?.let { cursor ->
                runCatching {
                    api.getSet(setId, limit = ROW_LIMIT, page = page, after = cursor)
                }.getOrNull()
            }
            if (setJson == null) {
                setJson = runCatching {
                    api.getSet(setId, limit = ROW_LIMIT, page = page)
                }.getOrNull()
            }
            if (setJson == null) {
                updated.add(ref.copy(hasMore = false, cursor = null))
                rows.add(HomePageList(ref.name, emptyList(), false))
                continue
            }

            val rawItems = setJson["items"].nodeList()
            val items = if (rawItems.size > ROW_LIMIT) rawItems.subList(0, ROW_LIMIT) else rawItems
            val cursor = setJson["cursorPagination"]?.get("next").textOrNull()
            val setHasMore = when {
                cursor != null -> true
                setJson["pagination"] != null ->
                    setJson["pagination"]["hasMore"]?.asBoolean(false) == true
                else -> rawItems.size >= ROW_LIMIT
            }
            updated.add(ref.copy(cursor = cursor, hasMore = setHasMore))

            val parsed = items.mapNotNull { toSearchResponse(it) }
            // Keep empty rows in the response so CloudStream updates hasNext for
            // rows that are already exhausted as well.
            rows.add(HomePageList(ref.name, parsed, false))
        }
        shelfRefs[refId] = updated
        return rows
    }

    /** Dedupe key so a guide program and its hub-page card collapse into one entry. */
    private fun espnDedupeKey(response: SearchResponse): String =
        response.name.trim().lowercase(Locale.US)

    /**
     * Event cards from the ESPN hub page, split into live events and the full
     * playable set (used only when nothing is live anywhere).
     */
    private suspend fun loadEspnPageEvents(): Pair<List<SearchResponse>, List<SearchResponse>> {
        val triedPageIds = mutableSetOf<String>()
        val candidates = listOf(
            "espn",
            "espn-hub",
            "brand-espn",
            "espn-plus",
            "sports",
            // Public ESPN browse page id (es-419 / es-mx). Last resort when the account's
            // API does not expose an `espn` deeplink.
            "page-15961e0d-00e3-4ff5-90b1-eb71a0f9457c",
        )
        for (candidate in candidates) {
            val pageId = if (candidate.startsWith("page-")) {
                candidate
            } else {
                runCatching { browsePageId(api.deeplink(candidate)) }.getOrNull()
            }?.takeIf { it.isNotBlank() && triedPageIds.add(it) } ?: continue

            Log.i("DisneyESPN", "trying pageId=$pageId (from $candidate)")
            val pageResult = runCatching {
                api.page(pageId, limit = ESPN_LIMIT, enhancedLimit = ENHANCED_LIMIT)
            }
            val pageJson = pageResult.getOrNull()
            if (pageJson == null) {
                Log.w("DisneyESPN", "page $pageId failed: ${pageResult.exceptionOrNull()?.message}")
                continue
            }
            val live = linkedMapOf<String, SearchResponse>()
            val playable = linkedMapOf<String, SearchResponse>()
            runCatching { collectEspnEvents(pageJson, live, playable, depth = 0) }
                .exceptionOrNull()?.let {
                    Log.e("DisneyESPN", "collectEspnEvents threw: ${it.javaClass.simpleName}: ${it.message}")
                }
            Log.i("DisneyESPN", "collect done: live=${live.size} playable=${playable.size}")
            if (live.isNotEmpty() || playable.isNotEmpty()) {
                return live.values.toList() to playable.values.toList()
            }
        }
        Log.w("DisneyESPN", "no page events found for any pageId")
        return emptyList<SearchResponse>() to emptyList()
    }

    /**
     * ESPN marketing pages mix event cards with navigation tiles. Event cards can
     * live directly in `items`, under nested `children`, or inside the set behind a
     * menu row. Walk one level deep so the live events are actually reached.
     */
    private suspend fun collectEspnEvents(
        node: JsonNode,
        live: MutableMap<String, SearchResponse>,
        playable: MutableMap<String, SearchResponse>,
        depth: Int,
    ) {
        // A leaf event card (not a row/container, which also has actions/visuals).
        val isContainer = node["type"]?.asText() == "set" ||
            node["items"] != null || node["containers"] != null ||
            node["children"] != null
        if (!isContainer && node["actions"] != null && node["visuals"] != null) {
            if (isEspnEvent(node)) {
                val parsed = toSearchResponse(node)
                if (parsed != null) {
                    playable.putIfAbsent(parsed.url, parsed)
                    if (isLivePlayable(node)) live.putIfAbsent(parsed.url, parsed)
                }
            }
            return
        }

        val containers = node["containers"].nodeList()
        if (containers.isEmpty()) {
            for (item in node["items"].nodeList()) {
                collectEspnEvents(item, live, playable, depth)
            }
            for (child in node["children"].nodeList()) {
                collectEspnEvents(child, live, playable, depth)
            }
            return
        }

        for (container in containers) {
            val style = container["style"]?.get("name")?.asText() ?: ""
            if (style.contains("logo_round", true) || style.contains("brand", true)) continue

            val setId = container["id"].textOrNull()
            var items = container["items"].nodeList()
            var fetchedSet = false

            // Only paginate beyond the initial ESPN_LIMIT batch if the batch is full of
            // live events (live items always appear at the start of ESPN shelves).
            val hasMore = container["pagination"]?.get("hasMore")?.asBoolean(false) == true ||
                container["cursorPagination"]?.get("next").textOrNull() != null
            if (setId != null && hasMore && items.isNotEmpty() && isLivePlayable(items.last())) {
                val collected = items.toMutableList()
                var cursor = container["cursorPagination"]?.get("next").textOrNull()
                var pageNo = 2
                while (pageNo <= 5) {
                    val setJson = runCatching {
                        api.getSet(setId, limit = ESPN_LIMIT, page = pageNo, after = cursor)
                    }.getOrNull() ?: break
                    val newItems = setJson["items"].nodeList()
                    if (newItems.isEmpty()) break
                    collected.addAll(newItems)
                    fetchedSet = true
                    val next = setJson["cursorPagination"]?.get("next").textOrNull()
                    val more = (next != null ||
                        setJson["pagination"]?.get("hasMore")?.asBoolean(false) == true) &&
                        isLivePlayable(newItems.last())
                    if (!more) break
                    cursor = next
                    pageNo++
                }
                items = collected
            }

            for (item in items) {
                if (!isEspnEvent(item)) continue
                val parsed = toSearchResponse(item) ?: continue
                playable.putIfAbsent(parsed.url, parsed)
                if (isLivePlayable(item)) {
                    live.putIfAbsent(parsed.url, parsed)
                }
            }

            // Marketing components often nest event cards under `children`.
            for (child in container["children"].nodeList()) {
                collectEspnEvents(child, live, playable, depth)
            }

            // Unhydrated container with no inline items: fetch its first page once.
            if (items.isEmpty() && setId != null && depth < 1 && !fetchedSet) {
                val setJson = runCatching {
                    api.getSet(setId, limit = ESPN_LIMIT, page = 1)
                }.getOrNull()
                if (setJson != null) {
                    collectEspnEvents(setJson, live, playable, depth + 1)
                }
            }
        }

        // Some marketing pages keep components at the page level in `children`.
        for (child in node["children"].nodeList()) {
            collectEspnEvents(child, live, playable, depth)
        }
    }

    /**
     * Programs currently airing on the ESPN linear channels, as returned by the live
     * guide. Empty when the account/region exposes no guide.
     */
    private suspend fun loadEspnGuideEvents(): List<SearchResponse> {
        val views = runCatching { api.guideViews() }.getOrNull()
        if (views == null) {
            Log.w("DisneyESPN", "guideViews failed")
            return emptyList()
        }
        val channelIds = espnChannelIds(views)
        Log.i("DisneyESPN", "guide channelIds=${channelIds.size} $channelIds")
        if (channelIds.isEmpty()) return emptyList()

        val (start, end) = guideTimeWindow()
        val channels = runCatching {
            api.guideListings(channelIds, start, end)
        }.getOrNull() ?: return emptyList()

        val now = System.currentTimeMillis()
        val live = linkedMapOf<String, SearchResponse>()
        for (channelId in channelIds) {
            for (program in channels[channelId].nodeList()) {
                val startTime = guideTime(program["startTime"].textOrNull()) ?: continue
                val endTime = guideTime(program["endTime"].textOrNull())
                    ?: (startTime + 3 * 60 * 60 * 1000L)
                if (startTime > now || endTime <= now) continue
                val parsed = toGuideSearchResponse(program, channelId) ?: continue
                live.putIfAbsent(parsed.url, parsed)
            }
        }
        return live.values.toList()
    }

    /** Finds ESPN channels in the guide views (es-MX uses "ESPN" / "ESPN 2"). */
    private fun espnChannelIds(views: JsonNode): List<String> {
        val espn = linkedSetOf<String>()
        val sports = linkedSetOf<String>()
        for (view in views.nodeList().ifEmpty { views.objectValues() }) {
            val viewName = view["name"].labelText()
                ?: view["visuals"]?.get("name").labelText()
                ?: view["id"].labelText()
                ?: ""
            val viewIsSports = viewName.contains("espn", true) ||
                viewName.contains("deporte", true) ||
                viewName.contains("sport", true) ||
                viewName.contains("live", true) ||
                viewName.contains("vivo", true)

            val channels = view["channels"].nodeList()
                .ifEmpty { view["channels"].objectValues() }
                .ifEmpty { view["visuals"]?.get("channels").nodeList() }
                .ifEmpty { view["visuals"]?.get("channels").objectValues() }
            for (channel in channels) {
                val id = channel["id"].textOrNull() ?: continue
                val name = channel["name"].labelText()
                    ?: channel["visuals"]?.get("name").labelText()
                    ?: ""
                when {
                    name.contains("espn", true) || id.contains("espn", true) -> espn.add(id)
                    viewIsSports -> sports.add(id)
                }
            }
        }
        // Prefer actual ESPN channels; fall back to the wider sports view only
        // when the guide does not label the ESPN channels directly.
        return (if (espn.isNotEmpty()) espn else sports).toList()
    }

    private fun guideTimeWindow(hours: Int = 6): Pair<String, String> {
        val start = Date()
        val end = Date(start.time + hours * 60 * 60 * 1000L)
        return guideTimeFormat(start) to guideTimeFormat(end)
    }

    private fun guideTimeFormat(date: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(date)
    }

    private fun guideTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun toGuideSearchResponse(program: JsonNode, channelId: String): SearchResponse? {
        val visuals = program["visuals"] ?: return null
        val title = visuals["title"].labelText()
            ?: visuals["name"].labelText()
            ?: program["title"].labelText()
            ?: return null
        // The guide program id is a composite analytics id, not a playback id.
        // Linear playback is resolved from the channel itself.
        val payload = mutableMapOf<String, String>(
            "deeplinkId" to channelId,
            "resourceId" to channelId,
            "contentType" to "live",
            "title" to title,
        )
        posterUrl(visuals)?.let { payload["poster"] = it }
        val url = mapper.writeValueAsString(payload)
        return newMovieSearchResponse(title, url, TvType.Live) {
            this.posterUrl = posterUrl(visuals)
        }
    }

    /**
     * ESPN event tiles are often browse-only cards; their playback action lives on
     * the entity page. Treat airing event state and sports metadata as event markers
     * so those tiles are not discarded as navigation.
     */
    private fun isEspnEvent(item: JsonNode): Boolean {
        if (hasPlaybackAction(item)) return true
        val visuals = item["visuals"] ?: return false
        val eventState = visuals["badging"]?.get("airingEventState")
            ?: visuals["airingEventState"]
        if (eventState != null) return true
        val metas = visuals["metastringParts"]
        if (metas != null && metas["sportsLeague"] != null) return true
        val type = visuals["type"]?.asText()?.lowercase() ?: ""
        return type.contains("event") || type.contains("sport")
    }

    private fun isShelf(container: JsonNode, includeHero: Boolean = false): Boolean {
        val type = container["type"]?.asText()
        if (type == "details" || type == "episodes") return false
        val style = container["style"]?.get("name")?.asText() ?: ""
        if (style.contains("brand", true) ||
            style.contains("continue_watching", true) ||
            style.contains("logo_round", true)
        ) return false
        if (!includeHero && style.contains("hero", true)) return false
        return container["items"] != null
    }

    /** True when the item carries a real playback action, including nested modals. */
    private fun hasPlaybackAction(item: JsonNode, depth: Int = 0): Boolean {
        val actions = item["actions"] ?: return false
        for (action in actions) {
            val type = action["type"]?.asText()?.lowercase()
            when {
                type in setOf("playback", "play", "watch", "tune", "resume") -> return true
                type == "modal" || type == "upsell" || action["modalType"] != null -> {
                    if (depth < 3 && hasPlaybackAction(action, depth + 1)) return true
                }
                action["resourceId"] != null -> return true
            }
        }
        return false
    }

    private fun isLivePlayable(item: JsonNode): Boolean {
        val playback = getActions(item)["playback"]
        val contentType = sequenceOf(
            playback?.get("contentType")?.asText(),
            item["contentType"]?.asText(),
            item["visuals"]?.get("contentType")?.asText(),
        ).filterNotNull().map { it.lowercase() }.firstOrNull()
        if (contentType == "live" || contentType == "linear") return true

        val visuals = item["visuals"]
        val eventState = visuals?.get("badging")?.get("airingEventState")
            ?: visuals?.get("airingEventState")
            ?: item["airingEventState"]
        if (isLiveEventState(eventState)) return true
        if (visuals?.get("live")?.asBoolean(false) == true) return true
        return false
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
        val trimmed = url.trim()
        // Accept Disney+ web URLs directly (e.g. a /play/<uuid> live event link).
        val dataUrl = if (trimmed.startsWith("http")) {
            val payload = mutableMapOf<String, String>()
            Regex("/play/([0-9a-fA-F-]{36})").find(trimmed)?.groupValues?.get(1)?.let {
                payload["deeplinkId"] = it
                payload["resourceId"] = it
                payload["contentType"] = "live"
                payload["title"] = "Disney+"
            }
            if (payload.isEmpty()) {
                Regex("/browse/entity-([0-9a-fA-F-]{36})").find(trimmed)?.groupValues?.get(1)
                    ?.let {
                        payload["deeplinkId"] = it
                        payload["title"] = "Disney+"
                    }
            }
            if (payload.isEmpty()) {
                Regex("/browse/(page-[0-9a-fA-F-]{36})").find(trimmed)?.groupValues?.get(1)
                    ?.let {
                        payload["pageId"] = it
                        payload["title"] = "Disney+"
                    }
            }
            if (payload.isEmpty()) return null
            mapper.writeValueAsString(payload)
        } else {
            url
        }

        val data = mapper.readTree(dataUrl) ?: return null
        val deeplinkId = data["deeplinkId"].textOrNull()
        val storedPageId = data["pageId"].textOrNull()
        if (deeplinkId == null && storedPageId == null) {
            // Live event exposed only as a playback resource (no entity page).
            data["resourceId"].textOrNull() ?: return null
            val title = data["title"].textOrNull() ?: return null
            return newMovieLoadResponse(title, dataUrl, TvType.Live, dataUrl) {
                this.posterUrl = data["poster"].textOrNull()
            }
        }
        val pageId = storedPageId ?: deeplinkId?.let { pageIdFor(it) } ?: return null
        val pageJson = runCatching { api.page(pageId, limit = 1, enhancedLimit = 15) }.getOrNull()
        val parsed = pageJson?.let { runCatching { parseDetails(it, dataUrl) }.getOrNull() }
        if (parsed != null) return parsed

        // Live guide programs may have no regular entity page. Keep the stored
        // metadata; loadLinks resolves playback through the deeplink instead.
        val title = data["title"].textOrNull() ?: "Disney+"
        return newMovieLoadResponse(title, dataUrl, TvType.Live, dataUrl) {
            this.posterUrl = data["poster"].textOrNull()
        }
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
            val pageAction = getActions(pageJson)["playback"]
            val contentType = pageAction?.get("contentType")?.asText()?.lowercase()
            val movieType = if (contentType == "live" || contentType == "linear") {
                TvType.Live
            } else {
                TvType.Movie
            }
            val movieData = withPlaybackInfo(url, pageAction)
            newMovieLoadResponse(title, url, movieType, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
            }
        }
    }

    /**
     * Linear/live playback resources are base64-encoded JSON with a `channelId`.
     * The media endpoint expects the playback id resolved from that channel.
     */
    private fun channelIdFromResourceId(resourceId: String): String? {
        val decoded = runCatching {
            String(Base64.decode(resourceId, Base64.DEFAULT))
        }.getOrNull() ?: runCatching {
            String(Base64.decode(resourceId, Base64.URL_SAFE or Base64.NO_WRAP))
        }.getOrNull() ?: return null
        return runCatching { mapper.readTree(decoded)["channelId"].textOrNull() }.getOrNull()
    }

    /**
     * Linear playback actions expose a base64 resource containing a `channelId`.
     * The media endpoint needs the playback id resolved from that channel.
     */
    private suspend fun resolveLinearPlaybackId(resourceId: String): String {
        // The stored value can be either a base64 playback resource (with a
        // channelId inside) or a raw channel id from the guide/direct play URL.
        val channelId = channelIdFromResourceId(resourceId) ?: resourceId
        val deeplink = runCatching {
            api.deeplink(channelId, refType = "channelId", action = "playback")
        }.getOrNull() ?: return resourceId
        return deeplink["actions"]?.firstOrNull()?.get("resourceId").textOrNull() ?: resourceId
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
        var resourceId = storedResourceId ?: run {
            action = resolvePlaybackAction(json["deeplinkId"].textOrNull())
            action?.get("resourceId").textOrNull()
        } ?: return false
        val contentType = json["contentType"].textOrNull() ?: action?.get("contentType").textOrNull()
        val isLinear = contentType == "linear" || contentType == "live"

        // A linear event's stored resource is a base64 JSON with a channelId.
        // Resolve the real playback id from that channel before asking for media.
        if (isLinear) resourceId = resolveLinearPlaybackId(resourceId)

        var playback = runCatching { api.playback(resourceId) }.getOrNull()
        var source = playback?.get("stream")?.get("sources")?.firstOrNull()
        Log.i(
            "DisneyLive",
            "loadLinks contentType=$contentType isLinear=$isLinear " +
                "streamingType=${playback?.get("stream")?.get("streamingType").textOrNull()} " +
                "source=${if (source == null) "null" else "ok"}",
        )

        // Guide/live payloads may store a deeplink id as the resource. If direct
        // playback fails, resolve the real playback action from the deeplink.
        if (source == null) {
            val altDeeplink = json["deeplinkId"].textOrNull()
            if (altDeeplink != null) {
                val resolved = resolvePlaybackAction(altDeeplink)
                    ?.get("resourceId").textOrNull()
                if (resolved != null && resolved != resourceId) {
                    resourceId = resolved
                    if (isLinear) resourceId = resolveLinearPlaybackId(resourceId)
                    playback = runCatching { api.playback(resourceId) }.getOrNull()
                    source = playback?.get("stream")?.get("sources")?.firstOrNull()
                }
            }
        }
        if (source == null) return false
        // Live feeds can report either field; try the expected one first.
        val streamUrl = if (isLinear) {
            source["slide"]?.get("url")?.asText() ?: source["complete"]?.get("url")?.asText()
        } else {
            source["complete"]?.get("url")?.asText() ?: source["slide"]?.get("url")?.asText()
        }
        if (streamUrl == null) return false

        // Disney's playback payload declares which DRM endpoint the asset type
        // needs via stream.attributes.security.drmEndpointKey:
        //   "event"  -> widevineLicense        (/widevine/v1/obtain-license)
        //   "linear" -> widevineLinearLicense  (/widevine/v1/channel/obtain-license)
        //   "vod"    -> widevineLicense
        // Live *events* are "event" content: sending their challenge to the
        // linear-channel endpoint makes the license server reject it with
        // "bad-license-request" (400), which ExoPlayer reports as
        // ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED.
        val drmEndpointKey = playback?.get("stream")?.get("attributes")
            ?.get("security")?.get("drmEndpointKey").textOrNull()
            ?: when {
                contentType == "linear" -> "linear"
                contentType == "live" ||
                    playback?.get("stream")?.get("streamingType").textOrNull()
                        ?.equals("LIVE", ignoreCase = true) == true -> "event"
                else -> "vod"
            }
        val licenseUrl = api.widevineLicenseUrl(drmEndpointKey)
        if (licenseUrl.isNullOrBlank()) return false

        // The media CDN rejects any request that carries an `authorization` header
        // (the signed URL is the auth), while the Widevine endpoint requires it.
        // Add it per-host in getVideoInterceptor instead of on the link itself.
        val manifestHeaders = mapOf(
            "User-Agent" to DisneyConstants.userAgent(),
        )
        // The DRM callback may not go through getVideoInterceptor, so carry the
        // bearer token on the link as well. The interceptor strips it again for
        // the media CDN hosts, which reject it.
        val linkHeaders = manifestHeaders + mapOf(
            "authorization" to "Bearer ${api.accessToken.orEmpty()}",
            "Content-Type" to "application/octet-stream",
        )

        // Disney+ is HLS-only, but CloudStream plays DRM exclusively through DASH.
        // Convert the HLS master to a DASH manifest and serve it via the interceptor.
        val preferredAudio = runCatching { api.profileContext().appLanguage }.getOrNull()
        val buildStarted = System.currentTimeMillis()
        val built = runCatching { DisneyDash.buildManifest(streamUrl, preferredAudio) }.getOrNull()
        Log.i(
            "DisneyLive",
            "buildManifest ${System.currentTimeMillis() - buildStarted} ms live=${built?.live} " +
                "xml=${built?.xml?.length ?: 0} bytes plan=${built?.plan?.variants?.size ?: 0} variants",
        )
        if (built != null) {
            dashManifests[streamUrl] = DashSource(
                hlsUrl = streamUrl,
                preferredAudio = preferredAudio,
                live = built.live,
                xml = built.xml,
                builtAt = System.currentTimeMillis(),
                availabilityStartTime = built.availabilityStartTime,
                presentationTimeOffset = built.presentationTimeOffset,
                plan = built.plan,
                subtitles = built.subtitles,
            )
        }

        // HLS subtitles are WebVTT segments; expose one proxy .vtt URL per language
        // and let the interceptor merge the segments when the user picks one. The
        // renditions come from the same master fetch as the manifest, so no second
        // master request is needed.
        val subtitles = built?.subtitles
            ?: runCatching { DisneyDash.buildSubtitles(streamUrl) }.getOrDefault(emptyList())
        subtitles.forEachIndexed { index, subtitle ->
            val proxyUrl = "https://disney.local/subtitles/$index.vtt"
            subtitleSources[proxyUrl] = subtitle.playlistUrl
            subtitleCallback.invoke(newSubtitleFile(subtitle.lang, proxyUrl))
        }

        val link = newDrmExtractorLink(
            "Disney+",
            "Disney+",
            streamUrl,
            if (built != null) ExtractorLinkType.DASH else ExtractorLinkType.M3U8,
            WIDEVINE_UUID,
        ) {
            this.licenseUrl = licenseUrl
            this.headers = linkHeaders
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

            if (isManifest) {
                val source = dashManifests[extractorLink.url] ?: manifest
                val xml = refreshIfLive(extractorLink.url, source)
                val body = xml.toResponseBody("application/dash+xml".toMediaType())
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
            val isPlaybackHost = request.url.host == "disney.playback.edge.bamgrid.com"
            if (isPlaybackHost) {
                api.accessToken?.takeIf { it.isNotBlank() }?.let {
                    builder.header("authorization", "Bearer $it")
                }
                builder.header("x-dss-feature-filtering", "true")
            } else {
                builder.removeHeader("authorization")
                builder.removeHeader("x-dss-feature-filtering")
                builder.removeHeader("Content-Type")
            }
            val isLicense = isPlaybackHost && request.url.encodedPath.contains("obtain-license")
            if (isLicense) {
                Log.i(
                    "DisneyDRM",
                    "license POST ${request.url} auth=${request.header("authorization") != null} " +
                        "contentType=${request.header("Content-Type")}",
                )
            }
            val response = chain.proceed(builder.build())
            if (isLicense) {
                Log.i("DisneyDRM", "license response code=${response.code} path=${request.url.encodedPath}")
            }
            response
        }
    }

    /**
     * Rebuilds a live manifest from its HLS playlist when the cached copy is older
     * than [LIVE_REFRESH_MS]. Rebuilding uses a direct HTTP client so it never
     * re-enters this interceptor. VOD manifests are returned unchanged.
     */
    private fun refreshIfLive(url: String, source: DashSource): String {
        if (!source.live) return source.xml
        if (System.currentTimeMillis() - source.builtAt < LIVE_REFRESH_MS) return source.xml
        val started = System.currentTimeMillis()
        val plan = source.plan
        val rebuilt = runCatching {
            runBlocking {
                if (plan != null) {
                    // Skip the master playlist entirely: only re-fetch the media
                    // playlists we already resolved during the initial load.
                    DisneyDash.refreshManifest(
                        plan = plan,
                        preferredAudio = source.preferredAudio,
                        direct = true,
                        anchorStartTime = source.availabilityStartTime,
                        anchorTimeOffset = source.presentationTimeOffset,
                        subtitles = source.subtitles,
                    )
                } else {
                    DisneyDash.buildManifest(
                        hlsUrl = source.hlsUrl,
                        preferredAudio = source.preferredAudio,
                        direct = true,
                        anchorStartTime = source.availabilityStartTime,
                        anchorTimeOffset = source.presentationTimeOffset,
                    )
                }
            }
        }.getOrNull()
        if (rebuilt == null || !rebuilt.live) {
            Log.w("DisneyLive", "rebuild failed or no longer live; serving cached manifest")
            return source.xml
        }
        dashManifests[url] = source.copy(
            xml = rebuilt.xml,
            builtAt = System.currentTimeMillis(),
            plan = rebuilt.plan ?: plan,
            subtitles = rebuilt.subtitles.ifEmpty { source.subtitles },
        )
        Log.i(
            "DisneyLive",
            "manifest rebuilt in ${System.currentTimeMillis() - started} ms: " +
                "${rebuilt.xml.length} bytes, variants=${rebuilt.plan?.variants?.size ?: 0}, " +
                "availabilityStartTime=${rebuilt.availabilityStartTime}, " +
                "presentationTimeOffset=${rebuilt.presentationTimeOffset}",
        )
        return rebuilt.xml
    }
}
