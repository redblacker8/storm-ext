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
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale
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
    @JsonProperty("seasons") val seasons: List<NetflixSeason>? = null,
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
 * Playback target stored in the detail dataUrl (movies) or in each episode data
 * (series).
 *
 * load() only resolves TMDB metadata and never searches the other providers:
 * CloudStream calls load() for the home page preview (hero) row, so an eager
 * search delayed the first paint of the whole home page by several seconds. The
 * provider search runs lazily in loadLinks(), when the user presses play.
 */
data class NetflixDeferredData(
    @JsonProperty("title") val title: String,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    // Absolute position in the show (0-based): used to find the provider episode
    // when it lists the whole show as a single season (e.g. anime).
    @JsonProperty("index") val index: Int? = null,
)

/** TMDB season list (/tv/{id}), used to build the episode list. */
data class NetflixSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null,
)

data class NetflixSeasonDetails(
    @JsonProperty("episodes") val episodes: List<NetflixTvEpisode>? = null,
)

data class NetflixTvEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
)

/** Netflix Top 10 row: the title and the poster from the Netflix CDN. */
data class NetflixTop10Row(
    @JsonProperty("title") val title: String = "",
    @JsonProperty("poster") val poster: String? = null,
)

data class NetflixTop10Payload(
    @JsonProperty("rows") val rows: List<NetflixTop10Row>? = null,
)

/**
 * Netflix LATAM: meta provider for discovering Netflix content available in
 * Mexico/LATAM in Spanish.
 *
 * load() only resolves TMDB metadata (and the episode list of a series): the
 * installed providers are searched lazily in loadLinks(), when the user presses
 * play. That keeps the home page fast, because the app calls load() for the
 * hero row of the home page. Playback itself is delegated to the matched
 * provider, so the sources keep working as before.
 */
