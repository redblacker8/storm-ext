package com.stormunblessed


import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import java.util.*
import kotlin.collections.ArrayList


class JKAnimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }

        var latestCookie: Map<String, String> = emptyMap()
        var latestToken = ""
    }

    override var mainUrl = "https://jkanime.net"
    override var name = "JKAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    data class MediaItem (
        val title: String? = null,
        val synopsis: String? = null,
        val image: String? = null,
        val slug: String? = null,
        val type: String? = null,
        val url: String? = null,
    )

    private suspend fun getToken(url: String) {
        if(latestToken.equals("")){
            val maintas = app.get(url)
            val token = maintas.document.selectFirst("html head meta[name=csrf-token]")?.attr("content") ?: ""
            val cookies = maintas.cookies
            latestToken = token
            latestCookie = cookies
        }
    }

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair(
                "$mainUrl/directorio?filtro=popularidad&estado=emision",
                "En emisión"
            ),
            Pair(
                "$mainUrl/directorio?filtro=popularidad&tipo=animes",
                "Animes"
            ),
            Pair(
                "$mainUrl/directorio?filtro=popularidad&tipo=peliculas",
                "Películas"
            ),
        )

        val items = ArrayList<HomePageList>()
        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl).document.select("div#animes div.row.mode1.autoimage.row-cols-md-3.row-cols-2.row-cols-lg-4 div.mb-4.d-flex.align-items-stretch.mb-3.dir1").map {
                    val title = it.selectFirst("h5")?.text()
                    val dubstat = if (title!!.contains("Latino") || title.contains("Castellano"))
                        DubStatus.Dubbed else DubStatus.Subbed
                    val poster =
                        it.selectFirst("div.card.ml-2.mr-2 a div.d-thumb img.card-img-top")?.attr("src") ?: ""
                    val epRegex = Regex("/(\\d+)/|/especial/|/ova/")
                    val url = it.selectFirst("a")?.attr("href")?.replace(epRegex, "")
                    val epNum =
                        it.selectFirst("div.card.ml-2.mr-2 a div.d-thumb div.badges.badges-top span.badge.badge-primary")?.text()?.replace("Ep ", "")?.toIntOrNull()
                    newAnimeSearchResponse(title, url!!) {
                        this.posterUrl = poster
                        addDubStatus(dubstat, epNum)
                    }
                }, true)
        )
        urls.amap { (url, name) ->
            val soup = app.get(url).document
            val json = soup.select("script").firstOrNull { it.html().trim().startsWith("var animes = {") }?.html()?.substringAfter("\"data\":[")?.substringBefore("],")
            val mediaitems = AppUtils.tryParseJson<List<MediaItem>>("[$json]")
            val home = mediaitems?.map {
                val title = it.title
                val poster = it.image
                newAnimeSearchResponse(
                    name = title!!,
                    url = it.url!!,
                    type = TvType.Anime,
                ) {
                    posterUrl = poster
                }
            }.orEmpty()
            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    /* data class MainSearch(
         @JsonProperty("animes") val animes: List<Animes>,
         @JsonProperty("anime_types") val animeTypes: AnimeTypes
     )

     data class Animes(
         @JsonProperty("id") val id: String,
         @JsonProperty("slug") val slug: String,
         @JsonProperty("title") val title: String,
         @JsonProperty("image") val image: String,
         @JsonProperty("synopsis") val synopsis: String,
         @JsonProperty("type") val type: String,
         @JsonProperty("status") val status: String,
         @JsonProperty("thumbnail") val thumbnail: String
     )

     data class AnimeTypes(
         @JsonProperty("TV") val TV: String,
         @JsonProperty("OVA") val OVA: String,
         @JsonProperty("Movie") val Movie: String,
         @JsonProperty("Special") val Special: String,
         @JsonProperty("ONA") val ONA: String,
         @JsonProperty("Music") val Music: String
     ) */

    override suspend fun search(query: String): List<SearchResponse> {
        val search = ArrayList<SearchResponse>()
        val doc = app.get("$mainUrl/buscar/$query").document
        doc.select("div.row div.anime__item").mapNotNull {
            val title = it.selectFirst(".title")?.text() ?: ""
            val href = it.selectFirst("a")?.attr("href") ?: ""
            val img = it.selectFirst(".set-bg")?.attr("data-setbg") ?: ""
            val isDub = title.contains("Latino") || title.contains("Castellano")
            search.add(
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = fixUrl(img)
                    addDubStatus(isDub, !isDub)
                })
        }

        return search
    }

    data class EpsInfo (
        @JsonProperty("number" ) var number : String? = null,
        @JsonProperty("title"  ) var title  : String? = null,
        @JsonProperty("image"  ) var image  : String? = null
    )

    data class EpsResponse (
        @JsonProperty("current_page" ) var currentPage : Int? = null,
        var data  : List<EpsInfo>? = null,
        @JsonProperty("next_page_url"  ) var nextPageurl  : String? = null
    )

    override suspend fun load(url: String): LoadResponse {
        getToken(url)
        val doc = app.get(url).document
        val poster = doc.selectFirst(".anime_pic img")?.attr("src")
        val title = doc.selectFirst(".anime_info h3")?.text()
        val description = doc.selectFirst("p.scroll")?.text()
        val genres = doc.select("div.card:nth-child(3) > div:nth-child(1) > ul:nth-child(1) > li:nth-child(2) > a")
            .map { it.text() }
        val status =
            when (doc.select("div.card:nth-child(3) > div:nth-child(1) > ul:nth-child(1) > li")
                .firstOrNull { it.text().trim().contains("Estado") }?.text()
                ?.substringAfter("Estado:")?.trim()) {
                "En emisión" -> ShowStatus.Ongoing
                "En emision" -> ShowStatus.Ongoing
                "Concluido" -> ShowStatus.Completed
                else -> null
            }

        val year = doc.select("div.card:nth-child(3) > div:nth-child(1) > ul:nth-child(1) > li")
            .firstOrNull { it.text().trim().startsWith("Emitido") }?.text()
            ?.substringAfterLast("de ")?.toIntOrNull()
        val type = doc.selectFirst("div.card:nth-child(3) > div:nth-child(1) > ul:nth-child(1) > li:nth-child(1)")?.text()
        val animeID = doc.selectFirst("div#guardar-anime")?.attr("data-anime")?.toInt()
        val episodes = ArrayList<Episode>()
        var finished = false
        var pagnum = 1
        do {
            val headers = mapOf(
                "Referer" to url
            )
            val res = app.post("$mainUrl/ajax/episodes/$animeID/$pagnum/", headers = headers, data= mapOf("_token" to latestToken), cookies = latestCookie).document.body().html()
            pagnum++
            val json = AppUtils.tryParseJson<EpsResponse>(res)
            json?.data?.amap { info ->
                val imagetest = !info.image.isNullOrBlank()
                val image = if (imagetest) "https://cdn.jkdesu.com/assets/images/animes/video/image_thumb/${info.image}" else null
                val link = "${url.removeSuffix("/")}/${info.number}"
                val ep = newEpisode(link){
                    this.posterUrl = image
                    this.episode = info.number?.toIntOrNull()
                }

                episodes.add(ep)
            }
            if(json?.nextPageurl.isNullOrEmpty())
                finished = true
        }while (finished == false)
        val sortedEpisodes = episodes.sortedBy { it.episode }

        return newAnimeLoadResponse(title!!, url, getType(type!!)) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
            showStatus = status
            plot = description
            tags = genres
            this.year = year
        }
    }

    data class Nozomi(
        @JsonProperty("file") val file: String?
    )

    private suspend fun streamClean(
        name: String,
        url: String,
        referer: String,
        quality: String?,
        callback: (ExtractorLink) -> Unit,
        m3u8: Boolean
    ): Boolean {
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.M3U8){
                this.referer = referer
                this.quality = getQualityFromName(quality)
            }
        )
        return true
    }


    private fun fetchjkanime(text: String?): List<String> {
        if (text.isNullOrEmpty()) {
            return listOf()
        }
        val linkRegex =
            Regex("""(iframe.*class.*width)""")
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"").replace(Regex("(iframe(.class|.src=\")|=\"player_conte\".*src=\"|\".scrolling|\".width)"),"") }.toList()
    }

    suspend fun customLoadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit)
    {
        loadExtractor(url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com","https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            , referer, subtitleCallback, callback)
    }


    data class ServersEncoded (
            @JsonProperty("remote" ) val remote : String,
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("script").firstOrNull{
            it.html().trim().startsWith("var video = [];")
        }?.html().let{
            val json = it?.substringAfter("var servers = [")?.substringBefore("];")
            val linksencoded = AppUtils.tryParseJson<List<ServersEncoded>>("[$json]")
            linksencoded?.amap { val encodedurl = it.remote
                    val urlDecoded = base64Decode(encodedurl)
                    customLoadExtractor(urlDecoded, mainUrl, subtitleCallback, callback) }
            val other = it?.substringBefore("if(video[1] !== undefined)")
            if(!other.isNullOrBlank()){
                fetchjkanime(other).map {
                    it.replace("$mainUrl/jkfembed.php?u=", "https://embedsito.com/v/")
                        .replace("$mainUrl/jkokru.php?u=", "http://ok.ru/videoembed/")
                        .replace("$mainUrl/jkvmixdrop.php?u=", "https://mixdrop.co/e/")
                        .replace("$mainUrl/jk.php?u=", "$mainUrl/")
                        .replace("/jkfembed.php?u=","https://embedsito.com/v/")
                        .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                        .replace("/jkvmixdrop.php?u=", "https://mixdrop.co/e/")
                        .replace("/jk.php?u=", "$mainUrl/")
                        .replace("/um2.php?","$mainUrl/um2.php?")
                        .replace("/um.php?","$mainUrl/um.php?")
                        .replace("=\"player_conte\" src=", "")
                }.amap { link ->
                    fetchUrls(link).forEach {links ->
                        customLoadExtractor(links, data, subtitleCallback, callback)
                        if (links.contains("um2.php")) {
                            val doc = app.get(links, referer = data).document
                            val gsplaykey = doc.select("form input[value]").attr("value")
                            app.post(
                                "$mainUrl/gsplay/redirect_post.php",
                                headers = mapOf(
                                    "Host" to "jkanime.net",
                                    "User-Agent" to USER_AGENT,
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                    "Accept-Language" to "en-US,en;q=0.5",
                                    "Referer" to link,
                                    "Content-Type" to "application/x-www-form-urlencoded",
                                    "Origin" to "https://jkanime.net",
                                    "DNT" to "1",
                                    "Connection" to "keep-alive",
                                    "Upgrade-Insecure-Requests" to "1",
                                    "Sec-Fetch-Dest" to "iframe",
                                    "Sec-Fetch-Mode" to "navigate",
                                    "Sec-Fetch-Site" to "same-origin",
                                    "TE" to "trailers",
                                    "Pragma" to "no-cache",
                                    "Cache-Control" to "no-cache",
                                ),
                                data = mapOf(Pair("data", gsplaykey)),
                                allowRedirects = false
                            ).okhttpResponse.headers.values("location").amap { loc ->
                                val postkey = loc.replace("/gsplay/player.html#", "")
                                val nozomitext = app.post(
                                    "$mainUrl/gsplay/api.php",
                                    headers = mapOf(
                                        "Host" to "jkanime.net",
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                        "X-Requested-With" to "XMLHttpRequest",
                                        "Origin" to "https://jkanime.net",
                                        "DNT" to "1",
                                        "Connection" to "keep-alive",
                                        "Sec-Fetch-Dest" to "empty",
                                        "Sec-Fetch-Mode" to "cors",
                                        "Sec-Fetch-Site" to "same-origin",
                                    ),
                                    data = mapOf(Pair("v", postkey)),
                                    allowRedirects = false
                                ).text
                                val json = parseJson<Nozomi>(nozomitext)
                                val nozomiurl = listOf(json.file)
                                if (nozomiurl.isEmpty()) null else
                                    nozomiurl.forEach { url ->
                                        val nozominame = "Nozomi"
                                        if (url != null) {
                                            streamClean(
                                                nozominame,
                                                url,
                                                "",
                                                null,
                                                callback,
                                                url.contains(".m3u8")
                                            )
                                        }
                                    }
                            }
                        }
                        if (links.contains("/um")) {
                            val desutext = app.get(links, referer = data).document.body().html()
                            val desuRegex = Regex("((https:|http:)//.*\\.m3u8)")
                            val file = desuRegex.find(desutext)?.value
                            val namedesu = "Desu"
                            generateM3u8(
                                namedesu,
                                file!!,
                                mainUrl,
                            ).forEach { desurl ->
                                streamClean(
                                    namedesu,
                                    desurl.url,
                                    mainUrl,
                                    desurl.quality.toString(),
                                    callback,
                                    true
                                )
                            }
                        }
                        if (links.contains("jkmedia")) {
                            app.get(
                                links,
                                referer = data,
                                allowRedirects = false
                            ).okhttpResponse.headers.values("location").amap { xtremeurl ->
                                val namex = "Xtreme S"
                                streamClean(
                                    namex,
                                    xtremeurl,
                                    "",
                                    null,
                                    callback,
                                    xtremeurl.contains(".m3u8")
                                )
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}