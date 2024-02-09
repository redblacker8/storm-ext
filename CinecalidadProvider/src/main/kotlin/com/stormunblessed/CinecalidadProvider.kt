package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Cinestart
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.stormunblessed.Extractors

import java.net.URI

class CinecalidadProvider : MainAPI() {
    override var mainUrl = "https://cinecalidad.men"
    override var name = "Cinecalidad"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.Movie,
            TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded //Due to evoload sometimes not loading
    private val cloudflareKiller by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
            Pair("$mainUrl/ver-serie/page/", "Series"),
            Pair("$mainUrl/page/", "Peliculas"),
            Pair("$mainUrl/genero-de-la-pelicula/peliculas-en-calidad-4k/page/", "4K UHD"),
    )

    override suspend fun getMainPage(
            page: Int,
            request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page

        val soup = app.get(url).document
        val home = soup.select(".item.movies").map {
            val title = it.selectFirst("div.in_title")!!.text()
            val link = it.selectFirst("a")!!.attr("href")
            TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    if (link.contains("/ver-pelicula/")) TvType.Movie else TvType.TvSeries,
                    it.selectFirst(".poster.custom img")!!.attr("data-src"),
                    null,
                    null,
            )
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url).document

        return document.select("article").map {
            val title = it.selectFirst("div.in_title")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst(".poster.custom img")!!.attr("data-src")
            val isMovie = href.contains("/ver-pelicula/")

            if (isMovie) {
                MovieSearchResponse(
                        title,
                        href,
                        this.name,
                        TvType.Movie,
                        image,
                        null
                )
            } else {
                TvSeriesSearchResponse(
                        title,
                        href,
                        this.name,
                        TvType.TvSeries,
                        image,
                        null,
                        null
                )
            }
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

    override suspend fun load(url: String): LoadResponse? {
        try {
            var resp = app.get(
                    url
            )
            var body = resp.document
            val title = body.select(".single_left h1").first()!!.text()
            val description =
                    body.selectFirst("div.single_left table tbody tr td p")?.text()?.trim()
            val poster: String? = body.selectFirst(".alignnone")!!.attr("data-src")
            val episodes = body.select("div.se-c div.se-a ul.episodios li").map { li ->
                val href = li.selectFirst("a")!!.attr("href")
                val epThumb = li.selectFirst("img.lazy")!!.attr("data-src")
                val name = li.selectFirst(".episodiotitle a")!!.text()
                val seasonid =
                        li.selectFirst(".numerando")!!.text().replace(Regex("(S|E)"), "").let { str ->
                            str.split("-").mapNotNull { subStr -> subStr.toIntOrNull() }
                        }
                val isValid = seasonid.size == 2
                val episode = if (isValid) seasonid.getOrNull(1) else null
                val season = if (isValid) seasonid.getOrNull(0) else null
                Episode(
                        href,
                        name,
                        season,
                        episode,
                        if (epThumb.contains("svg")) null else epThumb
                )
            }
            return when (val tvType =
                    if (url.contains("/ver-pelicula/")) TvType.Movie else TvType.TvSeries) {
                TvType.TvSeries -> {
                    TvSeriesLoadResponse(
                            title,
                            url,
                            this.name,
                            tvType,
                            episodes,
                            poster,
                            null,
                            description,
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
                            null,
                            description,
                    )
                }

                else -> null
            }
        } catch (e: Throwable) {
            return null
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val datam = app.get(data)
        val doc = datam.document
        val datatext = datam.text

        doc.select("div #playeroptions li").apmap {
            try {
                val url = it.attr("data-option").replace("youtube", "")
                if (url.contains("""https?://(\w+\.)?cinecalidad\.\w+/vipembed""".toRegex())) {
                    val res = app.get(
                            url,
                            headers = mapOf(
                                    "Host" to (URI(data).host ?: ""),
                                    "User-Agent" to USER_AGENT,
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                    "Accept-Language" to "en-US,en;q=0.5",
                                    "DNT" to "1",
                                    "Connection" to "keep-alive",
                                    "Referer" to data,
                                    "Upgrade-Insecure-Requests" to "1",
                                    "Sec-Fetch-Dest" to "iframe",
                                    "Sec-Fetch-Mode" to "navigate",
                                    "Sec-Fetch-Site" to "same-origin",
                                    "Sec-Fetch-User" to "?1",
                            ),
                            allowRedirects = false
                    ).document
                    val extractedurl = res.selectFirst("iframe")!!.attr("src")
                    Extractors.mainExtractor(extractedurl, mainUrl, subtitleCallback, callback)
                } else {
                    Extractors.mainExtractor(url, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Throwable) {
            }
        }
        if (datatext.contains("en castellano")) app.get("$data?ref=es").document.select(".dooplay_player_option")
                .apmap {
                    val url = it.attr("data-option")
                    if (url.startsWith("https://cinestart.net")) {
                        val extractor = Cinestart()
                        extractor.getSafeUrl(url, null, subtitleCallback, callback)
                    } else {
                        loadExtractor(url, mainUrl, subtitleCallback, callback)
                    }

                    if (url.startsWith("https://v4.cinecalidad.men")) {
                        val cineurlregex =
                                Regex("(https:\\/\\/v4\\.cinecalidad\\.men\\/play\\/\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                        cineurlregex.findAll(url).map {
                            it.value.replace("/play/", "/play/r.php")
                        }.toList().apmap {
                            app.get(
                                    it,
                                    headers = mapOf(
                                            "Host" to "v4.cinecalidad.men",
                                            "User-Agent" to USER_AGENT,
                                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                            "Accept-Language" to "en-US,en;q=0.5",
                                            "DNT" to "1",
                                            "Connection" to "keep-alive",
                                            "Referer" to data,
                                            "Upgrade-Insecure-Requests" to "1",
                                            "Sec-Fetch-Dest" to "iframe",
                                            "Sec-Fetch-Mode" to "navigate",
                                            "Sec-Fetch-Site" to "same-origin",
                                            "Sec-Fetch-User" to "?1",
                                    ),
                                    allowRedirects = false
                            ).okhttpResponse.headers.values("location").apmap { extractedurl ->
                                if (extractedurl.contains("cinestart")) {
                                    loadExtractor(extractedurl, mainUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }
        if (datatext.contains("Subtítulo LAT") || datatext.contains("Forzados LAT")) {
            doc.select("#panel_descarga.pane a").apmap {
                val link =
                        if (data.contains("serie") || data.contains("episodio")) "${data}${it.attr("href")}"
                        else it.attr("href")
                val docsub = app.get(link)
                val linksub = docsub.document
                val validsub = docsub.text
                if (validsub.contains("Subtítulo") || validsub.contains("Forzados")) {
                    val langregex = Regex("(Subtítulo.*\$|Forzados.*\$)")
                    val langdoc = linksub.selectFirst("div.titulo h3")!!.text()
                    val reallang = langregex.find(langdoc)?.destructured?.component1()
                    linksub.select("a.link").apmap {
                        val sublink =
                                if (data.contains("serie") || data.contains("episodio")) "${data}${
                                    it.attr("href")
                                }"
                                else it.attr("href")
                        subtitleCallback(
                                SubtitleFile(reallang!!, sublink)
                        )
                    }
                }
            }
        }
        return true
    }
}

