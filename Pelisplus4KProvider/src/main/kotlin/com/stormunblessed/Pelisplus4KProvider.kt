package com.stormunblessed

import android.util.Base64
import android.webkit.URLUtil
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.mozilla.javascript.Context

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

        val url = "$mainUrl/api/search/$query"
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

    suspend fun hashLoader(
            hash: String,
            data: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        val url = "$mainUrl/player/${Base64.encodeToString(hash.toByteArray(), Base64.DEFAULT)}"
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
        Extractors.mainExtractor(extractedurl, data, subtitleCallback, callback)
    }

}