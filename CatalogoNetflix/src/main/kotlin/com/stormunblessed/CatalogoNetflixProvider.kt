package com.stormunblessed

import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
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

data class NetflixLatamData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class TmdbNetflixResult(
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

data class TmdbNetflixPageResult(
    @JsonProperty("results") val results: List<TmdbNetflixResult>? = null,
)

data class TmdbNetflixDetails(
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
    @JsonProperty("genres") val genres: List<TmdbNetflixGenre>? = null,
) {
    val displayTitle get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbNetflixGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class NetflixCrossMetaData(
    @JsonProperty("isSuccess") val isSuccess: Boolean,
    @JsonProperty("sources") val sources: List<NetflixCrossSource>? = null,
)

data class NetflixCrossSource(
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("dataUrl") val dataUrl: String,
)

/**
 * Netflix LATAM: meta provider for discovering Netflix content available in
 * Mexico/LATAM in Spanish. On load, it searches other installed providers
 * (same language) for the title and, if found, delegates playback to them.
 * Movies get a Play button via CrossMetaData; TV series get the matched
 * provider's episodes list. If no match is found, the detail shows metadata
 * only (comingSoon) with a search button via QuickSearch.
 */
class CatalogoNetflixProvider(private val filter: CatalogoSourceFilter) : MainAPI() {
    override var name = "Catálogo Netflix"
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

    // Many providers append a year to the displayed title (e.g. "Deseo (2026)").
    // Strip a trailing year before comparing so those names still match.
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

    // Resolving a title searches the other installed providers. CloudStream
    // calls load() for the home page preview (hero) row, so a slow load()
    // stalls the whole main page. All providers are therefore searched in
    // parallel and the whole search is capped by a short global deadline: the
    // worst case is a few seconds instead of one round of up to 10s per batch
    // of providers. The slowest providers are cut off individually, and only
    // COMPLETE results are cached per title so a truncated search is not
    // memoized as "no match" (a real match would stay hidden for the whole
    // cache window). A repeat load() is instant.
    private val providerSearchTimeoutMs = 5_000L
    private val findMatchesDeadlineMs = 6_000L
    private val maxMatchesForFallback = 4
    private val maxLoadsForFallback = 8

    // Short-lived cache for TMDB responses so that reloading the home page
    // (CloudStream refetches every section at once) is instant instead of
    // hammering TMDB with ~36 parallel requests.
    private val tmdbCacheMs = 15L * 60 * 1000

    private data class TmdbCacheEntry(val timestamp: Long, val body: String)

    private val tmdbCache = Collections.synchronizedMap(HashMap<String, TmdbCacheEntry>())

    // Short-lived cache for the provider-search outcome per title, so the search
    // is only paid for the first time a title is opened (a second open of the
    // same title, e.g. from another row or after the app's own load cache
    // evicts it, returns instantly).
    private val searchCacheMs = 10L * 60 * 1000

    private data class SearchCacheEntry(val timestamp: Long, val matches: List<SearchMatch>)

    private val searchCache = Collections.synchronizedMap(HashMap<String, SearchCacheEntry>())

    private class SearchMatch(val providerName: String, val loaded: LoadResponse)

    // A match must be of the same media type (movie vs series) as the item
    // being loaded, otherwise a same-named movie could be returned for a
    // series and vice versa.
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

        // Cache only a complete result: a run cut short by the deadline (some
        // slow provider not yet answered) is not memoized as "no match", so a
        // real match is not hidden for the whole cache window.
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

    // Netflix provider ID on TMDB, Mexico watch region
    private val netflixProviderId = "8"
    private val watchRegion = "MX"

    // Netflix network ID for originals
    private val netflixNetworkId = "213"

    // Minimum votes to filter junk
    private val minVotes = 50

    // Per-genre section configuration
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

    // Deduplication across main page sections
    private val minSectionResults = 12
    private val seenWindowMs = 30L * 60 * 1000
    private val seenMainPageKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private var seenWindowStart = System.currentTimeMillis()

    private fun mainPageKey(result: TmdbNetflixResult): String =
        "${result.isTv}-${result.id ?: result.displayTitle}"

    private fun dedupeMainPage(raw: List<TmdbNetflixResult>): List<TmdbNetflixResult> {
        val now = System.currentTimeMillis()
        if (now - seenWindowStart > seenWindowMs) {
            seenMainPageKeys.clear()
            seenWindowStart = now
        }
        val fresh = mutableListOf<TmdbNetflixResult>()
        val repeats = mutableListOf<TmdbNetflixResult>()
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
        mainPage("top10global", "Top 10 Global", false),
        mainPage("top10mexico", "Top 10 México", false),
        mainPage("popular", "Netflix Popular", false),
        mainPage("top", "Mejor Calificadas", false),
        mainPage("nuevos", "Estrenos", false),
        mainPage("originales", "Netflix Originales", false),
        mainPage("kdrama", "K-Drama", false),
        mainPage("anime", "Anime", false),
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
        val horizontal = false

        val raw = when (request.data) {
            "top10global" -> top10Section("https://www.netflix.com/tudum/top10")

            "top10mexico" -> top10Section("https://www.netflix.com/tudum/top10/mexico")

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

            "kdrama" -> discoverKoreanSeries(page, "popularity.desc")

            "anime" -> discoverAnime(page, "popularity.desc")

            else -> emptyList()
        }

        val isTop10 = request.data == "top10global" || request.data == "top10mexico"
        val results = if (isTop10) {
            raw.mapNotNull { it.toNetflixSearchResponse(horizontal) }
        } else {
            dedupeMainPage(raw).mapNotNull { it.toNetflixSearchResponse(horizontal) }
        }

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = !isTop10 && results.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<NetflixLatamData>(url) ?: return null
        val id = data.id ?: return null

        val details = if (data.isTv) {
            parseJson<TmdbNetflixDetails>(tmdbGet("/tv/$id"))
        } else {
            parseJson<TmdbNetflixDetails>(tmdbGet("/movie/$id"))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (data.isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        // Search other providers for this title. All providers are hit in parallel
        // under a short global deadline so that load() stays fast even when many
        // providers are installed — CloudStream calls load() for the home page
        // preview row, so a slow load() stalls the main page.
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
                val crossData = NetflixCrossMetaData(
                    true,
                    movieMatches.map { NetflixCrossSource(it.providerName, (it.loaded as MovieLoadResponse).dataUrl) }
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
                // Collect every provider that matched as a series so that when the
                // user plays an episode, loadLinks can fall back across all of
                // them instead of only the first match (a provider may lack
                // working links for an episode while another has them).
                val seriesMatches = matches.filter { it.loaded is TvSeriesLoadResponse }
                val wrappedEpisodes = loaded.episodes.map { ep ->
                    val sources = mutableListOf(NetflixCrossSource(first.providerName, ep.data))
                    seriesMatches.forEach { other ->
                        if (other.providerName == first.providerName) return@forEach
                        val otherLoaded = other.loaded as TvSeriesLoadResponse
                        otherLoaded.episodes.firstOrNull {
                            it.season == ep.season && it.episode == ep.episode
                        }?.let { sources.add(NetflixCrossSource(other.providerName, it.data)) }
                    }
                    val wrapped = NetflixCrossMetaData(true, sources).toJson()
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
        tryParseJson<NetflixCrossMetaData>(data)?.let { metaData ->
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
        tryParseJson<NetflixCrossSource>(data)?.let { source ->
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

    private suspend fun top10Section(url: String): List<TmdbNetflixResult> {
        return fetchTop10Titles(url).mapNotNull { title -> searchTop10Title(title) }
    }

    private suspend fun fetchTop10Titles(url: String): List<String> {
        return try {
            val doc = app.get(
                url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
                referer = "https://www.netflix.com/",
            ).document
            doc.select("[data-uia=top10-table-row-title] button").mapNotNull {
                it.text().trim().takeIf { t -> t.isNotBlank() }
            }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private suspend fun searchTop10Title(title: String): TmdbNetflixResult? {
        val movies = parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/search/movie",
                buildMap {
                    put("query", title)
                }
            )
        ).results.orEmpty()
        movies.firstOrNull()?.let { return it }

        val series = parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/search/tv",
                buildMap {
                    put("query", title)
                }
            )
        ).results.orEmpty()
        return series.firstOrNull()
    }

    private suspend fun discoverMovies(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        originals: Boolean = false,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    if (recent) put("primary_release_date.gte", "${currentYear()}-01-01")
                    put("vote_count.gte", if (minVotes) "200" else "$minVotes")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun discoverSeries(
        page: Int,
        sortBy: String,
        recent: Boolean = false,
        minVotes: Boolean = false,
        originals: Boolean = false,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    if (originals) put("with_networks", netflixNetworkId)
                    if (recent) put("first_air_date.gte", "${currentYear()}-01-01")
                    put("vote_count.gte", if (minVotes) "100" else "20")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun discoverKoreanSeries(
        page: Int,
        sortBy: String,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("with_original_language", "ko")
                    put("vote_count.gte", "20")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun discoverAnime(
        page: Int,
        sortBy: String,
    ): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("with_genres", "16")
                    put("with_original_language", "ja")
                    put("vote_count.gte", "20")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun genreMovies(page: Int, config: GenreSection): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "${config.id}")
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("vote_count.gte", if (config.minVotes) "200" else "$minVotes")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun genreSeries(page: Int, config: GenreSection): List<TmdbNetflixResult> {
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "${config.id}")
                    put("with_watch_providers", netflixProviderId)
                    put("watch_region", watchRegion)
                    put("vote_count.gte", if (config.minVotes) "100" else "20")
                }
            )
        ).results ?: emptyList()
    }

    private fun TmdbNetflixResult.toNetflixSearchResponse(
        preferLandscape: Boolean = false
    ): SearchResponse? {
        val display = displayTitle
        if (display.isBlank()) return null
        val id = this.id ?: return null

        val data = NetflixLatamData(id, isTv, display, year).toJson()

        val poster = if (preferLandscape) {
            getImageUrl(backdropPath ?: posterPath, "w780")
        } else {
            getImageUrl(posterPath)
        }

        return if (isTv) {
            newTvSeriesSearchResponse(display, data, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = this@toNetflixSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        } else {
            newMovieSearchResponse(display, data, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = this@toNetflixSearchResponse.year
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
