package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.mozilla.javascript.Context

class CuevanaProvider : MainAPI() {
    override var mainUrl = "https://cuevana.biz"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.Movie,
            TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
                Pair("$mainUrl/peliculas", "Peliculas"),
                Pair("$mainUrl/series", "Series"),
        )
        urls.apmap { (url, name) ->
            val soup = app.get(url).document
            val home = soup.select("html body div#__next div.body div#aa-wp div.bd div.bd div.TpRwCont.cont main section.home-movies div.apt ul.MovieList.Rows li.TPostMv").map {
                val title = it.selectFirst("div.TPost.C.hentry a span.Title.block")!!.text()
                val link = "$mainUrl${it.selectFirst("a")!!.attr("href")}"
                TvSeriesSearchResponse(
                        title,
                        link,
                        this.name,
                        if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
                        "$mainUrl${it.selectFirst("div.TPost.C.hentry a div.Image img")!!.attr("src")}",
                        it.selectFirst("div.TPost.C.hentry a div.Image span.Year")!!.text().toIntOrNull(),
//                        null,
                )
            }

            items.add(HomePageList(name, home))
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query}"
        val document = app.get(url).document

        return document.select("li.TPostMv").map {
            val title = it.selectFirst("span.Title")!!.text()
            val href = "$mainUrl${it.selectFirst("a")!!.attr("href")}"
            val image = "$mainUrl${it.selectFirst("img")!!.attr("src")}"
            val isSerie = href.contains("/serie/")

            if (isSerie) {
                TvSeriesSearchResponse(
                        title,
                        href,
                        this.name,
                        TvType.TvSeries,
                        image,
                        null,
                        null
                )
            } else {
                MovieSearchResponse(
                        title,
                        href,
                        this.name,
                        TvType.Movie,
                        image,
                        null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document
        val title = soup.selectFirst("h1.Title")!!.text()
        val description = soup.selectFirst(".Description p")?.text()?.trim()
        val poster: String? = soup.selectFirst("html body div#__next div.body.slider div#aa-wp div.bd div.backdrop article.TPost.movtv-info.cont div.Image img")!!.attr("src")
                .replace(Regex("\\/p\\/w\\d+.*\\/"), "/p/original/")
        val backgrounposter = "$mainUrl${soup.selectFirst("html body div#__next div.body.slider div#aa-wp div.bd div.backdrop div.Image img")!!.attr("src")}"
                .replace("\\/\\/", "/")
        val year1 = soup.selectFirst("footer p.meta").toString()
        val yearRegex = Regex("<span>(\\d+)</span>")
        val yearf =
                yearRegex.find(year1)?.destructured?.component1()?.replace(Regex("<span>|</span>"), "")
        val year = if (yearf.isNullOrBlank()) null else yearf.toIntOrNull()
        val episodes = soup.select(".all-episodes li.TPostMv article").map { li ->
            val href = "$mainUrl${li.select("a").attr("href") }"
            val epThumb =
                    li.selectFirst("div.Image img")?.attr("data-src")
                            ?: li.selectFirst("img.lazy")!!
                                    .attr("data-srcc").replace(Regex("\\/w\\d+\\/"), "/w780/")
            val seasonid = li.selectFirst("span.Year")!!.text().let { str ->
                str.split("x").mapNotNull { subStr -> subStr.toIntOrNull() }
            }
            val isValid = seasonid.size == 2
            val episode = if (isValid) seasonid.getOrNull(1) else null
            val season = if (isValid) seasonid.getOrNull(0) else null
            Episode(
                    href,
                    null,
                    season,
                    episode,
                    fixUrl(epThumb)
            )
        }
        val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a").map { it.text() }
        val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
        val recelement =
                if (tvType == TvType.TvSeries) "main section div.series_listado.series div.xxx"
                else "main section ul.MovieList li"
        val recommendations =
                soup.select(recelement).mapNotNull { element ->
                    val recTitle = element.select("h2.Title").text() ?: return@mapNotNull null
                    val image = element.select("figure img")?.attr("data-src")
                    val recUrl = fixUrl(element.select("a").attr("href"))
                    MovieSearchResponse(
                            recTitle,
                            recUrl,
                            this.name,
                            TvType.Movie,
                            image,
                            year = null
                    )
                }
        val trailer = soup.selectFirst("div.TPlayer.embed_div div[id=OptY] iframe")?.attr("data-src")
                ?: ""


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                        title,
                        url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgrounposter
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.backgroundPosterUrl = backgrounposter
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                    if (trailer.isNotBlank()) addTrailer(trailer)
                }

            }

            else -> null
        }
    }

    data class Femcuevana(
            @JsonProperty("url") val url: String,
    )

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("html body div#__next div.body.slider div#aa-wp div.bd div.video.cont ul._1EGcQ_0.TPlayerNv.tab_language_movie li.open_submenu.active.actives").apmap {
            val lang = it.selectFirst("div._3CT5n_0.L6v6v_0 div._1R6bW_0 span")!!.ownText()
            it.select("div ul.sub-tab-lang._3eEG3_0.optm3 li.clili.L6v6v_0").apmap {
                val playerUrl = fixUrl(it.attr("data-tr"))
                val playerHtml = app.get(playerUrl, allowRedirects = false)
                fetchUrls(playerHtml.document.selectFirst("html body script")!!.html()).apmap {
                    if (it.startsWith("https://filelions.to")) {
                        filelionsLoader(it, data, callback, lang)
                    } else if (it.startsWith("https://doodstream.com")) {
                        doodstreamExtractor(it, data, callback, lang)
                    } else {
                        loadExtractor(it, data, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private fun streamClean(
            name: String,
            url: String,
            referer: String,
            quality: String?,
            callback: (ExtractorLink) -> Unit,
            m3u8: Boolean
    ): Boolean {
        callback(
                ExtractorLink(
                        name,
                        name,
                        url,
                        referer,
                        getQualityFromName(quality),
                        m3u8
                )
        )
        return true
    }

    private fun streamTest(text: String, callback: (ExtractorLink) -> Unit) {
        val testUrl = "https://rt-esp.rttv.com/live/rtesp/playlist.m3u8"
        streamClean(
                text,
                testUrl,
                mainUrl,
                null,
                callback,
                testUrl.contains("m3u8")
        )
    }

    suspend fun filelionsLoader(
            url: String,
            data: String,
            callback: (ExtractorLink) -> Unit,
            nameExt: String = ""
    ) {
        try {
            val doc = app.get(
                    url,
                    headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                            "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                            "Connection" to "keep-alive",
                            "Referer" to data,
                            "Sec-Fetch-Dest" to "iframe",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-User" to "?1",
                            "Upgrade-Insecure-Requests" to "1",
                    ),
                    allowRedirects = false
            ).document
            var script = doc.select("script").find {
                it.html().contains("eval(function(p,a,c,k,e,d)")
            }
            var scriptContent = script?.html()
            var cx = Context.enter()
            cx.optimizationLevel = -1
            var scope = cx.initStandardObjects()
            cx.evaluateString(
                    scope, """
                                    var $
                                    $ = {
                                        ajaxSetup: () => {
                                            $ = () => ({on: () => null}) 
                                        }
                                    }
                                    var init = {}
                                    var jwplayer = function(info){
                                        return {
                                            setup: (data) => init = data,
                                            on: (name,callback) => null,
                                            geturl: () => init.sources[0].file,
                                            addButton: () => null,
                                            seek: () => null,
                                            getPosition: () => null,
                                        }
                                    }
                                """.trimIndent(), "script1", 1, null
            )
            cx.evaluateString(scope, scriptContent, "script2", 1, null)
            var result = cx.evaluateString(scope, "init.sources[0].file", "script3", 1, null)
            var finalUrl = result.toString()
            if (!finalUrl.isNullOrBlank()) {
                streamClean(
                        "filelions.to $nameExt",
                        finalUrl,
                        mainUrl,
                        null,
                        callback,
                        finalUrl.contains("m3u8")
                )
            }
        } catch (e: Throwable) {
        }
    }

    suspend fun doodstreamExtractor(
        url: String,
        data: String,
        callback: (ExtractorLink) -> Unit,
        nameExt: String = ""
    ) {
        try {
            val result = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                    "Connection" to "keep-alive",
                    "Referer" to data,
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1",
                ),
                allowRedirects = true
            )
            val htmlContent = result.document.html()
            val referer = result.url
            val regex = """'(/pass_md5/.*?)'""".toRegex()
            val match = regex.find(htmlContent)
            val endpoint = match?.groupValues?.get(1) ?: ""
            val baseurl = app.get(
                "https://ds2play.com" + endpoint,
                headers = mapOf(
                    "Host" to "ds2play.com",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Connection" to "keep-alive",
                    "Referer" to referer,
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1",
                )
            ).document.text()

            fun makePlay(): String {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                val charsLength = chars.length
                var result = ""
                for (i in 0..9) {
                    val randomIndex = (Math.random() * charsLength).toInt()
                    result += chars[randomIndex]
                }
                val now = System.currentTimeMillis() + 5000
                return "$result?token=sfqpjbi1vlvjr16o02wb7wa8&expiry=$now"
            }

            val extractedUrl = baseurl + makePlay()
            streamClean(
                "doodstream.com $nameExt",
                extractedUrl,
                "https://ds2play.com/",
                null,
                callback,
                false
            )
        } catch (e: Throwable) {
        }
    }
}

