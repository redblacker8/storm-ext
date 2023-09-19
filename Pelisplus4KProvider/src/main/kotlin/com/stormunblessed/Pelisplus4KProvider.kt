package com.stormunblessed

import android.util.Base64
import android.webkit.URLUtil
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.mozilla.javascript.Context
import java.net.URL
import java.net.URLDecoder

class Pelisplus4KProvider : MainAPI() {
    override var mainUrl = "https://ww3.pelisplus.to"
    override var name = "Pelisplus4K"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series")
        )

        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select(".articlesList article").map {
                val title = it.selectFirst("a h2")?.text()
                val link = it.selectFirst("a.itemA")?.attr("href")
                val img = it.selectFirst("picture img")?.attr("data-src")
                TvSeriesSearchResponse(
                    title!!,
                    link!!,
                    this.name,
                    TvType.TvSeries,
                    img,
                )
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?search=$query"
        val doc = app.get(url).document
        return doc.select("article.item").map {
            val title = it.selectFirst("a h2")?.text()
            val link = it.selectFirst("a.itemA")?.attr("href")
            val img = it.selectFirst("picture img")?.attr("data-src")
            TvSeriesSearchResponse(
                title!!,
                link!!,
                this.name,
                TvType.TvSeries,
                img,
            )
        }
    }

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) :
        HashMap<String, List<MainTemporadaElement>>(elements)

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst(".slugh1")?.text() ?: ""
        val backimage = doc.selectFirst("head meta[property=og:image]")!!.attr("content")
        val poster = backimage.replace("original", "w500")
        val description = doc.selectFirst("div.description")!!.text()
        val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }
        val epi = ArrayList<Episode>()
        if (tvType == TvType.TvSeries) {
            var jsonscript = ""
            doc.select("script[type=text/javascript]").mapNotNull { script ->
                val ssRegex = Regex("(?i)seasons")
                val ss = if (script.data().contains(ssRegex)) script.data() else ""
                val swaa = ss.substringAfter("seasonsJson = ").substringBefore(";")
                jsonscript = swaa
            }
            val json = parseJson<MainTemporada>(jsonscript)
            json.values.map { list ->
                list.map { info ->
                    val epTitle = info.title
                    val seasonNum = info.season
                    val epNum = info.episode
                    val img = info.image
                    val realimg =
                        if (img == null) null else if (img.isEmpty() == true) null else "https://image.tmdb.org/t/p/w342${
                            img.replace(
                                "\\/",
                                "/"
                            )
                        }"
                    val epurl = "$url/season/$seasonNum/episode/$epNum"
                    epi.add(
                        Episode(
                            epurl,
                            epTitle,
                            seasonNum,
                            epNum,
                            realimg,
                        )
                    )
                }
            }
        }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, epi,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                }
            }

            else -> null
        }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select(".subselect li").apmap {
            val hashOrURL: String = it.attr("data-server")

            if (URLUtil.isValidUrl(hashOrURL)) {
                loadExtractor(hashOrURL, data, subtitleCallback, callback)
            } else {
                hashLoader(hashOrURL, data, subtitleCallback, callback)
            }

        }
        return true
    }

    suspend fun hashLoader(
        hash: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var bytes = Base64.decode(hash, Base64.DEFAULT)
        val url = String(bytes)
        val doc = app.get(
            url,
            headers = mapOf(
                "Host" to mainUrl.replace("https://", ""),
                "Alt-Used" to mainUrl.replace("https://", ""),
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive",
                "Referer" to data,
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-User" to "?1",
                "TE" to "trailers",
            ),
            allowRedirects = false
        ).document
        var urlContainer = doc.select("script").first()?.html()
        val regex = """window.location.href = '(.*?)'""".toRegex()
        val match = regex.find(urlContainer ?: "")
        val extractedurl = match?.groupValues?.get(1) ?: ""
        if (extractedurl.startsWith("https://filelions.to")) {
            filelionsLoader(extractedurl, data, callback)
        } else if (extractedurl.startsWith("https://streamwish.to")) {
            streamwishLoader(extractedurl, data, callback)
        } else if (extractedurl.startsWith("https://emturbovid.com")) {
            emturbovidLoader(extractedurl, data, callback)
        } else if (extractedurl.startsWith("https://vudeo.co")) {
            vudeoLoader(extractedurl, data, callback)
        } else {
            loadExtractor(extractedurl, data, subtitleCallback, callback)
        }
    }

    suspend fun filelionsLoader(url: String, data: String, callback: (ExtractorLink) -> Unit) {
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
        streamClean(
            "filelions.to",
            finalUrl,
            mainUrl,
            null,
            callback,
            finalUrl.contains("m3u8")
        )
//            stremTest(e.message ?: "", callback)
    }

    suspend fun streamwishLoader(url: String, data: String, callback: (ExtractorLink) -> Unit) {
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
        streamClean(
            "streamwish.to",
            extractedurl,
            mainUrl,
            null,
            callback,
            extractedurl.contains("m3u8")
        )
    }

    suspend fun emturbovidLoader(url: String, data: String, callback: (ExtractorLink) -> Unit) {
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
            it.html().contains("var urlPlay = '")
        }
        var scriptContent = script?.html()
        val regex = """var urlPlay = '(.*?)'""".toRegex()
        val match = regex.find(scriptContent ?: "")
        val extractedurl = match?.groupValues?.get(1) ?: ""
        streamClean(
            "emturbovid.com",
            extractedurl,
            "https://emturbovid.com",
            null,
            callback,
            extractedurl.contains("m3u8")
        )
    }

    suspend fun vudeoLoader(url: String, data: String, callback: (ExtractorLink) -> Unit) {
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
            it.html().contains("var player = new Clappr.Player({")
        }
        var scriptContent = script?.html()
        val regex = """sources: \["(.*?)"""".toRegex()
        val match = regex.find(scriptContent ?: "")
        val extractedurl = match?.groupValues?.get(1) ?: ""
        streamClean(
            "vudeo.co",
            extractedurl,
            "https://vudeo.co",
            null,
            callback,
            false,
        )
    }
}