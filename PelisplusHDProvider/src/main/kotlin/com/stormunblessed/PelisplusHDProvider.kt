package com.lagradost.cloudstream3.movieproviders

import android.webkit.URLUtil
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.stormunblessed.Embed69Extractor
import com.stormunblessed.loadSourceNameExtractor
import org.jsoup.nodes.Element

class PelisplusHDProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.bz"
    override var name = "PelisplusHD"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val document = app.get(mainUrl).document
        val map = mapOf(
            "Películas" to "#default-tab-1",
            "Series" to "#default-tab-2",
            "Anime" to "#default-tab-3",
            "Doramas" to "#default-tab-4",
        )
        map.forEach {
            items.add(
                HomePageList(
                it.key,
                document.select(it.value).select("a.Posters-link").map { element ->
                    element.toSearchResult()
                }
            ))
        }
        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".listing-content p").text()
        val href = this.attr("href")
        val posterUrl = fixUrl(this.select(".Posters-img").attr("src"))
        val isMovie = href.contains("/pelicula/")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url).document

        return document.select("a.Posters-link").map {
            val title = it.selectFirst(".listing-content p")!!.text()
            val href = it.attr("href")
            val image = it.selectFirst(".Posters-img")?.attr("src")?.let { it1 -> fixUrl(it1) }
            val isMovie = href.contains("/pelicula/")

            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = image
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = image
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document

        // Title: h1.fiche-title, strip the trailing year like "(2020)"
        val titleEl = soup.selectFirst("h1.fiche-title")
        val rawTitle = titleEl?.text()?.trim()
        val title = rawTitle?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")?.trim()
            ?: titleEl?.text()?.trim()

        val description = soup.selectFirst(".fiche-synopsis")?.text()?.trim()

        val poster = soup.selectFirst(".fiche-poster img")?.attr("src")
            ?.let { fixUrl(it) }

        // Year from span.fiche-title-year: "(2020)" -> 2020
        val yearText = soup.selectFirst(".fiche-title-year")?.text()
        val year = yearText?.replace(Regex("[()]"), "")?.toIntOrNull()

        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries

        // Genres: a.item-meta-tag elements (only the text label)
        val tags = soup.select("a.item-meta-tag").mapNotNull { it.text()?.trim() }

        return when (tvType) {
            TvType.TvSeries -> {
                // Extract episodes from #episodios .tab-pane .episode-link elements
                val episodeLinks = soup.select("#episodios .episode-link")
                val episodes = episodeLinks.mapNotNull { link ->
                    val season = link.attr("data-season")?.toIntOrNull()
                    val episode = link.attr("data-episode")?.toIntOrNull()
                    val name = link.text()?.trim()
                    if (season == null || episode == null) return@mapNotNull null

                    // Build episode API endpoint: {seriesUrl}/episodio/{season}/{episode}
                    val baseUrl = url.trimEnd('/')
                    val epUrl = "${baseUrl}/episodio/${season}/${episode}"

                    newEpisode(epUrl) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                }

                newTvSeriesLoadResponse(title!!, url, tvType, episodes) {
                    this.posterUrl = poster ?: ""
                    this.year = year
                    this.tags = tags
                    this.plot = description
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title!!, url, tvType, url) {
                    this.posterUrl = poster ?: ""
                    this.year = year
                    this.plot = description
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
        if (data.contains("/episodio/")) {
            // Series episode — fetch API endpoint, parse JSON for server URLs
            val responseText = app.get(data).text
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            try {
                val json = mapper.readTree(responseText)
                val servers = json?.get("data")?.get("servers")
                if (servers != null) {
                    for (server in servers) {
                        val videoUrl = server?.get("url")?.asText() ?: continue
                        routeVideo(videoUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Fallback: try regex in case JSON parsing fails
                val urlRegex = """"url"\s*:\s*"(https?[^"]*)"""".toRegex()
                val matches = urlRegex.findAll(responseText).toList()
                for (match in matches) {
                    val videoUrl = match.groupValues.get(1).replace("\\/", "/")
                    routeVideo(videoUrl, data, subtitleCallback, callback)
                }
            }
        } else {
            // Movie page — extract from var video = [] script
            app.get(data).document.select("script")
                .firstOrNull { it.html().contains("var video = [];") }?.html().let { script ->
                    fetchUrls(script)
                        .amap {
                            routeVideo(it, data, subtitleCallback, callback)
                        }
                }
        }
        return true
    }

    private suspend fun routeVideo(
        videoUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (videoUrl.startsWith("https://embed69.org/")) {
            Embed69Extractor.load(videoUrl, data, subtitleCallback, callback)
        } else if (videoUrl.startsWith("https://xupalace.org/video")) {
            val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
            val page = app.get(videoUrl).document.html()
            val matches = regex.findAll(page).toList()
            for (mt in matches) {
                val target = mt.groupValues.get(2)
                loadSourceNameExtractor(lang, target, data, subtitleCallback, callback)
            }
        } else {
            // https://xupalace.org/uqlink.php or other servers
            val iframeSrc = app.get(videoUrl).document.selectFirst("iframe")?.attr("src")
            if (iframeSrc != null) {
                loadSourceNameExtractor(lang, iframeSrc, data, subtitleCallback, callback)
            }
        }
    }
}