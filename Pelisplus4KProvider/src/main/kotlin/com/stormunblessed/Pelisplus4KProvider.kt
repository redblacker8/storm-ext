package com.stormunblessed

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Pelisplus4KProvider : MainAPI() {
    override var mainUrl = "https://tioplus.app"
    override var name = "Pelisplus4K"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "peliculas" to "Peliculas",
        "series" to "Series",
        "doramas" to "Doramas",
        "animes" to "Animes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/$page").document
        val home = document.select(".articlesList article").filter { !it.selectFirst("a")?.attr("target").equals("_blank") }
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
        val title = this.selectFirst("a h2")?.text()
        val link = this.selectFirst("a.itemA")?.attr("href")
        val img = this.selectFirst("picture img")?.attr("data-src")
        return newMovieSearchResponse(title!!, link!!, TvType.Movie) {
            this.posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/api/search/$query").document
        val results =
            document.select("article.item").mapNotNull { it.toSearchResult() }
        return results
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
            val script =
                doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()
            if (!script.isNullOrEmpty()) {
                val jsonscript = script.substringAfter("seasonsJson = ").substringBefore(";")
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
                            newEpisode(epurl) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = realimg
                            }
                        )
                    }
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
        val linkRegex = Regex("\\{ window\\.location\\.href = '(.*)' \\}")
        val doc = app.get(data).document
        doc.select("div ul.subselect li").amap {
            val encodedOne = it.attr("data-server").toByteArray()
            val encodedTwo = base64Encode(encodedOne)
            val text = app.get("$mainUrl/player/$encodedTwo").text
            val link = linkRegex.find(text)?.destructured?.component1()
            if (!link.isNullOrBlank()) {
                loadExtractor(fixHostsLinks(link), mainUrl, subtitleCallback, callback)
            }
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
