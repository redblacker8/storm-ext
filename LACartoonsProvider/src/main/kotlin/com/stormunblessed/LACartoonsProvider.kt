package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LACartoonsProvider:MainAPI() {
    override var mainUrl = "https://www.lacartoons.com"
    override var name = "LACartoons"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "/?Categoria_id=1" to "Nickelodeon",
        "/?Categoria_id=2" to "Cartoon Network",
        "/?Categoria_id=3" to "Fox Kids",
        "/?Categoria_id=4" to "Hanna Barbera",
        "/?Categoria_id=5" to "Disney",
        "/?Categoria_id=6" to "Warner Channel",
        "/?Categoria_id=7" to "Marvel",
        "/?Categoria_id=8" to "Otros",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home = document.select(".categorias .conjuntos-series a")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("p.nombre-serie")?.text() ?: "No title"
        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrl(this.selectFirst("img")!!.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?Titulo=$query").document
        val results =
            document.select(".categorias .conjuntos-series a").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val regexep = Regex("Capitulo.(\\d+)|Capitulo.(\\d+)\\-")
        val doc = app.get(url).document
        val title = doc.selectFirst("h2.text-center")?.ownText()
        val tags = doc.selectFirst("h2.text-center span")?.text()?.let { listOf<String>(it) } ?: emptyList()
        val description = doc.selectFirst(".informacion-serie-seccion p:contains(Reseña)")?.text()?.substringAfter("Reseña:")?.trim()
        val poster = doc.selectFirst(".imagen-serie img")?.attr("src")
        val backposter = doc.selectFirst("img.fondo-serie-seccion")?.attr("src")
        val episodes = doc.select("ul.listas-de-episodion li").map {
            val href = it.selectFirst("a")?.attr("href")
            val title = it.selectFirst("a")?.text()
            val name = title?.substringAfter("- ")
            val seasonnum = href?.substringAfter("t=")
            val epnum = regexep.find(title!!)?.destructured?.component1()
            newEpisode(
                fixUrl(href!!),
            ){
                this.name = name
                this.season = seasonnum.toString().toIntOrNull()
                this.episode = epnum.toString().toIntOrNull()
            }
        }
        val recommendations = doc.select(".series-recomendadas a").mapNotNull { it.toSearchResult() }
        return newTvSeriesLoadResponse(title!!, url, TvType.Cartoon, episodes){
            this.posterUrl = fixUrl(poster!!)
            this.backgroundPosterUrl = fixUrl(backposter!!)
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        val iframeSrcs = res.select(".serie-video-informacion iframe")
            .mapNotNull { it.attr("src") }

        for (src in iframeSrcs) {
            if (src.contains("ok.ru/") || src.contains("m.ok.ru/")) {
                // OK.ru embeds: the built-in Odnoklassniki extractor can't parse
                // the &quot;-encoded JSON in the embed page, so we handle them directly.
                handleOkRuEmbed(src, callback)
            } else {
                // Other hosts: use the standard extractor pipeline.
                val fixed = fixHostsLinks(src.replace("https://short.ink/", "https://abysscdn.com/?v="))
                loadExtractor(fixed, data, subtitleCallback, callback)
            }
        }

        return true
    }
}

/**
 * Directly extracts video stream URLs from an OK.ru videoembed page.
 * This avoids the built-in Odnoklassniki extractor, which fails to parse
 * the &quot;-encoded JSON in the current OK.ru embed page format.
 */
private suspend fun handleOkRuEmbed(
    embedUrl: String,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Origin" to "https://ok.ru",
        "User-Agent" to USER_AGENT,
    )

    // Ensure we fetch the /videoembed/ metadata page
    val targetUrl = embedUrl.replace("/video/", "/videoembed/")

    // Fetch embed page and decode HTML entities + JSON escaping
    val cleaned = app.get(targetUrl, headers = headers).text
        .replace("&quot;", "\"")
        .replace("\\&quot;", "\"")
        .replace("\\\\", "\\")
        .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

    // Extract the videos JSON array — safe syntax because content ends with "]"
    val videosStr = Regex(""""videos":(\[[^]]*])""").find(cleaned)?.groupValues?.get(1) ?: return

    val qualityMap = mapOf(
        "mobile" to "144p",
        "lowest" to "240p",
        "low" to "360p",
        "sd" to "480p",
        "hd" to "720p",
        "full" to "1080p",
        "quad" to "1440p",
        "ultra" to "4k",
    )

    // Match each video entry inside the array
    val entryRegex = Regex("\"name\":\"([^\"]+)\",\"url\":\"([^\"]+)\"")
    val matches = entryRegex.findAll(videosStr)

    for (match in matches) {
        val rawName = match.groupValues[1]
        var rawUrl = match.groupValues[2]
        if (rawUrl.startsWith("//")) rawUrl = "https:$rawUrl"

        val qualityStr = qualityMap[rawName.lowercase()] ?: rawName

        callback.invoke(
            newExtractorLink(
                source = "OkRuSSL",
                name = "OkRuSSL",
                url = rawUrl,
                type = INFER_TYPE,
            ) {
                this.referer = "https://ok.ru/"
                this.quality = getQualityFromName(qualityStr)
                this.headers = headers
            }
        )
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}