class CatalogoNetflixProvider(
    private val filter: CatalogoSourceFilter,
    // Directory of the persisted section cache (null disables it).
    private val cacheDir: File? = null,
) : MainAPI() {
    override var name = "Catálogo Netflix"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "mx"
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val hasMainPage = true

    // A hung TMDB request would otherwise keep the home page on the loading
    // spinner for the app's default 120s.
    override val getMainPageTimeoutMs: Long? = 20_000L

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

    // Section bodies are also kept on disk: on Android TV the app is killed
    // between sessions, so an in-memory cache alone made every cold start
    // refetch all sections (~20 requests) before the home page could paint.
    private val tmdbDiskCacheMs = 6L * 60 * 60 * 1000

    private fun cacheFile(key: String): File? {
        val dir = cacheDir ?: return null
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir, name)
    }

    private fun readCacheFile(key: String, ttlMs: Long): String? {
        return try {
            val file = cacheFile(key) ?: return null
            if (!file.exists()) return null
            if (System.currentTimeMillis() - file.lastModified() > ttlMs) {
                file.delete()
                return null
            }
            val text = file.readText()
            val newline = text.indexOf('\n')
            if (newline <= 0) return null
            // The key is stored in the file, so a hash collision can never serve
            // another section's body.
            if (text.substring(0, newline) != key) return null
            text.substring(newline + 1)
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private fun writeCacheFile(key: String, body: String) {
        try {
            val file = cacheFile(key) ?: return
            file.parentFile?.mkdirs()
            file.writeText("$key\n$body")
        } catch (e: Exception) {
            logError(e)
        }
    }

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
    // TMDB uses a different genre id space for series: "Acción" is 28 for
    // movies but 10759 (Action & Adventure) for TV, and some movie genres
    // (Terror, Fantasía) have no TV equivalent. A null [tvId] means the row is
    // movies only, so no series request is made at all.
    private data class GenreSection(
        val id: Int,
        val sortBy: String,
        val minVotes: Boolean,
        val tvId: Int? = null,
    )

    private val genreSections = mapOf(
        "accion" to GenreSection(28, "popularity.desc", false, tvId = 10759),
        "comedia" to GenreSection(35, "popularity.desc", false, tvId = 35),
        "scifi" to GenreSection(878, "popularity.desc", false, tvId = 10765),
        "fantasia" to GenreSection(14, "popularity.desc", false),
        "horror" to GenreSection(27, "popularity.desc", false),
        "animacion" to GenreSection(16, "popularity.desc", false, tvId = 16),
    )

    // A row shows at most this many cards: the rest of the TMDB page is dropped
    // because the UI never scrolls that far and every card is an image to load.
    private val maxRowItems = 24

    // Within-row dedupe only. CloudStream fetches every section in parallel, so a
    // cross-row "already seen" set was filled by whichever section finished
    // first (not by the first row of the list), which emptied rows or replaced
    // their content with repeats nondeterministically between reloads. The same
    // title in two different rows is fine.
    private fun rowResults(raw: List<TmdbNetflixResult>): List<TmdbNetflixResult> =
        raw.distinctBy { "${it.isTv}-${it.id ?: it.displayTitle}" }.take(maxRowItems)

    private fun <T> interleave(first: List<T>, second: List<T>): List<T> {
        val out = ArrayList<T>(first.size + second.size)
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let { out.add(it) }
            second.getOrNull(i)?.let { out.add(it) }
        }
        return out
    }

    private suspend fun <T> safeRow(block: suspend () -> List<T>): List<T> {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    // Movie and series discovery are joined into one row, but both requests are
    // fired in parallel so the row costs one round trip instead of two. The cards
    // alternate movie/series; a plain concatenation would put 20 movies first and
    // push the series off the first screen.
    private suspend fun <T> bothRows(
        movies: suspend () -> List<T>,
        series: suspend () -> List<T>,
    ): List<T> = supervisorScope {
        val movieRow = async { safeRow(movies) }
        val seriesRow = async { safeRow(series) }
        interleave(movieRow.await(), seriesRow.await())
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
        mainPage("comedia", "Comedia", false),
        mainPage("scifi", "Ciencia Ficción", false),
        mainPage("fantasia", "Fantasía", false),
        mainPage("horror", "Horror", false),
        mainPage("animacion", "Animación", false),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val horizontal = false

        // Top 10 rows are a fixed list: they are built from the Netflix page
        // (cached for hours) and have no pagination.
        val top10Url = when (request.data) {
            "top10global" -> "https://www.netflix.com/tudum/top10"
            "top10mexico" -> "https://www.netflix.com/tudum/top10/mexico"
            else -> null
        }
        if (top10Url != null) {
            // The Tudum page only exposes landscape thumbnails (389x219), so the
            // row is rendered with horizontal cards instead of portrait posters.
            return newHomePageResponse(
                HomePageList(request.name, top10Items(top10Url), isHorizontalImages = true),
                hasNext = false
            )
        }

        val raw = when (request.data) {
            "popular" -> bothRows(
                { discoverMovies(page, "popularity.desc") },
                { discoverSeries(page, "popularity.desc") },
            )

            "top" -> bothRows(
                { discoverMovies(page, "vote_average.desc", minVotes = true) },
                { discoverSeries(page, "vote_average.desc", minVotes = true) },
            )

            "nuevos" -> bothRows(
                { discoverMovies(page, "primary_release_date.desc", recent = true) },
                { discoverSeries(page, "first_air_date.desc", recent = true) },
            )

            "originales" -> discoverSeries(page, "popularity.desc", originals = true)

            "accion", "comedia", "scifi", "fantasia", "horror", "animacion" -> {
                val config = genreSections[request.data] ?: return newHomePageResponse(
                    HomePageList(request.name, emptyList(), horizontal),
                    hasNext = false
                )
                bothRows(
                    { genreMovies(page, config) },
                    { genreSeries(page, config) },
                )
            }

            "kdrama" -> discoverKoreanSeries(page, "popularity.desc")

            "anime" -> discoverAnime(page, "popularity.desc")

            else -> emptyList()
        }

        val results = rowResults(raw).mapNotNull { it.toNetflixSearchResponse(horizontal) }

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<NetflixLatamData>(url) ?: return null

        // Top 10 rows only carry the title (their poster comes from the Netflix
        // CDN), so the TMDB id is resolved here instead of on the home page.
        var id = data.id
        var isTv = data.isTv
        if (id == null) {
            val resolved = resolveTmdbId(data.title ?: return null) ?: return null
            id = resolved.id ?: return null
            isTv = resolved.isTv
        }

        val details = if (isTv) {
            parseJson<TmdbNetflixDetails>(tmdbGet("/tv/$id"))
        } else {
            parseJson<TmdbNetflixDetails>(tmdbGet("/movie/$id"))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        // No provider search here: load() is also called by the app for the home
        // page hero row (3 titles per home load), and searching every installed
        // provider delayed the first paint of the whole main page by several
        // seconds. The search runs in loadLinks(), when the user presses play.
        return if (isTv) {
            val episodes = buildTmdbEpisodes(id, title, year, details.seasons)
            newTvSeriesLoadResponse(title, url, type, episodes = episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = details.overview
                this.score = score
                this.tags = tags
            }
        } else {
            val deferred = NetflixDeferredData(title = title, year = year, isTv = false).toJson()
            newMovieLoadResponse(title, url, type, dataUrl = deferred) {
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

    /**
     * Resolves the TMDB entry of a title without id (the Top 10 rows, built from
     * the Netflix page so no TMDB search is needed per row).
     */
    private suspend fun resolveTmdbId(title: String): TmdbNetflixResult? {
        val results = try {
            parseJson<TmdbNetflixPageResult>(
                tmdbGet("/search/multi", buildMap { put("query", title) })
            ).results.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
        val candidates = results.filter { it.mediaType != "person" && it.id != null }
        // The catalog row carries the title as published by the source (e.g. the
        // original English one), while TMDB answers with the local title, so the
        // original title is compared too.
        val wanted = comparableName(title)
        fun matches(candidate: TmdbNetflixResult): Boolean =
            comparableName(candidate.displayTitle) == wanted ||
                candidate.originalTitle?.let { comparableName(it) == wanted } == true ||
                candidate.originalName?.let { comparableName(it) == wanted } == true

        return candidates.firstOrNull { matches(it) } ?: candidates.firstOrNull()
    }

    /**
     * Episode list from TMDB: one request per season, fired in parallel and
     * cached by [tmdbGet]. This keeps the series detail fast without waiting for
     * a provider (the previous list came from the matched provider, which
     * required a full provider search inside load()).
     *
     * Every episode stores a deferred target, so the provider episode is looked
     * up only when the user plays it.
     */
    private suspend fun buildTmdbEpisodes(
        id: Int,
        title: String,
        year: Int?,
        seasons: List<NetflixSeason>?,
    ): List<Episode> {
        val validSeasons = seasons.orEmpty()
            .filter { (it.seasonNumber ?: 0) > 0 && (it.episodeCount ?: 0) > 0 }
            .sortedBy { it.seasonNumber }

        val fetched = validSeasons.amap { season ->
            val seasonNumber = season.seasonNumber ?: return@amap emptyList<Pair<Int, List<NetflixTvEpisode>>>()
            val episodes = try {
                parseJson<NetflixSeasonDetails>(
                    tmdbGet("/tv/$id/season/$seasonNumber")
                ).episodes.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            listOf(seasonNumber to episodes.sortedBy { it.episodeNumber ?: 0 })
        }.flatten()

        val out = mutableListOf<Episode>()
        var index = 0
        for ((seasonNumber, episodes) in fetched) {
            for (raw in episodes) {
                val episodeNumber = raw.episodeNumber ?: continue
                @Suppress("DEPRECATION_ERROR")
                out.add(
                    Episode(
                        data = NetflixDeferredData(
                            title = title,
                            year = year,
                            isTv = true,
                            season = seasonNumber,
                            episode = episodeNumber,
                            index = index,
                        ).toJson(),
                        name = raw.name?.takeIf { it.isNotBlank() } ?: "Episodio $episodeNumber",
                        season = seasonNumber,
                        episode = episodeNumber,
                        posterUrl = getImageUrl(raw.stillPath, "w300"),
                        description = raw.overview?.takeIf { it.isNotBlank() },
                        date = isoDateToMillis(raw.airDate),
                        runTime = raw.runtime?.let { it * 60 },
                    )
                )
                index++
            }
        }
        return out
    }

    private fun isoDateToMillis(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.time
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linksFound = true
            callback(link)
        }

        suspend fun play(sources: List<NetflixCrossSource>): Boolean {
            // Sources blocked in the settings panel are skipped here too, so a
            // detail page opened before the change stops using them.
            val allowed = sources.filter { filter.isAllowed(it.apiName) }
            allowed.amap { source ->
                getApiFromNameNull(source.apiName)?.let {
                    try {
                        it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            if (!linksFound) notifyNoSources(sources.size - allowed.size, sources.size)
            return linksFound
        }

        // Normal path: the detail only stored the title, so the providers are
        // searched now (cached per title for a few minutes).
        tryParseJson<NetflixDeferredData>(data)?.let { deferred ->
            return play(resolveSources(deferred))
        }
        // Compatibility with payloads built by older versions.
        tryParseJson<NetflixCrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            return play(metaData.sources.orEmpty())
        }
        tryParseJson<NetflixCrossSource>(data)?.let { source ->
            return play(listOf(source))
        }
        return false
    }

    /**
     * Searches the installed providers for a deferred title and returns the
     * per-provider playback payloads. This is the expensive step that used to
     * run inside load(); now it only runs when the user presses play.
     */
    private suspend fun resolveSources(deferred: NetflixDeferredData): List<NetflixCrossSource> {
        val matches = findMatches(
            deferred.title,
            filterName(deferred.title),
            deferred.year,
            deferred.isTv,
        )

        if (!deferred.isTv) {
            return matches.mapNotNull { match ->
                val movie = match.loaded as? MovieLoadResponse ?: return@mapNotNull null
                NetflixCrossSource(match.providerName, movie.dataUrl)
            }
        }

        val season = deferred.season
        val episode = deferred.episode
        val absolute = deferred.index
        return matches.mapNotNull { match ->
            val series = match.loaded as? TvSeriesLoadResponse ?: return@mapNotNull null
            // Exact season/episode first; providers that list the whole show as
            // one season (anime, telenovelas) are matched by absolute position.
            val exact = if (season != null && episode != null) {
                series.episodes.firstOrNull { it.season == season && it.episode == episode }
            } else {
                null
            }
            val found = exact ?: absolute?.let { position ->
                series.episodes
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                    .getOrNull(position)
            }
            found?.let { NetflixCrossSource(match.providerName, it.data) }
        }
    }

    /**
     * The app shows a generic "No links found" when nothing plays, so explain the
     * otherwise invisible cause: no provider has the title, or every source for
     * it is blocked in the settings panel.
     */
    private fun notifyNoSources(blockedSources: Int, totalSources: Int) {
        val message = when {
            totalSources <= 0 -> "$name: no se encontraron fuentes en tus proveedores."
            blockedSources >= totalSources ->
                "$name: todas las fuentes de este título están bloqueadas en los ajustes."
            else -> return
        }
        runCatching {
            CommonActivity.showToast(message, Toast.LENGTH_LONG)
        }
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String> = emptyMap()): String {
        val key = "$path${params.toSortedMap()}"
        val now = System.currentTimeMillis()
        tmdbCache[key]?.let { if (now - it.timestamp < tmdbCacheMs) return it.body }
        readCacheFile(key, tmdbDiskCacheMs)?.let { body ->
            tmdbCache[key] = TmdbCacheEntry(now, body)
            return body
        }

        val body = app.get(
            url = "$apiBase$path",
            params = buildMap {
                put("api_key", apiKey)
                put("language", "es-MX")
                putAll(params)
            },
        ).text
        tmdbCache[key] = TmdbCacheEntry(now, body)
        // Only page 1 is persisted: that is what the home page shows, and it
        // keeps the cache to a few dozen small files.
        val page = params["page"]
        if (page == null || page == "1") writeCacheFile(key, body)
        return body
    }

    // ------------------------------------------------------------------
    // Top 10 (netflix.com/tudum)
    // ------------------------------------------------------------------

    // The Tudum page is ~2 MB of HTML. Building a Jsoup DOM out of it was the
    // most expensive part of the home page on weak hardware, so the rows are
    // extracted with a regex over the raw text instead.
    private val top10RowPattern =
        Regex("""data-uia="top10-table-row-title"([\s\S]{0,2000}?)</td>""")
    private val top10ImagePattern = Regex("""<img[^>]*?src="([^"]+)"""")
    private val top10TitlePattern = Regex("""<button[^>]*>([^<]+)</button>""")

    // The list only changes once a week; a longer cache keeps the two heavy page
    // downloads off the home page.
    private val top10CacheMs = 12L * 60 * 60 * 1000
    private data class Top10CacheEntry(val timestamp: Long, val rows: List<NetflixTop10Row>)
    private val top10Cache = Collections.synchronizedMap(HashMap<String, Top10CacheEntry>())

    private suspend fun top10Items(url: String): List<SearchResponse> {
        return top10Rows(url).mapNotNull { row ->
            val title = row.title.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // No TMDB id here: that would be one search per row. The item carries
            // only the title and the Netflix poster; load() resolves the id when
            // the row is opened.
            val data = NetflixLatamData(id = null, isTv = false, title = title).toJson()
            newMovieSearchResponse(title, data, TvType.Movie, fix = false) {
                this.posterUrl = row.poster
            }
        }
    }

    private suspend fun top10Rows(url: String): List<NetflixTop10Row> {
        val key = "top10|$url"
        val now = System.currentTimeMillis()
        top10Cache[key]?.let { if (now - it.timestamp < top10CacheMs) return it.rows }

        readCacheFile(key, top10CacheMs)?.let { body ->
            val rows = runCatching {
                parseJson<NetflixTop10Payload>(body).rows.orEmpty()
            }.getOrDefault(emptyList())
            if (rows.isNotEmpty()) {
                top10Cache[key] = Top10CacheEntry(now, rows)
                return rows
            }
        }

        val rows = fetchTop10Rows(url)
        if (rows.isNotEmpty()) {
            top10Cache[key] = Top10CacheEntry(now, rows)
            writeCacheFile(key, NetflixTop10Payload(rows).toJson())
        }
        return rows
    }

    private suspend fun fetchTop10Rows(url: String): List<NetflixTop10Row> {
        return try {
            val html = app.get(
                url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
                referer = "https://www.netflix.com/",
            ).text
            top10RowPattern.findAll(html).take(10).mapNotNull { match ->
                val block = match.groupValues[1]
                val title = top10TitlePattern.find(block)?.groupValues?.get(1)
                    ?.let { unescapeHtml(it) }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NetflixTop10Row(title, top10ImagePattern.find(block)?.groupValues?.get(1))
            }.toList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

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
        // Rows without a TV equivalent are movies only: one request less and no
        // empty series query.
        val genreId = config.tvId ?: return emptyList()
        return parseJson<TmdbNetflixPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "$genreId")
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

    private fun getImageUrl(link: String?, size: String = "w342"): String? {
        link ?: return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$size$link" else link
    }

    private fun currentYear(): Int {
        return Calendar.getInstance().get(Calendar.YEAR)
    }
}
