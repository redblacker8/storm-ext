package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.stormunblessed.Embed69Extractor
import com.stormunblessed.loadSourceNameExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class EntrepeliculasyseriesProvider : MainAPI() {
    private val cloudflareKiller = CloudflareKiller()

    private suspend fun appGetCf(url: String): NiceResponse {
        return app.get(url, interceptor = cloudflareKiller)
    }

    override var mainUrl = "https://entrepeliculasyseries.nz"
    override var name = "EntrePeliculasySeries"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded //Due to evoload sometimes not loading

    override val mainPage = mainPageOf(
        "series" to "Series",
        "peliculas" to "Peliculas",
        "animes" to "Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = appGetCf("$mainUrl/${request.data}?page=$page").document
        val home = document.select(".post-lst li")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("article.post a header.entry-header .title").text()
        val href =
            this.select("article.post a").attr("href").replaceFirst("^/".toRegex(), "$mainUrl/")
        val posterUrl =
            fixUrlNull(this.select("article.post a figure.post-thumbnail img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = appGetCf("${mainUrl}/search?s=$query").document
        val results =
            document.select(".post-lst li").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = appGetCf(url).document
        val tvType = if (url.contains("/pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst(".movie-title")?.text()
        val plot = doc.selectFirst(".movie-description")?.text()
        val year = doc.select(".movie-meta > span").mapNotNull { span ->
            val text = span.text().trim()
            text.toIntOrNull() ?: text.take(4).toIntOrNull()
        }.firstOrNull { it in 1900..2100 }
        val poster = doc.selectFirst(".movie-poster img")?.attr("src")
        val tags = doc.select(".movie-genres a").map { it.text() }
        val recommendations = doc.select(".post-lst li").mapNotNull { it.toSearchResult() }
        val episodes = doc.select("div.episodes-grid").flatMap { grid ->
            grid.select(".episode-card a").mapNotNull { link ->
                val season = link.attr("data-season").toIntOrNull()
                val episode = link.attr("data-episode").toIntOrNull()
                val epUrl = url.trimEnd('/') + "#s${season}-e$episode"
                newEpisode(epUrl) {
                    this.season = season
                    this.episode = episode
                }
            }
        }
        return when (tvType) {
            TvType.Movie -> newMovieLoadResponse(title!!, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }

            TvType.TvSeries -> newTvSeriesLoadResponse(
                title!!,
                url, tvType, episodes,
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
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
        // Series/anime episodes use hash URLs: {mainUrl}/{type}/{slug}#s{season}-e{episode}
        val hashMatch = Regex("""#s(\d+)-e(\d+)""").find(data)
        if (hashMatch != null) {
            val season = hashMatch.groupValues[1]
            val episode = hashMatch.groupValues[2]
            val base = data.substringBefore("#")
            val typeSlug = Regex("""$mainUrl/(serie|anime|pelicula)/([^/]+)""").find(base)
            if (typeSlug != null) {
                val type = typeSlug.groupValues[1]
                val slug = typeSlug.groupValues[2]
                val resp = appGetCf("$mainUrl/api/episodio/$type/$slug/$season/$episode")
                    .parsedSafe<EpisodeApiResponse>()
                val html = resp?.html
                if (!html.isNullOrBlank()) {
                    val doc = Jsoup.parse(html)
                    // Each server is a player-tab button whose data-url points to a /vidurl/ page
                    val tabs = doc.select("button.player-tab[data-url]")
                    if (tabs.isNotEmpty()) {
                        tabs.amap { btn ->
                            val vidUrl = btn.attr("data-url")
                            val fullUrl = if (vidUrl.startsWith("/")) "$mainUrl$vidUrl" else vidUrl
                            val vidDoc = appGetCf(fullUrl).document
                            Embed69Extractor.loadFromDocument(vidDoc, fullUrl, subtitleCallback, callback)
                        }
                    } else {
                        // Fallback: single iframe in the returned HTML
                        doc.select("iframe#player-iframe").amap { iframe ->
                            val vidUrl = iframe.attr("src")
                            val fullUrl = if (vidUrl.startsWith("/")) "$mainUrl$vidUrl" else vidUrl
                            val vidDoc = appGetCf(fullUrl).document
                            Embed69Extractor.loadFromDocument(vidDoc, fullUrl, subtitleCallback, callback)
                        }
                    }
                    return true
                }
            }
        }

        // Movies (direct URL) — keep existing player-frame extraction
        appGetCf(data).document.select("div.player-frame").amap {
            it.selectFirst("iframe")?.attr("src")?.let { src ->
                val embedUrl = if (src.startsWith("/")) "$mainUrl$src" else src
                when {
                    embedUrl.startsWith("https://embed69.org/") -> {
                        Embed69Extractor.load(embedUrl, data, subtitleCallback, callback)
                    }
                    embedUrl.startsWith("$mainUrl/") -> {
                        val doc = appGetCf(embedUrl).document
                        Embed69Extractor.loadFromDocument(doc, embedUrl, subtitleCallback, callback)
                    }
                    embedUrl.startsWith("https://xupalace.org/video") -> {
                        val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                        regex.findAll(app.get(embedUrl).document.html()).map { it.groupValues.get(2) }
                            .toList().amap {
                                loadSourceNameExtractor(lang, it, data, subtitleCallback, callback)
                            }
                    }
                    else -> {
                        app.get(embedUrl).document.selectFirst("iframe")?.attr("src")?.let { src ->
                            loadSourceNameExtractor(lang, src, data, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
        return true
    }

    data class EpisodeApiResponse(
        @JsonProperty("html") val html: String? = null,
    )
}
