package com.lagradost.cloudstream3.animeproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.mozilla.javascript.Context
import java.util.*


class DoramasYTProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.Movie
            else TvType.AsianDrama
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://www.doramasyt.com"
    override var name = "DoramasYT"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/emision", "En emisión"),
            Pair(
                "$mainUrl/doramas?categoria=pelicula&genero=false&fecha=false&letra=false",
                "Peliculas"
            ),
            Pair("$mainUrl/doramas", "Doramas"),
            Pair(
                "$mainUrl/doramas?categoria=live-action&genero=false&fecha=false&letra=false",
                "Live Action"
            ),
        )

        val items = ArrayList<HomePageList>()
        var isHorizontal = true
        items.add(
            HomePageList(
                "Capítulos actualizados",
                app.get(mainUrl, timeout = 120).document.select(".col-6").map {
                    val title = it.selectFirst("p")!!.text()
                    val poster = it.selectFirst(".chapter img")!!.attr("src")
                    val epRegex = Regex("episodio-(\\d+)")
                    val url = it.selectFirst("a")!!.attr("href").replace("ver/", "dorama/")
                        .replace(epRegex, "sub-espanol")
                    val epNum = it.selectFirst("h3")!!.text().toIntOrNull()
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = fixUrl(poster)
                        addDubStatus(getDubStatus(title), epNum)
                    }
                }, isHorizontal
            )
        )

        urls.apmap { (url, name) ->
            val posterdoc = if (url.contains("/emision")) "div.animes img" else ".anithumb img"
            val home = app.get(url).document.select(".col-6").map {
                val title = it.selectFirst(".animedtls p")!!.text()
                val poster = it.selectFirst(posterdoc)!!.attr("src")
                newAnimeSearchResponse(title, fixUrl(it.selectFirst("a")!!.attr("href"))) {
                    this.posterUrl = fixUrl(poster)
                    addDubStatus(getDubStatus(title))
                }
            }
            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/buscar?q=$query", timeout = 120).document.select(".col-6").map {
            val title = it.selectFirst(".animedtls p")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst(".animes img")!!.attr("src")
            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime,
                image,
                null,
                if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                    DubStatus.Dubbed
                ) else EnumSet.of(DubStatus.Subbed),
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document
        val poster = doc.selectFirst("head meta[property=og:image]")!!.attr("content")
        val backimagedoc =
            doc.selectFirst("html body div.herohead div.heroheadmain")!!.attr("style")
        val backimageregex = Regex("url\\((.*)\\)")
        val backimage = backimageregex.find(backimagedoc)?.destructured?.component1() ?: ""
        val title = doc.selectFirst("h1")!!.text()
        val type = doc.selectFirst("h4")!!.text()
        val description = doc.selectFirst("p.textComplete")!!.text().replace("Ver menos", "")
        val genres = doc.select(".nobel a").map { it.text() }
        val status = when (doc.selectFirst(".state h6")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select(".heromain .col-item").map {
            val name = it.selectFirst(".dtlsflim p")!!.text()
            val link = it.selectFirst("a")!!.attr("href")
            val epThumb = it.selectFirst(".flimimg img.img1")!!.attr("src")
            Episode(link, name, posterUrl = epThumb)
        }
        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("div.playother p").apmap {
            val encodedurl = it.select("p").attr("data-player")
            val urlDecoded = base64Decode(encodedurl)
            val url = (urlDecoded).replace("https://doramasyt.com/reproductor?url=", "")
                .replace("https://www.doramasyt.com/reproductor?url=", "")
            if (url.startsWith("https://filemoon.sx")) {
                filemoonsxExtractor(url, data, callback)
            } else if (url.startsWith("https://embedwish.com")) {
                embedWishExtractor(url, data, callback)
            } else if (url.startsWith("https://doodstream.com")) {
                doodstreamExtractor(url, data, callback)
            } else if (url.startsWith("https://wishfast.top")) {
                wishfasttopExtractor(url, data, callback)
            } else {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    suspend fun doodstreamExtractor(
        url: String,
        data: String,
        callback: (ExtractorLink) -> Unit
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
                "doodstream.com",
                extractedUrl,
                "https://ds2play.com/",
                null,
                callback,
                false
            )
        } catch (e: Throwable) {
        }
    }

    suspend fun wishfasttopExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit) {
        try {
            val resText = app.get(
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
            ).text
            val regex = """sources: \[\{file:"(.*?)"""".toRegex()
            val match = regex.find(resText)
            val extractedurl = match?.groupValues?.get(1) ?: ""
            streamClean(
                "wishfast.top",
                extractedurl,
                mainUrl,
                null,
                callback,
                extractedurl.contains("m3u8")
            )
        } catch (e: Throwable) {
        }
    }

    suspend fun embedWishExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit) {
        try {
            val resText = app.get(
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
            ).text
            val regex = """sources: \[\{file:"(.*?)"""".toRegex()
            val match = regex.find(resText)
            val extractedurl = match?.groupValues?.get(1) ?: ""
            streamClean(
                "embedwish.com",
                extractedurl,
                mainUrl,
                null,
                callback,
                extractedurl.contains("m3u8")
            )
        } catch (e: Throwable) {
        }
    }

    suspend fun filemoonsxExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit) {
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
            doc.select("script").apmap {
                val script = JsUnpacker(it.html())
                if (script.detect()) {
                    val regex = """sources:\[\{file:"(.*?)"""".toRegex()
                    val match = regex.find(script.unpack() ?: "")
                    val extractedurl = match?.groupValues?.get(1) ?: ""
                    if(!extractedurl.isNullOrBlank()){
                        streamClean(
                            "filemoon.sx",
                            extractedurl,
                            "https://filemoon.sx/",
                            null,
                            callback,
                            extractedurl.contains("m3u8")
                        )
                        return@apmap
                    }
                }
            }
        } catch (e: Throwable) {
        }
    }
}