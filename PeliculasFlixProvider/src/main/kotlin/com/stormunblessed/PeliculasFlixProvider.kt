package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PeliculasFlixProvider:MainAPI() {
    companion object  {
        private const val peliflixapi = "https://doraflix.fluxcedene.net/api/gql"
        private val mediaType = "application/json; charset=utf-8".toMediaType()
    }

    override var mainUrl = "https://pelisflixhd.love"
    override var name = "PeliculasFlix"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
    )



    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w1280/$link" else link
    }


    data class PeliMain (

        @JsonProperty("data" ) var data : PeliData? = PeliData()

    )

    data class PeliData (
        @JsonProperty("paginationFilm" ) var paginationFilm : PaginationFilm? = PaginationFilm(),
        @JsonProperty("searchFilm" ) var searchFilm : ArrayList<PeliItems>?= arrayListOf(),
        @JsonProperty("detailFilm" ) var detailFilm : DetailFilm? = DetailFilm()
    )

    data class PageInfo (
        @JsonProperty("currentPage"    ) var currentPage    : Int?     = null,
        @JsonProperty("hasNextPage"    ) var hasNextPage    : Boolean? = null,
        @JsonProperty("hasPreviousPage") var hasPreviousPage: Boolean? = null,
        @JsonProperty("__typename"     ) var __typename     : String?  = null
    )
    data class PaginationFilm (
        @JsonProperty("items"    ) var items    : ArrayList<PeliItems>? = arrayListOf(),
        @JsonProperty("pageInfo" ) var pageInfo : PageInfo?             = PageInfo(),
        @JsonProperty("__typename") var __typename : String?            = null
    )
    data class PeliItems (
        @JsonProperty("_id"          ) var Id          : String?           = null,
        @JsonProperty("title"        ) var title       : String?           = null,
        @JsonProperty("name"         ) var name        : String?           = null,
        @JsonProperty("overview"     ) var overview    : String?           = null,
        @JsonProperty("runtime"      ) var runtime     : Int?              = null,
        @JsonProperty("slug"         ) var slug        : String?           = null,
        @JsonProperty("name_es"      ) var nameEs      : String?           = null,
        @JsonProperty("poster_path"  ) var posterPath  : String?           = null,
        @JsonProperty("poster"       ) var poster      : String?           = null,
        @JsonProperty("languages"    ) var languages   : ArrayList<String> = arrayListOf(),
        @JsonProperty("release_date" ) var releaseDate : String?           = null,
        @JsonProperty("__typename"   ) var _typename   : String?           = null
    )

    data class DetailFilm (
        @JsonProperty("name"          ) var name         : String?                = null,
        @JsonProperty("title"         ) var title        : String?                = null,
        @JsonProperty("name_es"       ) var nameEs       : String?                = null,
        @JsonProperty("overview"      ) var overview     : String?                = null,
        @JsonProperty("languages"     ) var languages    : ArrayList<String>      = arrayListOf(),
        @JsonProperty("popularity"    ) var popularity   : Double?                = null,
        @JsonProperty("poster"        ) var poster       : String?                = null,
        @JsonProperty("poster_path"   ) var posterPath   : String?                = null,
        @JsonProperty("backdrop"      ) var backdrop     : String?                = null,
        @JsonProperty("backdrop_path" ) var backdropPath : String?                = null,
        @JsonProperty("genres"        ) var genres       : ArrayList<GenresAndLabels>?      = arrayListOf(),
        @JsonProperty("labels"        ) var labels       : ArrayList<GenresAndLabels>?      = arrayListOf(),
        @JsonProperty("links_online"  ) var linksOnline  : ArrayList<LinksOnline>? = arrayListOf(),
        @JsonProperty("__typename"    ) var _typename    : String?                = null
    )

    data class GenresAndLabels (
        @JsonProperty("name"       ) var name      : String? = null,
        @JsonProperty("slug"       ) var slug      : String? = null,
        @JsonProperty("__typename" ) var _typename : String? = null
    )

    data class LinksOnline (
        @JsonProperty("_id"        ) var Id        : String? = null,
        @JsonProperty("server"     ) var server    : Int?    = null,
        @JsonProperty("lang"       ) var lang      : String? = null,
        @JsonProperty("link"       ) var link      : String? = null,
        @JsonProperty("__typename" ) var _typename : String? = null
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // The GraphQL API is frozen (last data from 2023) and its links are dead, so we scrape the
        // live HTML site instead. It exposes the current catalog with working Voe embed links.
        val doc = app.get("$mainUrl/").document
        val lists = doc.select("section dl").mapNotNull { dl ->
            val sectionTitle = dl.selectFirst("dt.section-title")?.text()?.trim() ?: return@mapNotNull null
            val movies = dl.select("div.movie-item").mapNotNull { item ->
                val a = item.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href")
                if (!href.contains("/pelicula/")) return@mapNotNull null
                val title = item.selectFirst(".item-detail p")?.text()?.trim() ?: return@mapNotNull null
                val img = item.selectFirst("img.poster")?.attr("src")?.let { src ->
                    if (src.startsWith("//")) "https:$src" else src
                }
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = img
                }
            }
            if (movies.isEmpty()) null else HomePageList(sectionTitle, movies)
        }
        if (lists.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Scrape the live site search - the GraphQL "searchFilm" only knows the stale 2023 catalog.
        val searchUrl = "$mainUrl/busqueda/${query.replace(" ", "%20")}"
        val doc = app.get(searchUrl).document
        return doc.select("div.movie-item").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (!href.contains("/pelicula/")) return@mapNotNull null
            val title = item.selectFirst(".item-detail p")?.text()?.trim() ?: return@mapNotNull null
            val img = item.selectFirst("img")?.attr("src")?.let { src ->
                if (src.startsWith("//")) "https:$src" else src
            }
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = img
            }
        }.ifEmpty { null }
    }

    data class PelisInfo (
        @JsonProperty("id"   ) var id   : String? = null,
        @JsonProperty("slug" ) var slug : String? = null,
        @JsonProperty("type" ) var type : String? = null,
    )
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/pelicula/")) {
            val doc = app.get(url).document
            val title = doc.selectFirst("h1.title span[itemprop=name]")?.text()
                ?.trim()?.removePrefix("Ver Película ")?.trim() ?: ""
            val poster = doc.selectFirst("img[alt*=poster]")?.attr("src")?.let { src ->
                if (src.startsWith("//")) "https:$src" else src
            }
            val plot = doc.selectFirst("div.description p")?.text() ?: ""
            val duration = doc.selectFirst("[itemprop=duration]")?.text()?.trim()

            val linksData = doc.select("li[data-server]").mapNotNull { li ->
                val encoded = li.attr("data-server")
                if (encoded.isBlank()) return@mapNotNull null
                val link = base64Decode(encoded)
                val spanText = li.selectFirst("span")?.text()?.trim() ?: ""
                val lang = when {
                    spanText.contains("Latino", true) -> "Latino"
                    spanText.contains("Castellano", true) -> "Castellano"
                    spanText.contains("Subtitulado", true) -> "Subtitulado"
                    spanText.contains("Ingles", true) -> "Ingles"
                    else -> "Server"
                }
                val server = Regex("Opci.n (\\d+)").find(spanText)?.groupValues?.get(1)?.toIntOrNull()
                LinksOnline(link = link, lang = lang, server = server)
            }
            val movieData = linksData.toJson()

            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = plot
                addDuration(duration)
                if (movieData == "[]" || movieData == "null") this.comingSoon = true
            }
        }

        val fixed = url.replace("$mainUrl/","")
        val json = parseJson<PelisInfo>(fixed)
        val sluginfo = json.slug
        val typename = json.type
        val id = json.id
        val bodyJson = "{\"operationName\":\"detailFilm\",\"variables\":{\"slug\":\"$sluginfo\"},\"query\":\"query detailFilm(\$slug: String!) {\\n  detailFilm(filter: {slug: \$slug}) {\\n    name\\n    title\\n    name_es\\n    overview\\n    languages\\n    popularity\\n  poster\\n poster_path\\n  backdrop\\n backdrop_path\\n  genres {\\n      name\\n      slug\\n      __typename\\n    }\\n    labels {\\n      name\\n      slug\\n      __typename\\n    }\\n     backdrop\\n    links_online {\\n      _id\\n      server\\n      lang\\n      link\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val res = app.post(peliflixapi, requestBody = bodyJson.toRequestBody(mediaType)).parsed<PeliMain>()
        val meta = res.data?.detailFilm
        val title = meta?.name ?: meta?.title ?: ""
        val plot = meta?.overview
        val posterinfo = meta?.posterPath ?: meta?.poster ?: ""
        val backposterinfo = meta?.backdropPath ?: meta?.backdrop ?: ""
        val poster = getImageUrl(posterinfo)
        val backposter = getImageUrl(backposterinfo)
        val tags = ArrayList<String>()
        val tags1 = meta?.genres?.map { tags.add(it.name!!) }
        val tags2 = meta?.labels?.map { tags.add(it.name!!) }
        val movieData = meta?.linksOnline?.toJson() ?: ""
        return newMovieLoadResponse(title, "{\"id\":\"$id\",\"slug\":\"$sluginfo\",\"type\":\"$typename\"}", TvType.Movie, movieData){
            this.posterUrl = poster
            this.plot = plot
            this.backgroundPosterUrl = backposter
            this.tags = tags.distinct().toList()
            if (movieData.isEmpty()) this.comingSoon = true
        }
    }



    data class VideoInfo (
        @JsonProperty("file" ) var file : String? = null
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<ArrayList<LinksOnline>>(data)
        links.amap { info ->
            val link = info.link ?: return@amap
            val lang = info.lang?.let { l ->
                when (l) {
                    "38"  -> "Latino"
                    "37"  -> "Castellano"
                    "192" -> "Subtitulado"
                    else  -> l
                }
            } ?: "Server"

            if (link.contains("player.html#")) {
                val videoID = link.substringAfter("player.html#")
                val fi = app.get("https://pelisplus.esplay.io/video/$videoID").parsedSafe<VideoInfo>()
                val file = fi?.file
                if (!file.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(lang, lang, file) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else {
                val collected = ArrayList<ExtractorLink>()
                val target = if (link.contains("nupload.")) {
                    val uploadDoc = app.get(link).document
                    uploadDoc.selectFirst("iframe")?.attr("src")?.let { src ->
                        if (src.startsWith("//")) "https:$src" else src
                    }
                } else {
                    link
                }
                if (target != null) {
                    loadExtractor(target, mainUrl, subtitleCallback) { extracted ->
                        collected.add(extracted)
                    }
                }
                collected.forEach { extracted ->
                    callback.invoke(newExtractorLink(extracted.source, "$lang - ${extracted.name}", extracted.url) {
                        this.quality = extracted.quality
                        this.type = extracted.type
                        this.referer = extracted.referer
                        this.headers = extracted.headers
                    })
                }
            }
        }
        return true
    }
}
