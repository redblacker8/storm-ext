package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Collections

/**
 * Serialized data stored in each [SearchResponse] of the provider.
 * The title is the localized one (es-MX), which is the term used by the
 * top-bar search button (QuickSearch) to look for sources across the rest
 * of the installed providers.
 */
data class CatalogoData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

data class TmdbCatalogoResult(
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

data class TmdbCatalogoPageResult(
    @JsonProperty("results") val results: List<TmdbCatalogoResult>? = null,
)

data class TmdbCatalogoDetails(
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
    @JsonProperty("genres") val genres: List<TmdbCatalogoGenre>? = null,
) {
    val displayTitle get() = title ?: name ?: originalTitle ?: originalName ?: ""
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbCatalogoGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class CrossMetaData(
    @JsonProperty("isSuccess") val isSuccess: Boolean,
    @JsonProperty("sources") val sources: List<CrossSource>? = null,
)

data class CrossSource(
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("dataUrl") val dataUrl: String,
)

/**
 * Catalogo Infantil: meta provider for discovering children's and family
 * content (Mexican AA classification). On load, it searches other installed
 * providers (same language) for the title and, if found, delegates playback
 * to them. Movies get a Play button via CrossMetaData; TV series get the
 * matched provider's episodes list. If no match is found, the detail shows
 * metadata only (comingSoon) with a search button via QuickSearch.
 */
class CatalogoInfantilProvider : MainAPI() {
    override var name = "Catálogo Infantil"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "mx"
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
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

    private val validApis
        get() = apis.filter { it.lang == this.lang && it::class != this::class }

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

    // Short-lived cache for TMDB responses so that reloading the home page
    // (CloudStream refetches every section at once) is instant instead of
    // hammering TMDB with many parallel requests.
    private val tmdbCacheMs = 60L * 60 * 1000
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

    private suspend fun searchProvider(
        api: MainAPI,
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): SearchMatch? {
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

                val loaded = api.load(matched.url) ?: return@withTimeout null
                SearchMatch(api.name, loaded)
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
        val key = "$isTv|$matchName|$searchYear"
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.timestamp < searchCacheMs) return it.matches }

        // All providers are hit in parallel so that the wall-clock time is the
        // slowest provider, not the sum of all of them. The global deadline
        // bounds the whole search (e.g. when the title matches nothing and
        // every provider has to answer first).
        var complete = true
        val matches = withTimeoutOrNull(findMatchesDeadlineMs) {
            validApis.amap { api ->
                searchProvider(api, title, matchName, searchYear, isTv)
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

    // Only the Mexican AA classification (under 7 years old): the exact
    // `certification` filter avoids titles without a registered MX certification
    // and, by not including "A", excludes non-kids content that does have it
    // (e.g. La Momia, Friends, Jesus 1979).
    private val kidsCertification = "AA"
    private val certificationCountry = "MX"

    // Kids genres for series: Family, Kids and Animation.
    // Comma (,) in TMDB's with_genres means AND: it works for series, where
    // almost all kids content combines the three genres.
    private val tvKidsGenres = "10751,10762,16"

    // For movies pipe (|) = OR is used: almost no movie has all three genres at
    // once (AND returns 0 results). OR + AA discards non-kids AA titles
    // (e.g. documentaries, dramas, concerts).
    private val movieKidsGenres = "10751|10762|16"

    // Minimum votes for movies: discards AA titles with 0-1 votes that are junk
    // or unknown regional releases (e.g. "Maruchan con Huevo").
    // Real kids content (Toy Story, Inside Out) accumulates thousands of votes.
    private val movieMinVotes = 25

    // Genre rows. The row genre is combined with the kids genres so that
    // non-kids titles of that genre are excluded.
    private data class GenreSection(val id: Int, val sortBy: String, val minMovieVotes: Int)

    // Animation is the genre with by far the most kids content, so it gets the
    // only genre row that also has a series counterpart. Adventure is the most
    // distinct non-animation genre; TMDB's series query for it (genre AND the
    // kids genres) returns nothing, so it is a movie-only row.
    private val animacionSection = GenreSection(16, "popularity.desc", movieMinVotes)
    private val aventuraSection = GenreSection(12, "popularity.desc", movieMinVotes)

    // A row shows at most this many cards: the rest of the TMDB page is dropped
    // because the UI never scrolls that far and every card is an image to load.
    private val maxRowItems = 24

    private fun mainPageKey(result: TmdbCatalogoResult): String =
        "${result.isTv}-${result.id ?: result.displayTitle}"

    // Within-row dedupe only. CloudStream fetches every section in parallel, so a
    // cross-row "already seen" set would be filled by whichever section finished
    // first (not by the first row of the list), which emptied rows or replaced
    // their content with repeats nondeterministically.
    private fun rowResults(raw: List<TmdbCatalogoResult>): List<TmdbCatalogoResult> =
        raw.distinctBy { mainPageKey(it) }.take(maxRowItems)

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
    // fired in parallel so the row costs one round trip instead of two. The
    // cards alternate movie/series; a plain concatenation would put 20 movies
    // first and push the series off the first screen.
    private suspend fun <T> bothRows(
        movies: suspend () -> List<T>,
        series: suspend () -> List<T>,
    ): List<T> = supervisorScope {
        val movieRow = async { safeRow(movies) }
        val seriesRow = async { safeRow(series) }
        interleave(movieRow.await(), seriesRow.await())
    }

    override val mainPage = listOf(
        mainPage("tendencias", "Tendencias", false),
        mainPage("peliculas", "Películas", false),
        mainPage("series", "Series", false),
        mainPage("animacion", "Animación", false),
        mainPage("aventura", "Aventura", false),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Cards are vertical with a portrait (poster) image.
        val horizontal = false

        val raw = when (request.data) {
            "tendencias" ->
                // Fresh releases don't have an MX AA certification registered on
                // TMDB yet, so the certification filter is skipped for this row:
                // applying it to the current-year window returns nothing. The
                // vote filter keeps junk (0-vote regional releases) out without
                // requiring the high vote count of the "best rated" rows.
                // Kids series take longer to accumulate votes, so their window is
                // wider (2 years): a current-year window fills the row with
                // 0-vote placeholders.
                bothRows(
                    {
                        discoverMovies(
                            page, "popularity.desc",
                            recentYears = 0, applyCert = false, minVoteCount = 50,
                        )
                    },
                    {
                        discoverSeries(
                            page, "popularity.desc",
                            recentYears = 2, applyCert = false, minVoteCount = 10,
                        )
                    },
                )

            "peliculas" -> discoverMovies(page, "vote_average.desc", minVoteCount = 150)
            "series" -> discoverSeries(page, "vote_average.desc", minVoteCount = 100)

            "animacion" -> bothRows(
                { genreMovies(page, animacionSection) },
                { genreSeries(page, animacionSection) },
            )

            "aventura" -> genreMovies(page, aventuraSection)

            else -> emptyList()
        }

        val results = rowResults(raw).mapNotNull { it.toCatalogoSearchResponse(horizontal) }

        return newHomePageResponse(
            HomePageList(request.name, results, horizontal),
            hasNext = results.isNotEmpty()
        )
    }

    // The provider's own search is deliberately disabled:
    // the catalog only discovers content on the main page. The source search is
    // done from the detail with the magnifier (the app's QuickSearch).
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<CatalogoData>(url) ?: return null
        val id = data.id ?: return null

        val details = if (data.isTv) {
            parseJson<TmdbCatalogoDetails>(tmdbGet("/tv/$id"))
        } else {
            parseJson<TmdbCatalogoDetails>(tmdbGet("/movie/$id"))
        }

        val title = details.displayTitle.takeIf { it.isNotBlank() } ?: return null
        val type = if (data.isTv) TvType.TvSeries else TvType.Movie
        val year = data.year ?: details.year
        val poster = getImageUrl(details.posterPath)
        val backdrop = getImageUrl(details.backdropPath, "w780")
        val score = Score.from10(details.voteAverage)
        val tags = details.genres?.mapNotNull { it.name }

        // Search other providers for this title and collect playable data.
        // All providers are hit in parallel under a short global deadline so
        // that load() stays fast even when many providers are installed —
        // CloudStream calls load() for the home page preview row, so a slow
        // load() stalls the main page.
        val matchName = filterName(title)
        val searchYear = year
        val matches = findMatches(title, matchName, searchYear, data.isTv)

        if (matches.isEmpty()) {
            // No match found in other providers: return metadata-only (comingSoon)
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

        // Use the FIRST matched provider's response, overlay TMDB metadata
        val first = matches.first()

        return when (val loaded = first.loaded) {
            is MovieLoadResponse -> {
                // Store all matches in CrossMetaData for loadLinks delegation
                val movieMatches = matches.filter { it.loaded is MovieLoadResponse }
                val crossData = CrossMetaData(
                    true,
                    movieMatches.map { CrossSource(it.providerName, (it.loaded as MovieLoadResponse).dataUrl) }
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
                    val sources = mutableListOf(CrossSource(first.providerName, ep.data))
                    seriesMatches.forEach { other ->
                        if (other.providerName == first.providerName) return@forEach
                        val otherLoaded = other.loaded as TvSeriesLoadResponse
                        otherLoaded.episodes.firstOrNull {
                            it.season == ep.season && it.episode == ep.episode
                        }?.let { sources.add(CrossSource(other.providerName, it.data)) }
                    }
                    val wrapped = CrossMetaData(true, sources).toJson()
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
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linksFound = true
            callback(link)
        }
        // Try CrossMetaData first (movies — contains list of sources)
        tryParseJson<CrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            metaData.sources?.amap { source ->
                getApiFromNameNull(source.apiName)?.let {
                    try {
                        it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            return linksFound
        }
        // Try CrossSource (TV episodes — single source wrapping episode data)
        tryParseJson<CrossSource>(data)?.let { source ->
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
        recentYears: Int? = null,
        minVoteCount: Int? = movieMinVotes,
        applyCert: Boolean = true,
    ): List<TmdbCatalogoResult> {
        return parseJson<TmdbCatalogoPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_genres", movieKidsGenres)
                    if (applyCert) {
                        put("certification_country", certificationCountry)
                        put("certification", kidsCertification)
                    }
                    recentYears?.let { put("primary_release_date.gte", yearFloor(it)) }
                    minVoteCount?.let { put("vote_count.gte", "$it") }
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun discoverSeries(
        page: Int,
        sortBy: String,
        recentYears: Int? = null,
        minVoteCount: Int? = null,
        applyCert: Boolean = true,
    ): List<TmdbCatalogoResult> {
        return parseJson<TmdbCatalogoPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", sortBy)
                    put("with_genres", tvKidsGenres)
                    if (applyCert) {
                        put("certification_country", certificationCountry)
                        put("certification", kidsCertification)
                    }
                    recentYears?.let { put("first_air_date.gte", yearFloor(it)) }
                    minVoteCount?.let { put("vote_count.gte", "$it") }
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun genreMovies(page: Int, config: GenreSection): List<TmdbCatalogoResult> {
        return parseJson<TmdbCatalogoPageResult>(
            tmdbGet(
                "/discover/movie",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    put("with_genres", "${config.id}")
                    put("certification_country", certificationCountry)
                    put("certification", kidsCertification)
                    put("vote_count.gte", "${config.minMovieVotes}")
                }
            )
        ).results ?: emptyList()
    }

    private suspend fun genreSeries(page: Int, config: GenreSection): List<TmdbCatalogoResult> {
        return parseJson<TmdbCatalogoPageResult>(
            tmdbGet(
                "/discover/tv",
                buildMap {
                    put("page", "$page")
                    put("sort_by", config.sortBy)
                    // TMDB's certification filter does not apply to TV, so the
                    // section requires the row genre PLUS the kids genres (AND)
                    // to exclude unsuitable sitcoms (e.g. Friends, Two and a
                    // Half Men).
                    put("with_genres", "${config.id},$tvKidsGenres")
                    put("certification_country", certificationCountry)
                    put("certification", kidsCertification)
                }
            )
        ).results ?: emptyList()
    }

    private fun TmdbCatalogoResult.toCatalogoSearchResponse(
        preferLandscape: Boolean = false
    ): SearchResponse? {
        val display = displayTitle
        if (display.isBlank()) return null
        val id = this.id ?: return null

        // The card name is the localized title (es-MX): avoids showing titles in
        // their original language (e.g. anime in Japanese/Chinese) on the main page
        // and matches what the detail shows. It is also the term used by
        // QuickSearch in the installed mx/es providers.
        val data = CatalogoData(id, isTv, display, year).toJson()

        // Vertical cards use the portrait (poster) image.
        val poster = if (preferLandscape) {
            getImageUrl(backdropPath ?: posterPath, "w780")
        } else {
            getImageUrl(posterPath)
        }

        return if (isTv) {
            newTvSeriesSearchResponse(display, data, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = this@toCatalogoSearchResponse.year
                this.score = Score.from10(voteAverage)
            }
        } else {
            newMovieSearchResponse(display, data, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = this@toCatalogoSearchResponse.year
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

    // Start of the window used by the "recent" rows: 0 = January 1st of the
    // current year, 2 = two years back.
    private fun yearFloor(yearsBack: Int): String {
        return "${currentYear() - yearsBack}-01-01"
    }
}
