package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CinecalidadProvider : MainAPI() {
    override var mainUrl = "https://www.cinecalidad.ec"
    override var name = "Cinecalidad"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded //Due to evoload sometimes not loading

    override val mainPage = mainPageOf(
        "ver-serie" to "Series",
        "fecha-de-lanzamiento/2025" to "Estrenos",
        "genero-de-la-pelicula/animacion" to "Animación",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home = document.select(".item.movies")
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
        val title = this.selectFirst("div.in_title")!!.text()
        val link = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.selectFirst(".poster.custom img")!!.attr("data-src")
        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query}").document
        val results =
            document.select("article.item").mapNotNull { it.toSearchResult() }
        return results
    }


    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document

        val title = soup.selectFirst(".single_left h1")!!.text()
        val description = soup.selectFirst("div.single_left table tbody tr td p")?.text()?.trim()
        val poster: String? = soup.selectFirst(".alignnone")!!.attr("data-src")
        val episodes = soup.select("div.se-c div.se-a ul.episodios li").map { li ->
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
            newEpisode(
                href,
            ) {
                this.name = name
                this.season = season
                this.episode = episode
                this.posterUrl = if (epThumb.contains("svg")) null else epThumb

            }
        }
        return when (val tvType =
            if (url.contains("/ver-pelicula/")) TvType.Movie else TvType.TvSeries) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url,
                    tvType,
                    episodes,
                ) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(
                    title,
                    url,
                    tvType,
                    url
                ) {
                    this.posterUrl = poster
                    this.plot = description
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
        app.get(data).document.select(".linklist ul li[data-option]").amap {
            // Skip the trailer option, which points to a YouTube video
            if (it.hasClass("dooplay_player_option_trailer")) return@amap
            val url = it.attr("data-option")
            if (url.isBlank() || url.contains("youtube.com") || url.contains("youtu.be")) return@amap
            loadExtractor(fixHostsLinks(url), mainUrl, subtitleCallback, callback)
        }
        return true
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
