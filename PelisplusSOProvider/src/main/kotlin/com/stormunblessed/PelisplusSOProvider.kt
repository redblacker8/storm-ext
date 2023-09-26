package com.lagradost.cloudstream3.movieproviders


import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup
import java.util.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.mozilla.javascript.Context


class PelisplusSOProvider : MainAPI() {
    override var mainUrl = "https://pelisplus.so"
    override var name = "Pelisplus.so"
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
            Pair("$mainUrl/series", "Series actualizadas"),
            Pair("$mainUrl/", "Peliculas actualizadas"),
        )
        argamap({
            items.add(
                HomePageList(
                    "Estrenos",
                    app.get(mainUrl).document.select("div#owl-demo-premiere-movies .pull-left")
                        .map {
                            val title = it.selectFirst("p")?.text() ?: ""
                            TvSeriesSearchResponse(
                                title,
                                fixUrl(it.selectFirst("a")?.attr("href") ?: ""),
                                this.name,
                                TvType.Movie,
                                it.selectFirst("img")?.attr("src"),
                                it.selectFirst("span.year").toString().toIntOrNull(),
                                null,
                            )
                        })
            )

            urls.apmap { (url, name) ->
                val soup = app.get(url).document
                val home = soup.select(".main-peliculas div.item-pelicula").map {
                    val title = it.selectFirst(".item-detail p")?.text() ?: ""
                    val titleRegex = Regex("(\\d+)x(\\d+)")
                    TvSeriesSearchResponse(
                        title.replace(titleRegex, ""),
                        fixUrl(it.selectFirst("a")?.attr("href") ?: ""),
                        this.name,
                        TvType.Movie,
                        it.selectFirst("img")?.attr("src"),
                        it.selectFirst("span.year").toString().toIntOrNull(),
                        null,
                    )
                }

                items.add(HomePageList(name, home))
            }
        })

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.html?keyword=${query}"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "X-Requested-With" to "XMLHttpRequest",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Referer" to url,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
        )
        val html = app.get(
            url,
            headers = headers
        ).text
        val document = Jsoup.parse(html)

        return document.select(".item-pelicula.pull-left").map {
            val title = it.selectFirst("div.item-detail p")?.text() ?: ""
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
            val year = it.selectFirst("span.year")?.text()?.toIntOrNull()
            val image = it.selectFirst("figure img")?.attr("src")
            val isMovie = href.contains("/pelicula/")

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    year
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    year,
                    null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document

        val title = soup.selectFirst(".info-content h1")?.text() ?: ""

        val description = soup.selectFirst("span.sinopsis")?.text()?.trim()
        val poster: String? = soup.selectFirst(".poster img")?.attr("src")
        val episodes = soup.select(".item-season-episodes a").map { li ->
            val epTitle = li.selectFirst("a")?.text()
            val href = fixUrl(li.selectFirst("a")?.attr("href") ?: "")
            val seasonid =
                href.replace(Regex("($mainUrl\\/.*\\/temporada-|capitulo-)"), "").replace("/", "-")
                    .let { str ->
                        str.split("-").mapNotNull { subStr -> subStr.toIntOrNull() }
                    }
            val isValid = seasonid.size == 2
            val episode = if (isValid) seasonid.getOrNull(1) else null
            val season = if (isValid) seasonid.getOrNull(0) else null
            Episode(
                href,
                epTitle,
                season = season,
                episode = episode,

                )
        }.reversed()

        val year = Regex("(\\d*)").find(soup.select(".info-half").text())

        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val tags = soup.select(".content-type-a a")
            .map { it?.text()?.trim().toString().replace(", ", "") }
        val duration = Regex("""(\d*)""").find(
            soup.select("p.info-half:nth-child(4)").text()
        )

        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    poster,
                    year.toString().toIntOrNull(),
                    description,
                    ShowStatus.Ongoing,
                    null,
                    tags,
                )
            }

            TvType.Movie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    url,
                    poster,
                    year.toString().toIntOrNull(),
                    description,
                    null,
                    tags,
                    duration.toString().toIntOrNull(),

                    )
            }

            else -> null
        }
    }

    private suspend fun getPelisStream(
        link: String,
        lang: String? = null,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val soup = app.get(link).text
        val m3u8regex = Regex("((https:|http:)\\/\\/.*m3u8(|.*expiry=(\\d+)))")
        val m3u8 = m3u8regex.find(soup)?.value ?: return false

        generateM3u8(
            name,
            m3u8,
            mainUrl,
        ).apmap {
            callback(
                ExtractorLink(
                    name,
                    "$name $lang",
                    it.url,
                    mainUrl,
                    getQualityFromName(it.quality.toString()),
                    true
                )
            )
        }
        return true
    }

    /*  private suspend fun loadExtractor2(
          url: String,
          lang: String,
          referer: String,
          callback: (ExtractorLink) -> Unit,
          subtitleCallback: (SubtitleFile) -> Unit
      ):Boolean {
          for (extractor in extractorApis) {
              if (url.startsWith(extractor.mainUrl)) {
                  extractor.getSafeUrl2(url)?.forEach {
                      extractor.name += " $lang"
                      callback(it)
                  }
              }
          }
          return true
      } */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val elements = listOf(
            Pair("Castellano", "#level2_latino li.tab-video"),
            Pair("Subtitulado", "#level2_subtitulado li.tab-video"),
            Pair("Latino", "#level2_castell li.tab-video"),
        )
        elements.apmap { (lang, element) ->
            document.select(element).apmap {
                val url = fixUrl(it.attr("data-video"))
                if (url.contains("pelisplay.io")) {
                    val doc = app.get(url).document
                    getPelisStream(url, lang, callback)
                    doc.select("ul.list-server-items li").map {
                        val secondurl = fixUrl(it.attr("data-video"))
                        if (secondurl.startsWith("https://azipcdn.com")) {
                            filelionsLoader(secondurl, data, callback, lang)
                        } else if (secondurl.startsWith("https://wishembed.pro")) {
                            streamwishExtractor(secondurl, data, callback, lang)
                        } else {
                            loadExtractor(secondurl, mainUrl, subtitleCallback, callback)
                        }
                    }
                } else if (url.startsWith("https://azipcdn.com")) {
                    filelionsLoader(url, data, callback, lang)
                } else if (url.startsWith("https://wishembed.pro")) {
                    streamwishExtractor(url, data, callback, lang)
                } else {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
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

    private fun stremTest(text: String, callback: (ExtractorLink) -> Unit) {
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

    suspend fun streamwishExtractor(
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
                it.html().contains("jwplayer(\"vplayer\").setup(")
            }
            var scriptContent = script?.html()
            val regex = """sources: \[\{file:"(.*?)"""".toRegex()
            val match = regex.find(scriptContent ?: "")
            val extractedurl = match?.groupValues?.get(1) ?: ""
            if (!extractedurl.isNullOrBlank()) {
                streamClean(
                    "streamwish.to $nameExt",
                    extractedurl,
                    mainUrl,
                    null,
                    callback,
                    extractedurl.contains("m3u8")
                )
            }
        } catch (e: Throwable) {
        }
    }

}
