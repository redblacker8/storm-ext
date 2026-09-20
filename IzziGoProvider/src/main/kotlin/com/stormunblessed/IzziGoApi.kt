package com.stormunblessed

import android.content.SharedPreferences
import androidx.core.content.edit
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class IzziGoApi(private val prefs: SharedPreferences) {

    private val mapper = ObjectMapper()
    private var channelsCache: List<JsonNode>? = null
    private var channelsCacheTime = 0L
    private var categoryGenresCache: Map<String, String>? = null
    private var categoryGenresCacheTime = 0L
    private var playableUrlsCache: List<JsonNode>? = null
    private var playableUrlsCacheTime = 0L
    private val genreIdsCache = mutableMapOf<String, List<String>>()
    private var genreIdsCacheTime = 0L

    val mainUrl = "https://www.izzigo.tv"
    val appVersion = "11.6.22(0)_prd"
    val language = "spa"

    companion object {
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "user_session_token"
        const val KEY_REGION = "region"
        const val KEY_PARTITION = "partition"
        const val KEY_EXPIRES = "expires"
        const val KEY_HW_ID = "hw_id"
        const val KEY_PROVISIONING = "provisioning_data"
        const val KEY_DID = "device_id"
        const val KEY_RECENT = "recent_channels"
        const val RECENT_LIMIT = 10

        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0"

        private const val VIEW = "stb_contents_list_view"

        // Proof-of-work parameters used to build the IRIS-REQUEST-ID for login.
        private const val POW_BITS = 13
        private const val POW_VER = "00001000"
        private const val POW_PASS = "JoaneaUyDpO*Ch7XeCjqm8*!kwlJr9Obwnlc7IE^"

        const val LOGIN_REQUIRED = "izzi go requiere una cuenta con suscripción activa. " +
            "Inicia sesión en: Ajustes del proveedor (icono de engranaje) -> izzi go Login."
    }

    val hwId: String
        get() {
            val existing = prefs.getString(KEY_HW_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_HW_ID, generated) }
            return generated
        }

    val username: String?
        get() = prefs.getString(KEY_USERNAME, null)

    val password: String?
        get() = prefs.getString(KEY_PASSWORD, null)

    val isLoggedIn: Boolean
        get() = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()

    private val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    private val region: String
        get() = prefs.getString(KEY_REGION, null) ?: "Zapopan"

    private val partition: String
        get() = prefs.getString(KEY_PARTITION, null) ?: "OTHERS"

    private var provisioningData: String?
        get() = prefs.getString(KEY_PROVISIONING, null)
        set(value) = prefs.edit { putString(KEY_PROVISIONING, value) }

    private var expires: Long
        get() = prefs.getLong(KEY_EXPIRES, 0L)
        set(value) = prefs.edit { putLong(KEY_EXPIRES, value) }

    private fun headers(auth: Boolean = true, requestId: String? = null): Map<String, String> {
        val base = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "IRIS-DEVICE-TYPE" to "LINUX/FIREFOX",
            "IRIS-DEVICE-CLASS" to "PC",
            "IRIS-HW-DEVICE-ID" to hwId,
            "IRIS-APP-VERSION" to appVersion,
            "IRIS-DEVICE-STATUS" to "ACTIVE",
            "IRIS-APP-NAME" to "izzi go",
            "IRIS-TARGET" to "WEB",
            "IRIS-REQUEST-ID" to (requestId ?: UUID.randomUUID().toString()),
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/webclient/",
        )
        if (auth) token?.let { base["Authorization"] = it }
        return base
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }

    private fun checkErrors(json: JsonNode) {
        val err = json["err"]?.asInt() ?: 0
        if (err != 0) {
            throw ErrorLoadingException("izzi go: ${json["mes"]?.asText() ?: "error $err"}")
        }
    }

    private fun jwtExpiresAt(token: String): Long? {
        return try {
            val payload = token.split(".").getOrNull(1) ?: return null
            val decoded = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            mapper.readTree(String(decoded, Charsets.UTF_8))["exp"]?.asLong()?.times(1000L)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun login(user: String, pass: String): JsonNode {
        val powId = IzziGoPoW.generate(hwId, POW_BITS, POW_PASS, POW_VER)
        val json = mapper.readTree(
            app.post(
                "$mainUrl/managetv/core/user/login",
                headers = headers(auth = false, requestId = powId),
                data = mapOf("username" to user, "password" to pass),
            ).text
        )
        checkErrors(json)
        val sessionToken = json["userSessionToken"]?.asText()
            ?: throw ErrorLoadingException("izzi go: respuesta de login sin token")
        prefs.edit {
            putString(KEY_TOKEN, sessionToken)
            putString(KEY_USERNAME, user)
            putString(KEY_PASSWORD, pass)
            putString(KEY_REGION, json["region"]?.asText() ?: "Zapopan")
            putString(KEY_PARTITION, json["partition"]?.asText() ?: "OTHERS")
            remove(KEY_PROVISIONING)
        }
        expires = (jwtExpiresAt(sessionToken) ?: (System.currentTimeMillis() + 3 * 60 * 60 * 1000L)) - 60_000L
        channelsCache = null
        return json
    }

    suspend fun ensureLogin() {
        if (!isLoggedIn) throw ErrorLoadingException(LOGIN_REQUIRED)
        if (System.currentTimeMillis() < expires) return
        val user = username ?: throw ErrorLoadingException(LOGIN_REQUIRED)
        val pass = password ?: throw ErrorLoadingException(LOGIN_REQUIRED)
        login(user, pass)
    }

    fun logout() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_REGION)
            remove(KEY_PARTITION)
            remove(KEY_EXPIRES)
            remove(KEY_PROVISIONING)
        }
    }

    /** Recently played channels, most recent first. Persisted across restarts. */
    fun recentChannels(): List<IzziRef> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        val arr = try {
            mapper.readTree(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        if (!arr.isArray) return emptyList()
        return arr.mapNotNull { tryParseJson<IzziRef>(it.asText()) }
    }

    /** Returns true when the stored recent list actually changed. */
    fun addRecentChannel(ref: IzziRef): Boolean {
        if (ref.type != "CHANNEL") return false
        val current = recentChannels()
        val updated = (
            listOf(ref) + current.filterNot { it.cid == ref.cid && it.type == ref.type }
            ).take(RECENT_LIMIT)
        if (updated.map { it.cid } == current.map { it.cid }) return false
        val arr = mapper.createArrayNode()
        updated.forEach { arr.add(it.toJson()) }
        prefs.edit { putString(KEY_RECENT, arr.toString()) }
        return true
    }

    private fun baseParams(): MutableMap<String, String> = mutableMapOf(
        "language" to language,
        "region" to region,
        "partition" to partition,
        "controlvn" to appVersion,
    )

    private suspend fun getJson(path: String, params: Map<String, String>, auth: Boolean = true): JsonNode {
        val url = "$mainUrl$path?${query(params)}"
        val json = mapper.readTree(app.get(url, headers = headers(auth)).text)
        checkErrors(json)
        return json
    }

    suspend fun channels(): List<JsonNode> {
        val now = System.currentTimeMillis()
        channelsCache?.let { if (now - channelsCacheTime < 10 * 60 * 1000L) return it }
        ensureLogin()
        val json = getJson(
            "/managetv/tvinfo/channels/get",
            mapOf(
                "region" to region,
                "language" to language,
                "controlvn" to appVersion,
                "partition" to partition,
            ),
        )
        val list = json["chs"]?.toList() ?: emptyList()
        channelsCache = list
        channelsCacheTime = now
        return list
    }

    /** Comma separated genre gids of the category with the given english name (e.g. "Sports"). */
    suspend fun categoryGenres(name: String): String? {
        val now = System.currentTimeMillis()
        val cached = categoryGenresCache
        if (cached == null || now - categoryGenresCacheTime > 10 * 60 * 1000L) {
            ensureLogin()
            val json = getJson(
                "/managetv/tvinfo/category/get",
                mapOf("language" to language, "controlvn" to appVersion),
            )
            val map = linkedMapOf<String, String>()
            json["categories"]?.forEach { c ->
                val n = c["nam"]?.asText() ?: return@forEach
                val genres = c["genres"]?.mapNotNull { it["gid"]?.asText() } ?: emptyList()
                if (genres.isNotEmpty()) map[n] = genres.joinToString(",")
            }
            categoryGenresCache = map
            categoryGenresCacheTime = now
        }
        return categoryGenresCache?.get(name)
    }

    /** Service ids of the channels currently airing content of the given genre gids. */
    suspend fun channelIdsByGenre(genre: String): List<String> {
        val now = System.currentTimeMillis()
        if (now - genreIdsCacheTime > 5 * 60 * 1000L) {
            genreIdsCache.clear()
            genreIdsCacheTime = now
        }
        genreIdsCache[genre]?.let { return it }
        ensureLogin()
        val json = getJson(
            "/managetv/tvinfo/events/filter",
            mapOf(
                "start" to isoNow(0),
                "end" to isoNow(6 * 60),
                "ordering" to "1",
                "count" to "250",
                "jumpTo" to "0",
                "jumpType" to "ordinal",
                "filterChannels" to "true",
                "includeAdult" to "false",
                "language" to language,
                "region" to region,
                "partition" to partition,
                "controlvn" to appVersion,
                "genre" to genre,
            ),
        )
        val ids = json["evs"]?.mapNotNull { it["sid"]?.asText() }?.distinct() ?: emptyList()
        genreIdsCache[genre] = ids
        return ids
    }

    private fun isoNow(plusMinutes: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.MINUTE, plusMinutes)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(cal.time)
    }

    suspend fun contentFilter(extra: Map<String, String>): JsonNode {
        ensureLogin()
        val params = baseParams() + extra
        return getJson("/managetv/tvinfo/content/filter", params)
    }

    suspend fun content(cid: String): JsonNode? {
        ensureLogin()
        val json = getJson(
            "/managetv/tvinfo/content/get",
            mapOf(
                "language" to language,
                "cid" to cid,
                "region" to region,
                "partition" to partition,
                "view" to VIEW,
            ),
        )
        return json["con"]?.firstOrNull()
    }

    suspend fun seasons(seriesCid: String): List<JsonNode> {
        ensureLogin()
        val json = getJson(
            "/managetv/tvinfo/content/seasons",
            mapOf(
                "seriesCid" to seriesCid,
                "region" to region,
                "language" to language,
                "partition" to partition,
                "scope" to "VOD",
            ),
        )
        return json["con"]?.toList() ?: emptyList()
    }

    suspend fun episodes(seasonCid: String): List<JsonNode> {
        ensureLogin()
        val json = contentFilter(
            mapOf(
                "parentContentId" to seasonCid,
                "howRelated" to "CHILD",
                "view" to VIEW,
                "count" to "100",
                "jumpTo" to "0",
                "jumpType" to "ordinal",
            )
        )
        return json["con"]?.toList() ?: emptyList()
    }

    suspend fun search(term: String, page: Int, pageSize: Int = 40): JsonNode {
        ensureLogin()
        val json = getJson(
            "/managetv/tvsearch/content/pagedSearch/personal",
            baseParams() + mapOf(
                "identityToken" to token.orEmpty(),
                "view" to VIEW,
                "term" to term,
                "jumpTo" to (page * pageSize).toString(),
                "pageCount" to pageSize.toString(),
                "rollUpSeries" to "true",
            ),
        )
        return json
    }

    suspend fun ensureProvisioned() {
        if (!provisioningData.isNullOrBlank()) return
        ensureLogin()
        val identityToken = token.orEmpty()

        // Reuse an already provisioned device for this hardware id.
        existingProvisioningData(identityToken)?.takeIf { it.isNotBlank() }?.let {
            provisioningData = it
            return
        }

        // Provision a new device.
        provisionDevice(
            "/managetv/core/device/ott/provision",
            mapOf(
                "identityToken" to identityToken,
                "hid" to hwId,
                "drm" to "WV",
                "sys" to "PC",
                "label" to "CloudStream",
            ),
        )?.takeIf { it.isNotBlank() }?.let {
            provisioningData = it
            return
        }

        // Already provisioned but the data was not returned -> refresh it.
        provisionDevice(
            "/managetv/core/device/ott/updateprovision",
            mapOf(
                "identityToken" to identityToken,
                "hid" to hwId,
                "drm" to "WV",
                "sys" to "PC",
            ),
        )?.takeIf { it.isNotBlank() }?.let {
            provisioningData = it
            return
        }

        throw ErrorLoadingException("izzi go: no se pudo provisionar el dispositivo")
    }

    private suspend fun existingProvisioningData(identityToken: String): String? {
        return try {
            val json = getJson("/managetv/core/device/ott", mapOf("identityToken" to identityToken))
            json["dev"]
                ?.firstOrNull { it["hardwareId"]?.asText() == hwId }
                ?.get("provisioningData")?.asText()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun provisionDevice(path: String, data: Map<String, String>): String? {
        val json = mapper.readTree(
            app.post("$mainUrl$path", headers = headers(), data = data).text
        )
        android.util.Log.d(
            "IzziGo",
            "provision $path -> err=${json["err"]?.asInt()} mes=${json["mes"]?.asText()} " +
                "did=${json["did"]?.asText()} hasData=${!json["provisioningData"]?.asText().isNullOrBlank()}",
        )
        return json["provisioningData"]?.asText()
    }

    suspend fun playableUrl(url: String, packaging: String, drm: String): String? {
        ensureLogin()
        if (drm != "CLEAR") ensureProvisioned()
        val params = mutableMapOf(
            "url" to url,
            "packaging" to packaging,
            "drm" to drm,
            "userSessionToken" to token.orEmpty(),
        )
        provisioningData?.takeIf { it.isNotBlank() }?.let { params["provisioningData"] = it }
        val json = getJson("/streamlocators/multirights/getPlayableUrlAndLicense", params)
        if (json["allowed"]?.asBoolean(false) != true) return null
        return json["videos"]?.firstOrNull()?.get("url")?.asText()
    }

    private val nodeRegex = Regex("^https://live1-ott\\.izzigo\\.tv/\\d+/")

    /** All live stream locators/urls (public endpoint, no per-content license check). */
    suspend fun playableUrls(): List<JsonNode> {
        val now = System.currentTimeMillis()
        playableUrlsCache?.let { if (now - playableUrlsCacheTime < 10 * 60 * 1000L) return it }
        ensureLogin()
        val json = getJson(
            "/streamlocators/getPlayableUrls",
            baseParams() + mapOf(
                "packaging" to "DASH",
                "drm" to "WV",
                "deviceType" to "PC",
                "deviceClass" to "PC",
            ),
        )
        val list = json["urls"]?.toList() ?: emptyList()
        playableUrlsCache = list
        playableUrlsCacheTime = now
        return list
    }

    private fun streamName(url: String): String? {
        val marker = "/out/u/dash/"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val rest = url.substring(idx + marker.length)
        val end = rest.lastIndexOf('/')
        return if (end > 0) rest.substring(0, end) else null
    }

    private suspend fun isReachable(url: String): Boolean {
        return try {
            app.get(url, headers = headers(auth = false)).isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Some channel locators resolve to a `live1-ott.izzigo.tv` url without the CDN node
     * segment (e.g. `.../ESPN-HD/default.mpd` instead of `.../11/ESPN-HD/default.mpd`),
     * which the CDN answers with 403. The node-corrected url exists under another locator
     * for the very same stream name, so look it up in the bulk playable list.
     */
    suspend fun nodeCorrectedStream(streamUrl: String): String? {
        if (!streamUrl.startsWith("https://live1-ott.izzigo.tv/")) return null
        if (nodeRegex.containsMatchIn(streamUrl)) return null
        val name = streamName(streamUrl) ?: return null
        val urls = try {
            playableUrls()
        } catch (e: Exception) {
            return null
        }
        for (candidate in urls) {
            val candidateUrl = candidate["url"]?.asText() ?: continue
            if (streamName(candidateUrl) != name) continue
            if (!nodeRegex.containsMatchIn(candidateUrl)) continue
            if (isReachable(candidateUrl)) return candidateUrl
        }
        return null
    }

    private suspend fun renewAuthorization(renewAuth: String): String? {
        return try {
            val text = app.get(
                "$mainUrl/streamlocators/renewLicenseUrlAuthorization?token=${enc(renewAuth)}",
                headers = headers(),
            ).text.trim()
            if (text.isBlank() || text.startsWith("{")) null else text.trim('"')
        } catch (e: Exception) {
            null
        }
    }

    suspend fun licenseUrl(url: String, packaging: String, drm: String): String? {
        ensureLogin()
        ensureProvisioned()
        val json = getJson(
            "/streamlocators/getLicenseUrlByContent",
            mapOf(
                "url" to url,
                "packaging" to packaging,
                "drm" to drm,
                "provisioningData" to (provisioningData ?: ""),
                "userSessionToken" to token.orEmpty(),
            ),
        )
        val license = json["licenses"]
            ?.firstOrNull { it["system"]?.asText() == "com.widevine.alpha" }
            ?: json["licenses"]?.firstOrNull()
            ?: return null
        // The initial Authorization is single-use; the official client renews it for
        // every license request. Use a freshly renewed token against the renewal URL.
        val renewalUrl = license["renewalUrl"]?.asText()
        val renewAuth = license["renewAuth"]?.asText()
        if (!renewalUrl.isNullOrBlank() && !renewAuth.isNullOrBlank()) {
            val fresh = renewAuthorization(renewAuth)
            if (!fresh.isNullOrBlank()) return renewalUrl.replace("^auth^", fresh)
        }
        return license["url"]?.asText()
    }
}
