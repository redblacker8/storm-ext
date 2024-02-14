package com.stormunblessed

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor

class CablevisionHdProvider : MainAPI() {
    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.Live
    )
    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium", "18+")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
                Pair("Canales", "$mainUrl"),
        )
        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select("div.page-scroll div#page_container.page-container.bg-move-effect div div#canales.row div.canal-item.col-6.col-xs-6.col-sm-6.col-md-3.col-lg-3").filterNot { element ->
                val text = element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                        ?: ""
                nowAllowed.any {
                    text.contains(it, ignoreCase = true)
                } || text.isNullOrBlank()
            }.map {
                val title = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                        ?: ""
                val img = it.selectFirst("div.lm-canal.lm-info-block.gray-default a div.container-image img")?.attr("src")
                        ?: ""
                val link = it.selectFirst("div.lm-canal.lm-info-block.gray-default a")?.attr("href")
                        ?: ""
                LiveSearchResponse(
                        title,
                        link,
                        this.name,
                        TvType.Movie,
                        fixUrl(img),
                        null,
                        null,
                )
            }
            items.add(HomePageList(name, home))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl
        val doc = app.get(url).document
        return doc.select("div.page-scroll div#page_container.page-container.bg-move-effect div div#canales.row div.canal-item.col-6.col-xs-6.col-sm-6.col-md-3.col-lg-3").filterNot { element ->
            val text = element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                    ?: ""
            nowAllowed.any {
                text.contains(it, ignoreCase = true)
            } || text.isNullOrBlank()
        }.filter { element ->
            element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()?.contains(query, ignoreCase = true)
                    ?: false
        }.map {
            val title = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text() ?: ""
            val img = it.selectFirst("div.lm-canal.lm-info-block.gray-default a div.container-image img")?.attr("src")
                    ?: ""
            val link = it.selectFirst("div.lm-canal.lm-info-block.gray-default a")?.attr("href")
                    ?: ""
            LiveSearchResponse(
                    title,
                    link,
                    this.name,
                    TvType.Movie,
                    fixUrl(img),
                    null,
                    null,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val poster = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"), "/p/original/")
                ?: ""
        val title = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title h2")?.text()
                ?: ""
        val desc = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body div.info")?.text()
                ?: ""

        return newMovieLoadResponse(
                title,
                url, TvType.Live, url
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = desc
        }

    }


    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("a.btn.btn-md").forEach {
            val trembedlink = it.attr("href")
            val tremrequest = app.get(trembedlink, headers = mapOf(
                    "Host" to "www.cablevisionhd.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Referer" to data,
                    "Alt-Used" to "www.cablevisionhd.com",
                    "Connection" to "keep-alive",
                    "Cookie" to "TawkConnectionTime=0; twk_idm_key=qMfE5UE9JTs3JUBCtVUR1",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin",
            )).document
            val trembedlink2 = tremrequest.selectFirst("iframe")?.attr("src") ?: ""
            val tremrequest2 = app.get(trembedlink2, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Referer" to mainUrl,
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
            )).document
            val scriptPacked = tremrequest2.select("script").find { it.html().contains("function(p,a,c,k,e,d)") }?.html()
            val script = JsUnpacker(scriptPacked)
            if (script.detect()) {
                val regex = """MARIOCSCryptOld\("(.*?)"\)""".toRegex()
                val match = regex.find(script.unpack() ?: "")
                val hash = match?.groupValues?.get(1) ?: ""
                val extractedurl = base64Decode(base64Decode(base64Decode(base64Decode(hash))))
                if (!extractedurl.isNullOrBlank()) {
                    Extractors.streamClean(
                            it.text() ?: Extractors.getHostUrl(extractedurl),
                            extractedurl,
                            "${Extractors.getBaseUrl(extractedurl)}/",
                            null,
                            callback,
                            extractedurl.contains("m3u8")
                    )
                }
            }
        }
        return true
    }
}