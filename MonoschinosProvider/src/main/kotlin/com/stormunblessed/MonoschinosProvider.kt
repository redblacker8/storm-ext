package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MonoschinosProvider : MainAPI() {
    override var mainUrl = "https://monoschinos.st"
    override var name = "Monoschinos"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "" to "Últimos capítulos",
        "animes" to "Catálogo",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "") {
            val doc = app.get(mainUrl, timeout = 120).document
            val latest = doc.select("main section:first-child div.grid > article").map { article ->
                val title = article.selectFirst("h3.card-title")?.text() ?: ""
                val href = fixUrl(article.selectFirst("a.card-wrap")?.attr("href") ?: "")
                val poster = article.selectFirst("img")?.attr("data-src")
                    ?: article.selectFirst("img")?.attr("src") ?: ""
                val ep = article.selectFirst("div.absolute:contains(EP)")?.text()?.replace("EP ", "")?.toIntOrNull()
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                    addDubStatus(DubStatus.Subbed, ep)
                }
            }
            return newHomePageResponse(
                list = HomePageList(request.name, latest, isHorizontalImages = true),
                hasNext = false
            )
        }
        val url = if (page <= 1) "$mainUrl/animes" else "$mainUrl/animes?p=$page"
        val doc = app.get(url, timeout = 120).document
        val home = doc.select("main div.grid a.card-wrap").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a[rel=next]") != null
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.text() ?: return null
        val href = this.attr("href")
        val poster = this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src") ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/buscar?q=$query", timeout = 120).document
        return doc.select("main div.grid a.card-wrap").mapNotNull { it.toSearchResult() }
    }

    data class CapList(
        @JsonProperty("eps") val eps: List<Ep>,
    )

    data class Ep(
        @JsonProperty("num") val num: Int? = null,
    )

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, timeout = 120)
        val doc = resp.document
        val cookies = resp.cookies

        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val typeText = doc.selectFirst("dt:contains(Tipo) + dd")?.text() ?: ""
        val tvType = when {
            typeText.contains("Pelicula") -> TvType.AnimeMovie
            typeText.contains("OVA") || typeText.contains("Especial") -> TvType.OVA
            else -> TvType.Anime
        }

        val tags = doc.select("a[href^='/genero/']").map { it.text() }

        val status = when {
            doc.text().contains("Estreno") -> ShowStatus.Ongoing
            doc.text().contains("Finalizado") -> ShowStatus.Completed
            else -> null
        }

        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val caplistUrl = doc.selectFirst("section.caplist")?.attr("data-ajax") ?: ""

        val episodes = if (caplistUrl.isNotBlank() && token.isNotBlank()) {
            try {
                val capJson = app.post(caplistUrl, data = mapOf("_token" to token), cookies = cookies).parsed<CapList>()
                capJson.eps.mapNotNull { ep ->
                    val epNum = ep.num ?: return@mapNotNull null
                    val epUrl = url.replace("-sub-espanol", "").replace("/anime/", "/ver/") + "-episodio-$epNum"
                    newEpisode(epUrl) {
                        this.episode = epNum
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("button.play-video").amap {
            val url = base64Decode(it.attr("data-player"))
            loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
