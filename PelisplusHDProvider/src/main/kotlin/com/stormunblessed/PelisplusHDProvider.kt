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
        val href = this.select("a").attr("href")
        val posterUrl = fixUrl(this.select(".Posters-img").attr("src"))
        val isMovie = href.contains("/pelicula/")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url).document

        return document.select("a.Posters-link").map {
            val title = it.selectFirst(".listing-content p")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
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

        val title = soup.selectFirst(".m-b-5")?.text()
        val description = soup.selectFirst("div.text-large")?.text()?.trim()
        val poster: String? = soup.selectFirst(".img-fluid")?.attr("src")
        val episodes = soup.select("div.tab-pane .btn").map { li ->
            val href = li.selectFirst("a")?.attr("href")
            val name = li.selectFirst(".btn-primary.btn-block")?.text()
                ?.replace(Regex("(T(\\d+).*E(\\d+):)"), "")?.trim()
            val seasoninfo = href?.substringAfter("temporada/")?.replace("/capitulo/", "-")
            val seasonid =
                seasoninfo.let { str ->
                    str?.split("-")?.mapNotNull { subStr -> subStr.toIntOrNull() }
                }
            val isValid = seasonid?.size == 2
            val episode = if (isValid) seasonid?.getOrNull(1) else null
            val season = if (isValid) seasonid?.getOrNull(0) else null
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
            }
        }

        val year = soup.selectFirst(".p-r-15 .text-semibold")?.text()?.toIntOrNull()
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val tags = soup.select(".p-h-15.text-center a span.font-size-18.text-info.text-semibold")
            .map { it?.text()?.trim().toString().replace(", ", "") }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title!!, url, tvType, episodes) {
                    this.posterUrl = fixUrl(poster!!)
                    this.year = year
                    this.tags = tags
                    this.plot = description
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title!!, url, tvType, url) {
                    this.posterUrl = fixUrl(poster!!)
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
        app.get(data).document.select("script")
            .firstOrNull { it.html().contains("var video = [];") }?.html().let { script ->
                fetchUrls(
                    script
                )
                    .amap {
                        if (it.startsWith("https://embed69.org/")) {
                            Embed69Extractor.load(it, data, subtitleCallback, callback)
                        } else if (it.startsWith("https://xupalace.org/video")) {
                            val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                            regex.findAll(app.get(it).document.html()).map { it.groupValues.get(2) }
                                .toList().amap {
                                    loadSourceNameExtractor(
                                        lang,
                                        it,
                                        data,
                                        subtitleCallback,
                                        callback
                                    )
                                }
                        } else { // https://xupalace.org/uqlink.php or others
                            app.get(it).document.selectFirst("iframe")?.attr("src")?.let { src ->
                                loadSourceNameExtractor(lang, src, data, subtitleCallback, callback)
                            }
                        }

                    }
            }
        return true
    }
}
