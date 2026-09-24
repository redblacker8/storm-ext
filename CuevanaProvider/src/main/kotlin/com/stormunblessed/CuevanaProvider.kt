package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CuevanaProvider : MainAPI() {
    override var mainUrl = "https://wv3.cuevana3.eu"
    override var name = "Cuevana"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "peliculas" to "Peliculas actualizadas",
        "peliculas/estrenos" to "Peliculas Estrenos",
        "series" to "Series actualizadas",
        "series/estrenos" to "Series Estrenos",
    )

    private fun String?.resolvePoster(): String? {
        return this?.let {
            if (it.startsWith("/_next/image?url=")) {
                val encoded = it.substringAfter("url=").substringBefore("&")
                try {
                    java.net.URLDecoder.decode(encoded, "UTF-8")
                } catch (_: Exception) { it }
            } else it.replace("^/".toRegex(), "$mainUrl/")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val soup = app.get("$mainUrl/${request.data}/page/$page").document
        val home = soup.select("section li.TPostMv").map {
            val title = it.selectFirst("span.Title")?.text() ?: "Sin titulo"
            val link = it.selectFirst("a")?.attr("href")?.replace("^/".toRegex(), "$mainUrl/") ?: ""
            newTvSeriesSearchResponse(title, link, if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries){
                this.posterUrl = it.selectFirst("img")?.attr("src").resolvePoster()
            }
        }
        if (home.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query}"
        val document = app.get(url).document

        return document.select("li.TPostMv").map {
            val title = it.selectFirst("span.Title")!!.text()
            val href = it.selectFirst("a")!!.attr("href").replace("^/".toRegex(), "$mainUrl/")
            val image = it.selectFirst("img")!!.attr("src").resolvePoster()
            val isSerie = href.contains("/serie/")

            if (isSerie) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries){
                    this.posterUrl= image
                }
            } else {
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie,
                ){
                    this.posterUrl = image
                }
            }
        }
    }

    data class SeriesResponse(
        val props: Props?
    )

    data class Props(
        val pageProps: PageProps
    )

    data class PageProps(
        val thisSerie: Series
    )

    data class Series(
        val seasons: List<Season>,
        val titles: Titles
    )

    data class Season(
        val number: Int,
        val episodes: List<Episode>
    )

    data class Episode(
        val title: String,
        val TMDbId: String,
        val number: Int,
        val releaseDate: String,
        val image: String,
        val url: Url
    )

    data class Titles(
        val name: String,
        val original: OriginalTitle
    )

    data class OriginalTitle(
        val name: String
    )

    data class Url(
        val slug: String
    )

    fun parseSeriesData(jsonString: String): SeriesResponse? {
        try {
            return parseJson<SeriesResponse>(jsonString)
        } catch (error: Exception) {
            return null;
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document
        val title = soup.selectFirst("h1.Title")?.text() ?: return null
        val description = soup.selectFirst(".Description p")?.text()?.trim()
        val poster: String? = soup.selectFirst("div.backdrop article.TPost div.Image img")?.attr("src").resolvePoster()
        val backgrounposter =
            soup.selectFirst("div.Image:nth-child(2) img")?.attr("src").resolvePoster() ?: poster
        val year1 = soup.selectFirst("footer p.meta").toString()
        val yearRegex = Regex("<span>(\\d+)</span>")
        val yearf =
            yearRegex.find(year1)?.destructured?.component1()?.replace(Regex("<span>|</span>"), "")
        val year = if (yearf.isNullOrBlank()) null else yearf.toIntOrNull()
        val episodes = soup.select("script#__NEXT_DATA__").firstOrNull()?.let {
            parseSeriesData(it.html())?.props?.pageProps?.thisSerie?.seasons?.filter { season -> season.number > 0 && season.episodes.isNotEmpty() }?.flatMap { season ->
                season.episodes.amap {
                    newEpisode(it.url.slug.replace("series/", "$mainUrl/serie/")
                            .replace("seasons/", "temporada/")
                            .replace("episodes/", "episodio/")){
                        this.name = it.title
                        this.season = season.number
                        this.episode = it.number
                        this.posterUrl = it.image
                    }
                }
            }
        }.orEmpty()
        val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a").map { it.text() }
        val tvType = if (episodes == null || episodes.isEmpty()) TvType.Movie else TvType.TvSeries
        val recommendations =
            soup.select("ul.MovieList.Rows li").mapNotNull { element ->
                if (element.parent()?.hasClass("episodes") == true) return@mapNotNull null
                val recTitle = element.selectFirst("span.Title")?.text()
                    ?: element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val image = element.selectFirst("img")?.attr("src").resolvePoster()
                val recUrl = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                newMovieSearchResponse(recTitle, recUrl, TvType.Movie) {
                    this.posterUrl = image
                }
            }
        val trailer =
            soup.selectFirst("div.TPlayer.embed_div div[id=OptY] iframe")?.attr("data-src") ?: ""


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgrounposter
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.backgroundPosterUrl = backgrounposter
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                    if (trailer.isNotBlank()) addTrailer(trailer)
                }

            }

            else -> null
        }
    }

    data class Femcuevana(
        @JsonProperty("url") val url: String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("li.open_submenu").map {
            val languaje = it.text().trim().replaceFirst(" L", "_L").substringBefore(" ")
            it.select("li.clili").amap {
                val iframe = fixUrl(it.attr("data-tr"))
                app.get(iframe).document.select("script")
                    .firstOrNull { it.html().contains("var url = '") }?.html()
                    ?.substringAfter("var url = '")?.substringBefore("';")?.let {
                        loadSourceNameExtractor(languaje, fixHostsLinks(it), mainUrl, subtitleCallback, callback)
                    }
            }
        }
        return true
    }
}

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
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

