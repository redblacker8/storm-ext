package com.stormunblessed

import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.stormunblessed.settings.CatalogoSourceFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

data class HboLatamData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class TmdbHboResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
) {
    val isTv get() = name != null || mediaType == "tv"
    val displayTitle get() = title ?: originalTitle ?: name ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbHboPageResult(
    @JsonProperty("results") val results: List<TmdbHboResult>? = null,
)

data class TmdbHboDetails(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("genres") val genres: List<TmdbHboGenre>? = null,
) {
    val displayTitle get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbHboGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class HboCrossMetaData(
    @JsonProperty("isSuccess") val isSuccess: Boolean,
    @JsonProperty("sources") val sources: List<HboCrossSource>? = null,
)

data class HboCrossSource(
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("dataUrl") val dataUrl: String,
)

/**
 * HBO Max LATAM: meta provider for discovering HBO Max content available in
 * Mexico/LATAM in Spanish. On load, it searches other installed providers
 * (same language) for the title and, if found, delegates playback to them.
 */
class CatalogoHboMaxProvider(private val filter: CatalogoSourceFilter) : MainAPI() {
    override var name = "Catálogo HBO Max"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "mx"
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val hasMainPage = true

    private fun filterName(name: String): String {
        return Regex("""[^a-zA-Z0-9-]""").replace(name, "")
    }

    private fun comparableName(name: String): String {
        return filterName(Regex("""\s*\(?\d{4}\)?\s*$""").replace(name, ""))
    }

    // Other installed providers that can supply links. Meta providers (the
    // other catalogs) are excluded so a catalog never delegates to another
    // catalog (which would chain searches and duplicate sources), and providers
    // blocked in the settings panel are skipped.
    private val validApis: List<MainAPI>
        get() {
            val blocked = filter.effectiveBlocked()
            return apis.filter {
                it.lang == this.lang &&
                    it.providerType != ProviderType.MetaProvider &&
                    it.name !in blocked
            }
        }

    private val providerSearchTimeoutMs = 5_000L
    private val findMatchesDeadlineMs = 6_000L
    private val maxMatchesForFallback = 4
    private val maxLoadsForFallback = 8

    private val tmdbCacheMs = 15L * 60 * 1000

    private data class TmdbCacheEntry(val timestamp: Long, val body: String)

    private val tmdbCache = Collections.synchronizedMap(HashMap<String, TmdbCacheEntry>())

    private val searchCacheMs = 10L * 60 * 1000

    private data class SearchCacheEntry(val timestamp: Long, val matches: List<SearchMatch>)

    private val searchCache = Collections.synchronizedMap(HashMap<String, SearchCacheEntry>())

    private class SearchMatch(val providerName: String, val loaded: LoadResponse)

    private fun SearchResponse.typeOk(isTv: Boolean): Boolean {
        val t = this.type
        return if (isTv) {
            t?.let { it != TvType.Movie && it != TvType.AnimeMovie } ?: (this is TvSeriesSearchResponse)
        } else {
            t?.let { it == TvType.Movie || it == TvType.AnimeMovie } ?: (this is MovieSearchResponse)
        }
    }

    private fun SearchResponse.yearOk(searchYear: Int?): Boolean {
        val y = when (this) {
            is MovieSearchResponse -> this.year
            is TvSeriesSearchResponse -> this.year
            else -> null
        } ?: return true
        return searchYear == null || y == searchYear
    }

    private class CandidateMatch(val api: MainAPI, val searchResponse: SearchResponse)

    // Phase 1: find a matching search result. This is cheap (no detail request),
    // so it still runs for every allowed provider in parallel.
    private suspend fun searchMatch(
        api: MainAPI,
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): CandidateMatch? {
        return try {
            withTimeout(providerSearchTimeoutMs) {
                val searchResult = api.search(title, 1) ?: return@withTimeout null
                val candidates = searchResult.items.filter { it.typeOk(isTv) }
                if (candidates.isEmpty()) return@withTimeout null

                // Prefer an exact title + year match; otherwise fall back to a
                // same-title match, since many providers report a different or no
                // year for the same movie (e.g. new releases, regional titles).
                val matched = candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true) && it.yearOk(searchYear)
                } ?: candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true)
                } ?: return@withTimeout null

                CandidateMatch(api, matched)
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // Phase 2: only the first maxLoadsForFallback candidates pay this; the rest
    // are dropped before making a detail request.
    private suspend fun loadMatch(candidate: CandidateMatch): SearchMatch? {
        return try {
            withTimeout(providerSearchTimeoutMs) {
                val loaded = candidate.api.load(candidate.searchResponse.url) ?: return@withTimeout null
                SearchMatch(candidate.api.name, loaded)
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun findMatches(
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): List<SearchMatch> {
        // The blocked set is part of the key: a change in the settings panel must
        // not resurrect a result cached with the previous selection.
        val key = "$isTv|$matchName|$searchYear|${filter.signature()}"
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.timestamp < searchCacheMs) return it.matches }

        // All providers are hit in parallel so that the wall-clock time is the
        // slowest provider, not the sum of all of them. The global deadline
        // bounds the whole search (e.g. when the title matches nothing and
        // every provider has to answer first).
        var complete = true
        // Searches run for every allowed provider; only the first
        // maxLoadsForFallback matches pay the expensive load(). The counter is
        // local so concurrent loads of different titles do not share it.
        val loads = AtomicInteger(0)
        val matches = withTimeoutOrNull(findMatchesDeadlineMs) {
            validApis.amap { api ->
                val candidate = searchMatch(api, title, matchName, searchYear, isTv) ?: return@amap null
                if (loads.incrementAndGet() > maxLoadsForFallback) return@amap null
                loadMatch(candidate)
            }.filterNotNull()
        } ?: run {
            complete = false
            emptyList()
        }

        val result = matches.take(maxMatchesForFallback)

        if (complete) {
            searchCache[key] = SearchCacheEntry(now, result)
            if (searchCache.size > 250) {
                searchCache.clear()
            }
        }
        return result
    }

    private val apiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val apiBase = "https://api.themoviedb.org/3"

    // HBO Max provider ID on TMDB, Mexico watch region
    private val hboMaxProviderId = "1899"
    private val watchRegion = "MX"

    // HBO network ID for originals
    private val hboNetworkId = "49"

    private val minVotes = 50

    private data class GenreSection(val id: Int, val sortBy: String, val minVotes: Boolean)

    private val genreSections = mapOf(
        "accion" to GenreSection(28, "popularity.desc", false),
        "drama" to GenreSection(18, "vote_average.desc", true),
        "comedia" to GenreSection(35, "popularity.desc", false),
        "thriller" to GenreSection(53, "vote_average.desc", true),
        "misterio" to GenreSection(9648, "vote_average.desc", true),
        "scifi" to GenreSection(878, "popularity.desc", false),
        "fantasia" to GenreSection(14, "popularity.desc", false),
        "horror" to GenreSection(27, "popularity.desc", false),
        "documental" to GenreSection(99, "popularity.desc", false),
        "animacion" to GenreSection(16, "popularity.desc", false),
        "aventura" to GenreSection(12, "popularity.desc", false),
        "romance" to GenreSection(10749, "vote_average.desc", true),
    )

    private val minSectionResults = 12
    private val seenWindowMs = 30L * 60 * 1000
    private val seenMainPageKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private var seenWindowStart = System.currentTimeMillis()

    private fun mainPageKey(result: TmdbHboResult): String =
        "${result.isTv}-${result.id ?: result.displayTitle}"

    private fun dedupeMainPage(raw: List<TmdbHboResult>): List<TmdbHboResult> {
        val now = System.currentTimeMillis()
        if (now - seenWindowStart > seenWindowMs) {
            seenMainPageKeys.clear()
            seenWindowStart = now
        }
        val fresh = mutableListOf<TmdbHboResult>()
        val repeats = mutableListOf<TmdbHboResult>()
        for (result in raw) {
            if (mainPageKey(result) in seenMainPageKeys) {
                repeats.add(result)
            } else {
                seenMainPageKeys.add(mainPageKey(result))
                fresh.add(result)
            }
        }
        val fill = (minSectionResults - fresh.size).coerceAtLeast(0)
        return fresh + repeats.take(fill)
    }

    override val mainPage = listOf(
        mainPage("top10", "Top 10 HBO Max", false),
        mainPage("tendencias", "Tendencias", false),
        mainPage("popular", "HBO Max Popular", false),
        mainPage("top", "Mejor Calificadas", false),
        mainPage("nuevos", "Estrenos", false),
        mainPage("originales", "HBO Originales", false),
        mainPage("accion", "Acción", false),
        mainPage("drama", "Drama", false),
        mainPage("comedia", "Comedia", false),
        mainPage("thriller", "Thriller", false),
        mainPage("misterio", "Misterio", false),
        mainPage("scifi", "Ciencia Ficción", false),
        mainPage("fantasia", "Fantasía", false),
        mainPage("horror", "Horror", false),
        mainPage("documental", "Documentales", false),
        mainPage("animacion", "Animación", false),
        mainPage("aventura", "Aventura", false),
        mainPage("romance", "Romance", false),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val horizontal = request.data == "top10"

        val raw = when (request.data) {
            "popular" ->
                discoverMovies(page, "popularity.desc") +
                        discoverSeries(page, "popularity.desc")

            "top" ->
                discoverMovies(page, "vote_average.desc", minVotes = true) +
                        discoverSeries(page, "vote_average.desc", minVotes = true)

            "nuevos" ->
                discoverMovies(page, "primary_release_date.desc", recent = true) +
                        discoverSeries(page, "first_air_date.desc", recent = true)

            "originales" ->
                discoverSeries(page, "popularity.desc", originals = true)

            "accion", "drama", "comedia", "thriller", "misterio",
            "scifi", "fantasia", "horror", "documental", "animacion",
            "aventura", "romance" -> {
                val config = genreSections[request.data] ?: return newHomePageResponse(
                    HomePageList(request.name, emptyList(), true),
                    hasNext = false
                )
                genreMovies(page, config) + genreSeries(page, config)
            }

            else -> emptyList()
        }

        val results = dedupeMainPage(raw).mapNotNull { it.toHboSearchResponse(horizontal) }

        // Top 10 is a fixed list — no infinite scroll
        val hasNext = if (request.data == "top10") false else results.isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<HboLatamData>(url) ?: return null
        val id = data.id ?: return null

        val details = if (data.isTv) {
            parseJson<TmdbHboDetails>(tmdbGet("/tv/$id"))
        } else {
            parseJson<TmdbHboDetails>(tmdbGet("/movie/$id"))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (data.isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        val matchName = filterName(title)
        val searchYear = year
        val matches = findMatches(title, matchName, searchYear, data.isTv)

        if (matches.isEmpty()) {
            return if (data.isTv) {
                newTvSeriesLoadResponse(title, url, type, episodes = emptyList()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(title, url, type, dataUrl = "") {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }
        }

        val first = matches.first()

        return when (val loaded = first.loaded) {
            is MovieLoadResponse -> {
                val movieMatches = matches.filter { it.loaded is MovieLoadResponse }
                val crossData = HboCrossMetaData(
                    true,
                    movieMatches.map { HboCrossSource(it.providerName, (it.loaded as MovieLoadResponse).dataUrl) }
                ).toJson()
                newMovieLoadResponse(title, url, type, dataUrl = crossData) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                    this.duration = details.runtime?.let { it * 60 }
                }
            }

            is TvSeriesLoadResponse -> {
                val seriesMatches = matches.filter { it.loaded is TvSeriesLoadResponse }
                val wrappedEpisodes = loaded.episodes.map { ep ->
                    val sources = mutableListOf(HboCrossSource(first.providerName, ep.data))
                    seriesMatches.forEach { other ->
                        if (other.providerName == first.providerName) return@forEach
                        val otherLoaded = other.loaded as TvSeriesLoadResponse
                        otherLoaded.episodes.firstOrNull {
                            it.season == ep.season && it.episode == ep.episode
                        }?.let { sources.add(HboCrossSource(other.providerName, it.data)) }
                    }
                    val wrapped = HboCrossMetaData(true, sources).toJson()
                    @Suppress("DEPRECATION_ERROR")
                    Episode(
                        data = wrapped,
                        name = ep.name,
                        season = ep.season,
                        episode = ep.episode,
                        posterUrl = ep.posterUrl,
                        score = ep.score,
                        description = ep.description,
                        date = ep.date,
                        runTime = ep.runTime,
                    )
                }
                newTvSeriesLoadResponse(title, url, type, episodes = wrappedEpisodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = details.overview
                    this.score = score
                    this.tags = tags
                }
            }

            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        var blockedSources = 0
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linksFound = true
            callback(link)
        }
        tryParseJson<HboCrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            metaData.sources?.amap { source ->
                // Sources blocked in the settings panel are skipped here too, so
                // a detail page opened before the change stops using them.
                if (!filter.isAllowed(source.apiName)) {
                    blockedSources++
                    return@amap
                }
                getApiFromNameNull(source.apiName)?.let {
                    try {
                        it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            if (!linksFound) notifyAllSourcesBlocked(blockedSources)
            return linksFound
        }
        tryParseJson<HboCrossSource>(data)?.let { source ->
            if (!filter.isAllowed(source.apiName)) {
                notifyAllSourcesBlocked(1)
                return false
            }
            getApiFromNameNull(source.apiName)?.let {
                try {
                    it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return linksFound
        }
        return false
    }

    /**
     * The app shows a generic "No links found" when nothing plays, so explain the
     * otherwise invisible cause: every source for this title is blocked in the
     * settings panel.
     */
    private fun notifyAllSourcesBlocked(blockedSources: Int) {
        if (blockedSources <= 0) return
        runCatching {
            CommonActivity.showToast(
                "$name: todas las fuentes de este título están bloqueadas en los ajustes.",
                Toast.LENGTH_LONG,
            )
        }
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        val key = "$path${params.toSortedMap()}"
        val now = System.currentTimeMillis()
        tmdbCache[key]?.let { if (now - it.timestamp < tmdbCacheMs) return it.body }

        val body = app.get(
            url = "$apiBase$path",
            params = buildMap {
                put("api_key", apiKey)
                put("language", "es-MX")
                putAll(params)
            },
        ).text
        tmdbCache[key] = TmdbCacheEntry(now, body)
        return body
    }

    private suspend fun discoverMovies(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        limit: Int = 0,
    ): List<TmdbHboResult> {
        return parseJson<TmdbHboPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", hboMaxProviderId)
                    put("watch_region", watchRegion)
                    if (recent) put("primary_release_date.gte", "${currentYear()}-01-01")
                    put("vote_count.gte", if (minVotes) "200" else "$minVotes")
                }
            )
        ).results.let { list ->
            if (limit > 0) list?.take(limit) else list
        } ?: emptyList()
    }

    private suspend fun discoverSeries(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        originals: Boolean = false,
        limit: Int = 0,
    ): List<TmdbHboResult> {
        return parseJson<TmdbHboPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", hboMaxProviderId)
                    put("watch_region", watchRegion)
                    if (originals) put("with_networks", hboNetworkId)
                    if (recent) put("first_air_date.gte", "${currentYear()}-01-01")
                    put("vote_count.gte", if (minVotes) "100" else "20")
                }
            )
        ).results.let { list ->
            if (limit > 0) list?.take(limit) else list
        } ?: emptyList()
    }

    private suspend fun genreMovies(page: Int, config: GenreSection): List<TmdbHboResult> {
        return parseJson<TmdbHboPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "${config.id}")
                    put("with_watch_providers", hboMaxProviderId)
                    put("watch_region", watchRegion)
                    put("vote_count.gte", if (config.minVotes) "200" else "$minVotes")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun genreSeries(page: Int, config: GenreSection): List<TmdbHboResult> {
        return parseJson<TmdbHboPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "${config.id}")
                    put("with_watch_providers", hboMaxProviderId)
                    put("watch_region", watchRegion)
                    put("vote_count.gte", if (config.minVotes) "100" else "20")
                }
            )
        ).results ?: emptyList()
    }

    private fun TmdbHboResult.toHboSearchResponse(
        preferLandscape: Boolean = false
    ): SearchResponse? {
        val display = displayTitle
        if (display.isBlank()) return null
        val id = this.id ?: return null

        val data = HboLatamData(id, isTv, display, year).toJson()

        val poster = if (preferLandscape) {
            getImageUrl(backdropPath ?: posterPath, "w780")
        } else {
            getImageUrl(posterPath)
        }

        return if (isTv) {
            newTvSeriesSearchResponse(display, data, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = this@toHboSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        } else {
            newMovieSearchResponse(display, data, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = this@toHboSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        }
    }

    private fun getImageUrl(link: String?, size: String = "w500"): String? {
        link ?: return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$size$link" else link
    }

    private fun currentYear(): Int {
        return Calendar.getInstance().get(Calendar.YEAR)
    }
}
