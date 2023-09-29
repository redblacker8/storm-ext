package com.lagradost.cloudstream3.movieproviders

import android.net.Uri
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Cinestart
import com.lagradost.cloudstream3.extractors.Okrulink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getQualityFromName
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.jsoup.nodes.Element
import java.net.URI

class CinecalidadProvider : MainAPI() {
    override var mainUrl = "https://cinecalidad.cat"
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
                if (url.contains("""https:\/\/v\d+.cinecalidad.men\/vipembed""".toRegex())) {
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
                    if (extractedurl.startsWith("https://okru.link")) {
                        okruLinkExtractor(extractedurl, data, callback)
                    } else if (extractedurl.startsWith("https://filemoon.sx")) {
                        filemoonsxExtractor(extractedurl, data, callback)
                    } else if (extractedurl
                            .startsWith("https://embedwish.com/e")
                    ) {
                        embedWishExtractor(extractedurl, data, callback)
                    } else if (extractedurl
                            .startsWith("https://netu.cinecalidad.com.mx")
                    ) {
                        netuCineCalidadExtractor(extractedurl, data, subtitleCallback, callback)
                    } else {
                        loadExtractor(extractedurl, mainUrl, subtitleCallback, callback)
                    }
                } else if (url.startsWith("https://okru.link")) {
                    okruLinkExtractor(url, data, callback)
                } else if (url.startsWith("https://cinestart.net")) {
                    val extractor = Cinestart()
                    extractor.getSafeUrl(url, null, subtitleCallback, callback)
                } else if (url.startsWith("https://filemoon.sx")) {
                    filemoonsxExtractor(url, data, callback)
                } else if (url.startsWith("https://embedwish.com")) {
                    embedWishExtractor(url, data, callback)
                } else if (url
                        .startsWith("https://netu.cinecalidad.com.mx")
                ) {
                    netuCineCalidadExtractor(url, data, subtitleCallback, callback)
                } else if (url.startsWith("https://v4.cinecalidad.men")) {
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
                } else {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
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

    suspend fun netuCineCalidadExtractor( // not working
        url: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            app.get(
                url,
                headers = mapOf(
                    "Host" to "netu.cinecalidad.com.mx",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Connection" to "keep-alive",
                    "Referer" to data,
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1",
                ),
                allowRedirects = false,
            ).okhttpResponse.headers.values("location").apmap { extractedurl ->
                streamTest(extractedurl, callback)
//                if (extractedurl.contains("cinestart")) {
//                    loadExtractor(extractedurl, mainUrl, subtitleCallback, callback)
//                }
            }
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
                    if (!extractedurl.isNullOrBlank()) {
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

    suspend fun okruLinkExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit) {
        fun getParameterByKey(url: String, key: String): String? {
            val uri = Uri.parse(url)
            val queryParameterNames = uri.getQueryParameterNames()

            for (queryParameterName in queryParameterNames) {
                if (queryParameterName == key) {
                    return uri.getQueryParameter(queryParameterName)
                }
            }

            return null
        }

        data class ApizzOkruLinkResponse(
            @JsonProperty("url") var url: String? = null,
            @JsonProperty("status") var status: String? = null,
        )
        try {
            val token = getParameterByKey(url, "t")
            val resultJson = parseJson<ApizzOkruLinkResponse>(
                app.post(
                    "https://apizz.okru.link/decoding",
                    headers = mapOf(
                        "Host" to "apizz.okru.link",
                        "Origin" to "https://okru.link",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-site",
                    ),
                    requestBody = "video=$token".toRequestBody(
                        contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                    )
                ).text
            )
            if (!resultJson.url.isNullOrBlank()) {
                streamClean(
                    "okru.link",
                    resultJson.url ?: "",
                    "",
                    "",
                    callback,
                    false,
                )
            }
        } catch (e: Throwable) {
        }
    }
}

