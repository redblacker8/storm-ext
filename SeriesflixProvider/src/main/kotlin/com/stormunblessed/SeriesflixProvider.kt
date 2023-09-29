package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class SeriesflixProvider : MainAPI() {
    override var mainUrl = "https://seriesflix.lat"
    override var name = "Seriesflix"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/ver-series-online/", "Series"),
            Pair("$mainUrl/genero/accion/", "Acción"),
            Pair("$mainUrl/genero/ciencia-ficcion/", "Ciencia ficción"),
        )
        urls.apmap { (url, name) ->
            val soup = app.get(url).document
            val home = soup.select("article.TPost.B").map {
                val title = it.selectFirst("h2.title")!!.text()
                val link = it.selectFirst("a")!!.attr("href")
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    TvType.Movie,
                    it.selectFirst("figure img")!!.attr("src"),
                    null,
                    null,
                )
            }

            items.add(HomePageList(name, home))
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("article.TPost.B").map {
            val href = it.selectFirst("a")!!.attr("href")
            val poster = it.selectFirst("figure img")!!.attr("src")
            val name = it.selectFirst("h2.title")!!.text()
            val isMovie = href.contains("/movies/")
            if (isMovie) {
                MovieSearchResponse(
                    name,
                    href,
                    this.name,
                    TvType.Movie,
                    poster,
                    null
                )
            } else {
                TvSeriesSearchResponse(
                    name,
                    href,
                    this.name,
                    TvType.TvSeries,
                    poster,
                    null,
                    null
                )
            }
        }.toList()
    }


    override suspend fun load(url: String): LoadResponse {
        val type = if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries

        val document = app.get(url).document

        val title = document.selectFirst("h1.Title")!!.text()
        val descRegex = Regex("(Recuerda.*Seriesflix.)")
        val descipt = document.selectFirst("div.Description > p")!!.text().replace(descRegex, "")
        val rating =
            document.selectFirst("div.Vote > div.post-ratings > span")?.text()?.toRatingInt()
        val year = document.selectFirst("span.Date")?.text()
        // ?: does not work
        val duration = try {
            document.selectFirst("span.Time")!!.text()
        } catch (e: Exception) {
            null
        }
        val postercss = document.selectFirst("head").toString()
        val posterRegex =
            Regex("(\"og:image\" content=\"https://seriesflix.video/wp-content/uploads/(\\d+)/(\\d+)/?.*.jpg)")
        val poster = try {
            posterRegex.findAll(postercss).map {
                it.value.replace("\"og:image\" content=\"", "")
            }.toList().first()
        } catch (e: Exception) {
            document.select(".TPostBg").attr("src")
        }

        if (type == TvType.TvSeries) {
            val list = ArrayList<Pair<Int, String>>()

            document.select("main > section.SeasonBx > div > div.Title > a").forEach { element ->
                val season = element.selectFirst("> span")?.text()?.toIntOrNull()
                val href = element.attr("href")
                if (season != null && season > 0 && !href.isNullOrBlank()) {
                    list.add(Pair(season, fixUrl(href)))
                }
            }
            if (list.isEmpty()) throw ErrorLoadingException("No Seasons Found")

            val episodeList = ArrayList<Episode>()

            list.apmap { (seasonInt, seasonUrl) ->
                val seasonDocument = app.get(seasonUrl).document
                val episodes = seasonDocument.select("table > tbody > tr")
                if (episodes.isNotEmpty()) {
                    episodes.forEach { episode ->
                        val epNum = episode.selectFirst("> td > span.Num")?.text()?.toIntOrNull()
                        val epthumb = episode.selectFirst("img")?.attr("src")
                        val aName = episode.selectFirst("> td.MvTbTtl > a")
                        val name = aName!!.text()
                        val href = aName.attr("href")
                        val date = episode.selectFirst("> td.MvTbTtl > span")?.text()
                        episodeList.add(
                            newEpisode(href) {
                                this.name = name
                                this.season = seasonInt
                                this.episode = epNum
                                this.posterUrl = fixUrlNull(epthumb)
                                addDate(date)
                            }
                        )
                    }
                }
            }
            return TvSeriesLoadResponse(
                title,
                url,
                this.name,
                type,
                episodeList,
                fixUrlNull(poster),
                year?.toIntOrNull(),
                descipt,
                null,
                rating
            )
        } else {
            return newMovieLoadResponse(
                title,
                url,
                type,
                url
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year?.toIntOrNull()
                this.plot = descipt
                this.rating = rating
                addDuration(duration)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("ul.optnslst li div.Button").forEach {
            val urlBase64 = it.attr("data-url")
            val url = base64Decode(urlBase64)
            val doc1 = app.get(url).document
            doc1.select("iframe").apmap {
                val url2 = it.attr("src")
                Log.e("TAG", "loadLinks: $url2")
                loadExtractor(url2, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
