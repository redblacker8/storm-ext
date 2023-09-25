package com.lagradost.cloudstream3.movieproviders

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class PelisplusHDProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.dev"
    override var name = "PelisplusHD"
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
        val document = app.get(mainUrl).document
        val map = mapOf(
            "Películas" to "#default-tab-1",
            "Series" to "#default-tab-2",
            "Anime" to "#default-tab-3",
            "Doramas" to "#default-tab-4",
        )
        map.forEach {
            items.add(HomePageList(
                it.key,
                document.select(it.value).select("a.Posters-link").map { element ->
                    element.toSearchResult()
                }
            ))
        }
        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".listing-content p").text()
        val href = this.select("a").attr("href")
        val posterUrl = fixUrl(this.select(".Posters-img").attr("src"))
        val isMovie = href.contains("/pelicula/")
        return if (isMovie) {
            MovieSearchResponse(
                title,
                href,
                name,
                TvType.Movie,
                posterUrl,
                null
            )
        } else {
            TvSeriesSearchResponse(
                title,
                href,
                name,
                TvType.Movie,
                posterUrl,
                null,
                null
            )
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
            Episode(
                href!!,
                name,
                season,
                episode,
            )
        }

        val year = soup.selectFirst(".p-r-15 .text-semibold")?.text()?.toIntOrNull()
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val tags = soup.select(".p-h-15.text-center a span.font-size-18.text-info.text-semibold")
            .map { it?.text()?.trim().toString().replace(", ", "") }

        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title!!,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    fixUrl(poster!!),
                    year,
                    description,
                    null,
                    null,
                    tags,
                )
            }

            TvType.Movie -> {
                MovieLoadResponse(
                    title!!,
                    url,
                    this.name,
                    tvType,
                    url,
                    fixUrl(poster!!),
                    year,
                    description,
                    null,
                    tags,
                )
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
        app.get(data).document.select("div.player").map { script ->
            fetchUrls(
                script.data()
                    .replace("https://api.mycdn.moe/furl.php?id=", "https://www.fembed.com/v/")
                    .replace("https://api.mycdn.moe/sblink.php?id=", "https://streamsb.net/e/")
            )
                .apmap { link ->
                    val regex = """go_to_player\('(.*?)'""".toRegex()
                    regex.findAll(app.get(link).document.html()).toList().apmap {
                        val link =
                            base64Decode(
                                it?.groupValues?.get(1) ?: ""
                            )
                        if (link.contains("https://api.mycdn.moe/video/") || link.contains("https://api.mycdn.moe/embed.php?customid")) {
                            val doc = app.get(link).document
                            doc.select("div.ODDIV li").apmap {
                                val linkencoded = it.attr("data-r")
                                val linkdecoded = base64Decode(linkencoded)
                                    .replace(
                                        Regex("https://owodeuwu.xyz|https://sypl.xyz"),
                                        "https://embedsito.com"
                                    )
                                    .replace(Regex(".poster.*"), "")
                                val secondlink =
                                    it.attr("onclick").substringAfter("go_to_player('")
                                        .substringBefore("',")
                                loadExtractor(linkdecoded, link, subtitleCallback, callback)
                                val restwo = app.get(
                                    "https://api.mycdn.moe/player/?id=$secondlink",
                                    allowRedirects = false
                                ).document
                                val thirdlink = restwo.selectFirst("body > iframe")?.attr("src")
                                    ?.replace(
                                        Regex("https://owodeuwu.xyz|https://sypl.xyz"),
                                        "https://embedsito.com"
                                    )
                                    ?.replace(Regex(".poster.*"), "")
                                loadExtractor(thirdlink!!, link, subtitleCallback, callback)

                            }
                        } else if (link.startsWith("https://filemoon.sx")) {
                            filemoonsxExtractor(link, data, callback)
                        } else if (link.startsWith("https://streamwish.to")) {
                            streamwishExtractor(link, data, callback)
                        } else if (link.startsWith("https://doodstream.com")) {
                            doodstreamExtractor(link, data, callback)
                        } else if (link.startsWith("https://plusvip.net")) {
                            plusvipnetExtractor(link, data, callback)
                        } else {
                            loadExtractor(link, data, subtitleCallback, callback)
                        }
                    }
                }
        }
        return true
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

    suspend fun streamwishExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit) {
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
            streamClean(
                "streamwish.to",
                extractedurl,
                mainUrl,
                null,
                callback,
                extractedurl.contains("m3u8")
            )
        } catch (e: Throwable) {
        }
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
                "https://doods.pro" + endpoint,
                headers = mapOf(
                    "Host" to "doods.pro",
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
                referer,
                null,
                callback,
                false
            )
        } catch (e: Throwable) {
        }
    }

    suspend fun plusvipnetExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit) {
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

        data class PlusvipNetSources(
            @JsonProperty("link") var link: String? = null,
        )

        fun decryptBase64AES(input: String, key: String): String {
            val inputBytes = Base64.decode(input, Base64.DEFAULT)
            val secretKey = SecretKeySpec(key.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val outputBytes = cipher.doFinal(inputBytes)
            val output = Base64.encodeToString(outputBytes, Base64.DEFAULT)
            return output
        }
        try {
            val endpointHash =
                app.get(url).document.select("script[type=json]").first()?.html()?.replace("\"", "")
            if (endpointHash.isNullOrBlank()) {
                return
            }
            val endpoint =
                base64Decode(decryptBase64AES(endpointHash, "d41d8cd98f00b204e9800998ecf8427e"))
            val token = getParameterByKey(url, "data")
            val fetchurl = "https://plusvip.net$endpoint"
            val res = parseJson<PlusvipNetSources>(
                app.post(
                    fetchurl,
                    headers = mapOf(
                        "Host" to "plusvip.net",
                        "Origin" to "https://plusvip.net",
                        "Referer" to url,
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                    ),
                    requestBody = "link=$token".toRequestBody(
                        contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                    )
                ).text
            )
            if (!res.link.isNullOrBlank()) {
                streamClean(
                    "plusvip.net",
                    res.link ?: "",
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
