package com.stormunblessed

import android.content.SharedPreferences
import androidx.core.content.edit
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

class DisneyApi(private val prefs: SharedPreferences) {

    private val mapper = ObjectMapper()
    private val configMutex = Mutex()
    private val refreshMutex = Mutex()
    private val accountMutex = Mutex()
    private val profileMutex = Mutex()
    @Volatile
    private var cachedConfig: JsonNode? = null
    @Volatile
    private var accountCache: Pair<Long, JsonNode>? = null
    private var pendingEmail: String? = null
    private var pendingToken: String? = null

    val isLoggedIn: Boolean
        get() = !prefs.getString(DisneyConstants.KEY_REFRESH_TOKEN, null).isNullOrBlank()

    val email: String?
        get() = prefs.getString(DisneyConstants.KEY_EMAIL, null)

    private val baseHeaders = mapOf(
        "User-Agent" to DisneyConstants.userAgent(),
        "x-application-version" to DisneyConstants.APP_VERSION,
        "x-bamsdk-platform-id" to "android-tv",
        "x-bamsdk-client-id" to DisneyConstants.CLIENT_ID,
        "x-bamsdk-platform" to "android/google/tv",
        "x-bamsdk-version" to DisneyConstants.CLIENT_VERSION,
    )

    fun clearCache() {
        cachedConfig = null
        accountCache = null
    }

    private suspend fun getConfig(): JsonNode {
        cachedConfig?.let { return it }
        return configMutex.withLock {
            cachedConfig?.let { return@withLock it }
            val json = mapper.readTree(app.get(DisneyConstants.CONFIG_URL, headers = baseHeaders).text)
            cachedConfig = json
            json
        }
    }

    private suspend fun orchestrationEndpoint(name: String): String =
        getConfig()["services"]["orchestration"]["client"]["endpoints"][name]["href"].asText()

    private fun checkErrors(json: JsonNode?) {
        if (json == null) return
        json["errors"]?.let { errors ->
            if (errors.isArray && errors.size() > 0) {
                val err = errors[0]
                val code = err["extensions"]?.get("code")?.asText()
                    ?: err["code"]?.asText()
                    ?: err["message"]?.asText()
                    ?: err["description"]?.asText()
                    ?: "unknown"
                val description = err["extensions"]?.get("description")?.asText()
                    ?: err["description"]?.asText()
                val detail = description?.takeIf { it.isNotBlank() && it != code }
                throw ErrorLoadingException("Disney+ API: $code" + (detail?.let { " ($it)" } ?: ""))
            }
        }
        if (json.has("error")) {
            val msg = json["error_description"]?.asText() ?: json["error"]?.asText() ?: "unknown"
            throw ErrorLoadingException("Disney+ API: $msg")
        }
    }

    private suspend fun graphql(
        endpoint: String,
        operationName: String?,
        variables: Any,
        query: String,
        authorization: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonNode {
        val payload = LinkedHashMap<String, Any>()
        operationName?.let { payload["operationName"] = it }
        payload["variables"] = variables
        payload["query"] = query

        var headers = baseHeaders + extraHeaders
        if (authorization != null) headers = headers + ("authorization" to authorization)

        val resp = app.post(endpoint, headers = headers, json = payload)
        val json = mapper.readTree(resp.text)
            ?: throw ErrorLoadingException("Disney+ API: respuesta vacía")
        checkErrors(json)
        return json
    }

    private fun setAuth(sdk: JsonNode) {
        val token = sdk["token"]
        val expiresIn = token["expiresIn"].asLong()
        val now = System.currentTimeMillis() / 1000L
        prefs.edit {
            putString(DisneyConstants.KEY_ACCESS_TOKEN, token["accessToken"].asText())
            putString(DisneyConstants.KEY_REFRESH_TOKEN, token["refreshToken"].asText())
            putLong(DisneyConstants.KEY_EXPIRES, now + expiresIn - 15)
        }
    }

    suspend fun refreshToken(force: Boolean = false): Boolean = refreshMutex.withLock {
        val refreshToken = prefs.getString(DisneyConstants.KEY_REFRESH_TOKEN, null) ?: return@withLock false
        val expires = prefs.getLong(DisneyConstants.KEY_EXPIRES, 0L)
        if (!force && expires > System.currentTimeMillis() / 1000L) return@withLock true

        val endpoint = orchestrationEndpoint("refreshToken")
        val json = graphql(
            endpoint = endpoint,
            operationName = "refreshToken",
            variables = mapOf("input" to mapOf("refreshToken" to refreshToken)),
            query = DisneyQueries.REFRESH_TOKEN,
            authorization = DisneyConstants.API_KEY,
        )
        setAuth(json["extensions"]["sdk"])
        true
    }

    data class DeviceSession(val sdk: JsonNode, val region: String?)

    suspend fun registerDevice(): DeviceSession {
        val variables = mapOf(
            "registerDevice" to mapOf(
                "applicationRuntime" to "android",
                "attributes" to mapOf(
                    "osDeviceIds" to emptyList<String>(),
                    "manufacturer" to "NVIDIA",
                    "model" to "SHIELD Android TV",
                    "operatingSystem" to "Android",
                    "operatingSystemVersion" to "11",
                    "brand" to "NVIDIA",
                ),
                "deviceFamily" to "android",
                "deviceLanguage" to "en",
                "deviceProfile" to "tv",
                "devicePlatformId" to "android-tv",
            )
        )
        val endpoint = orchestrationEndpoint("registerDevice")
        val resp = app.post(
            endpoint,
            headers = baseHeaders + ("authorization" to DisneyConstants.API_KEY),
            json = mapOf("variables" to variables, "query" to DisneyQueries.REGISTER_DEVICE),
        )
        val json = mapper.readTree(resp.text)
            ?: throw ErrorLoadingException("Disney+ API: respuesta vacía al registrar dispositivo")
        checkErrors(json)
        return DeviceSession(json["extensions"]["sdk"], resp.headers["x-bamtech-region"])
    }

    suspend fun checkEmail(email: String, token: String): String {
        val endpoint = orchestrationEndpoint("query")
        val json = graphql(
            endpoint = endpoint,
            operationName = "Check",
            variables = mapOf("email" to email),
            query = DisneyQueries.CHECK_EMAIL,
            authorization = token,
        )
        return json["data"]["check"]["operations"][0].asText()
    }

    suspend fun requestOtp(email: String, token: String): Boolean {
        val endpoint = orchestrationEndpoint("query")
        val json = graphql(
            endpoint = endpoint,
            operationName = "requestOtp",
            variables = mapOf("input" to mapOf("email" to email, "reason" to "Login")),
            query = DisneyQueries.REQUESET_OTP,
            authorization = token,
        )
        return json["data"]["requestOtp"]["accepted"].asBoolean(false)
    }

    suspend fun loginPassword(email: String, password: String, token: String) {
        val endpoint = orchestrationEndpoint("query")
        val json = graphql(
            endpoint = endpoint,
            operationName = "loginTv",
            variables = mapOf("input" to mapOf("email" to email, "password" to password)),
            query = DisneyQueries.LOGIN,
            authorization = token,
        )
        setAuth(json["extensions"]["sdk"])
        prefs.edit { putString(DisneyConstants.KEY_EMAIL, email) }
    }

    suspend fun loginOtp(email: String, passcode: String, token: String): String? {
        val endpoint = orchestrationEndpoint("query")
        val resp = app.post(
            endpoint,
            headers = baseHeaders + ("authorization" to token),
            json = mapOf(
                "operationName" to "authenticateWithOtp",
                "variables" to mapOf("input" to mapOf("email" to email, "passcode" to passcode)),
                "query" to DisneyQueries.LOGIN_OTP,
            ),
        )
        val json = mapper.readTree(resp.text) ?: return "Disney+ API: respuesta vacía"
        val error = json["errors"]?.let { errors ->
            if (errors.isArray && errors.size() > 0) {
                val err = errors[0]
                err["extensions"]?.get("code")?.asText() ?: err["message"]?.asText()
            } else null
        }
        if (error != null) return error

        val actionGrant = json["data"]["authenticateWithOtp"]["actionGrant"].asText()
        loginWithActionGrant(actionGrant, token)
        return null
    }

    private suspend fun loginWithActionGrant(actionGrant: String, token: String) {
        val endpoint = orchestrationEndpoint("query")
        val json = graphql(
            endpoint = endpoint,
            operationName = "loginWithActionGrant",
            variables = mapOf("input" to mapOf("actionGrant" to actionGrant)),
            query = DisneyQueries.LOGIN_ACTION_GRANT,
            authorization = token,
        )
        setAuth(json["extensions"]["sdk"])
        prefs.edit { putString(DisneyConstants.KEY_EMAIL, pendingEmail ?: "") }
    }

    suspend fun startLogin(email: String): Boolean {
        val device = registerDevice()
        val token = device.sdk["token"]["accessToken"].asText()
        val nextStep = checkEmail(email, token).lowercase()
        when (nextStep) {
            "register" -> throw ErrorLoadingException("No existe una cuenta Disney+ con ese correo")
            "otp" -> {
                requestOtp(email, token)
                pendingEmail = email
                pendingToken = token
                return true
            }
            else -> {
                pendingEmail = email
                pendingToken = token
                return false
            }
        }
    }

    suspend fun completePasswordLogin(password: String) {
        val email = pendingEmail ?: throw ErrorLoadingException("Inicia el login primero")
        val token = pendingToken ?: throw ErrorLoadingException("Inicia el login primero")
        loginPassword(email, password, token)
        clearCache()
    }

    suspend fun completeOtpLogin(passcode: String): String? {
        val email = pendingEmail ?: return "Inicia el login primero"
        val token = pendingToken ?: return "Inicia el login primero"
        val error = loginOtp(email, passcode, token)
        if (error == null) clearCache()
        return error
    }

    /**
     * Imports a session captured from the Disney+ web app.
     *
     * Disney+ does not keep the session in cookies: the web app stores the BAM SDK
     * tokens in localStorage under `__bam_sdk_access--disney-svod-3d9324fc_prod`.
     * This accepts that raw value (or any JSON containing a refresh token) or a bare
     * refresh token, then validates it against the API.
     */
    suspend fun importSession(raw: String) {
        val refresh = extractRefreshToken(raw)
            ?: throw ErrorLoadingException(
                "No se encontró un refresh token. Copia el valor completo de localStorage " +
                    "(__bam_sdk_access--disney-svod-3d9324fc_prod)."
            )
        prefs.edit {
            putString(DisneyConstants.KEY_REFRESH_TOKEN, refresh)
            remove(DisneyConstants.KEY_ACCESS_TOKEN)
            putLong(DisneyConstants.KEY_EXPIRES, 0L)
        }
        clearCache()

        val refreshed = runCatching { refreshToken(force = true) }.isSuccess ||
            runCatching { refreshViaBrowserEndpoint(refresh) }.getOrDefault(false)
        if (!refreshed) {
            prefs.edit { remove(DisneyConstants.KEY_REFRESH_TOKEN) }
            throw ErrorLoadingException(
                "El token de sesión no es válido o expiró. Vuelve a copiarlo del navegador."
            )
        }

        runCatching {
            val me = account(force = true)
            me["account"]?.get("attributes")?.get("email")?.asText()
                ?.takeIf { it.isNotBlank() }
                ?.let { email -> prefs.edit { putString(DisneyConstants.KEY_EMAIL, email) } }
        }
    }

    private fun extractRefreshToken(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed.takeIf { it.length >= 20 }
        }
        val node = runCatching { mapper.readTree(trimmed) }.getOrNull() ?: return null
        return findToken(node, setOf("refreshtoken", "refresh_token"))
    }

    private fun findToken(node: JsonNode?, keys: Set<String>): String? {
        if (node == null || node.isNull) return null
        if (node.isTextual) {
            val text = node.asText().trim()
            if (text.startsWith("{") || text.startsWith("[")) {
                return runCatching { mapper.readTree(text) }.getOrNull()?.let { findToken(it, keys) }
            }
            return null
        }
        if (node.isObject) {
            val fields = node.fields()
            while (fields.hasNext()) {
                val (key, value) = fields.next()
                if (key.lowercase() in keys && value.isTextual && value.asText().isNotBlank()) {
                    return value.asText()
                }
            }
            val nested = node.fields()
            while (nested.hasNext()) {
                findToken(nested.next().value, keys)?.let { return it }
            }
        } else if (node.isArray) {
            for (child in node) findToken(child, keys)?.let { return it }
        }
        return null
    }

    /** Fallback for refresh tokens issued to the browser client. */
    private suspend fun refreshViaBrowserEndpoint(refresh: String): Boolean {
        val apiKey = browserApiKey() ?: return false
        val resp = app.post(
            "https://global.edge.bamgrid.com/token",
            headers = mapOf(
                "authorization" to "Bearer $apiKey",
                "x-bamsdk-client-id" to DisneyConstants.CLIENT_ID,
                "x-bamsdk-platform" to "browser",
                "x-bamsdk-version" to "4.8",
            ),
            data = mapOf(
                "grant_type" to "refresh_token",
                "latitude" to "0",
                "longitude" to "0",
                "platform" to "browser",
                "refresh_token" to refresh,
            ),
        )
        val json = mapper.readTree(resp.text) ?: return false
        val access = json["access_token"]?.asText() ?: return false
        val newRefresh = json["refresh_token"]?.asText() ?: refresh
        val expiresIn = json["expires_in"]?.asLong() ?: 3600L
        val now = System.currentTimeMillis() / 1000L
        prefs.edit {
            putString(DisneyConstants.KEY_ACCESS_TOKEN, access)
            putString(DisneyConstants.KEY_REFRESH_TOKEN, newRefresh)
            putLong(DisneyConstants.KEY_EXPIRES, now + expiresIn - 15)
        }
        return true
    }

    private suspend fun browserApiKey(): String? {
        val html = runCatching { app.get("https://www.disneyplus.com/home").text }.getOrNull()
            ?: return null
        return Regex("\"clientApiKey\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
    }

    suspend fun account(force: Boolean = false): JsonNode {
        val now = System.currentTimeMillis()
        val cached = accountCache
        if (!force && cached != null && now - cached.first < 5 * 60 * 1000L) return cached.second

        return accountMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = accountCache
            if (!force && lockedCached != null && lockedNow - lockedCached.first < 5 * 60 * 1000L) {
                return@withLock lockedCached.second
            }

            refreshToken()
            val endpoint = orchestrationEndpoint("query")
            val json = graphql(
                endpoint = endpoint,
                operationName = "EntitledGraphMeQuery",
                variables = emptyMap<String, Any>(),
                query = DisneyQueries.ENTITLEMENTS,
                authorization = accessToken,
            )
            val me = json["data"]?.get("me")?.takeIf { !it.isNull }
                ?: throw ErrorLoadingException("Disney+ API: no se pudo obtener la cuenta")
            accountCache = System.currentTimeMillis() to me
            me
        }
    }

    var profilePin: String?
        get() = prefs.getString(DisneyConstants.KEY_PROFILE_PIN, null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(DisneyConstants.KEY_PROFILE_PIN)
            else putString(DisneyConstants.KEY_PROFILE_PIN, value)
        }

    suspend fun switchProfile(profileId: String, pin: String? = null) {
        refreshToken()
        val endpoint = orchestrationEndpoint("query")
        val input = mutableMapOf<String, Any>("profileId" to profileId)
        if (!pin.isNullOrBlank()) input["entryPin"] = pin
        val json = graphql(
            endpoint = endpoint,
            operationName = "switchProfile",
            variables = mapOf("input" to input),
            query = DisneyQueries.SWITCH_PROFILE,
            authorization = accessToken,
        )
        setAuth(json["extensions"]?.get("sdk")?.takeIf { !it.isNull } ?: return)
        refreshToken(force = true)
        clearCache()
    }

    private fun isPinProtected(profile: JsonNode): Boolean =
        profile["attributes"]?.get("parentalControls")?.get("isPinProtected")?.asBoolean(false) == true

    private fun isKidsProfile(profile: JsonNode): Boolean =
        profile["attributes"]?.get("kidsModeEnabled")?.asBoolean(false) == true

    /**
     * Picks a profile to switch to: the stored PIN is used for PIN-protected profiles.
     * Prefers non-kids, non-PIN profiles, then a PIN-protected one when a PIN is saved.
     */
    private fun pickProfile(profiles: List<JsonNode>): JsonNode? {
        val hasPin = !profilePin.isNullOrBlank()
        return profiles.firstOrNull { !isKidsProfile(it) && !isPinProtected(it) }
            ?: profiles.firstOrNull { !isKidsProfile(it) && isPinProtected(it) && hasPin }
            ?: profiles.firstOrNull { isPinProtected(it) && hasPin }
            ?: profiles.firstOrNull { !isKidsProfile(it) }
            ?: profiles.firstOrNull()
    }

    private fun activeProfileId(me: JsonNode): String? {
        val account = me["account"]?.takeIf { !it.isNull } ?: return null
        account["activeProfile"]?.takeIf { !it.isNull }?.get("id")?.asText()?.let { return it }
        return pickProfile(account["profiles"]?.filter { !it.isNull } ?: emptyList())?.get("id")?.asText()
    }

    /** Selects a profile when the session has no active profile yet. */
    suspend fun ensureActiveProfile() = profileMutex.withLock {
        val me = account()
        val account = me["account"]?.takeIf { !it.isNull } ?: return@withLock
        if (account["activeProfile"]?.takeIf { !it.isNull } != null) return@withLock
        val profiles = account["profiles"]?.filter { !it.isNull } ?: emptyList()
        val profile = pickProfile(profiles) ?: return@withLock
        val profileId = profile["id"]?.asText() ?: return@withLock
        if (isPinProtected(profile) && profilePin.isNullOrBlank()) {
            throw ErrorLoadingException(
                "Disney+ perfil protegido con PIN. Guárdalo en: Ajustes del proveedor -> Disney+ Login -> PIN de perfil."
            )
        }
        switchProfile(profileId, if (isPinProtected(profile)) profilePin else null)
    }

    suspend fun selectActiveProfile(): Boolean {
        return try {
            val me = account(force = true)
            val profileId = activeProfileId(me) ?: return false
            val account = me["account"]?.takeIf { !it.isNull }
            val profile = account?.get("profiles")?.firstOrNull { it["id"]?.asText() == profileId }
            switchProfile(profileId, if (profile != null && isPinProtected(profile)) profilePin else null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun logout() {
        prefs.edit {
            remove(DisneyConstants.KEY_EMAIL)
            remove(DisneyConstants.KEY_ACCESS_TOKEN)
            remove(DisneyConstants.KEY_REFRESH_TOKEN)
            remove(DisneyConstants.KEY_EXPIRES)
            remove(DisneyConstants.KEY_PROFILE_PIN)
        }
        clearCache()
    }

    data class ProfileContext(
        val region: String,
        val maturity: Int,
        val kidsMode: Boolean,
        val appLanguage: String,
        val profileId: String?,
    )

    suspend fun profileContext(): ProfileContext {
        ensureActiveProfile()
        val me = account()
        val session = me["activeSession"]?.takeIf { !it.isNull }
        val account = me["account"]?.takeIf { !it.isNull }
        val activeProfile = account?.get("activeProfile")?.takeIf { !it.isNull }
        val activeProfileId = activeProfile?.get("id")?.asText()
        val profile = activeProfileId?.let { id ->
            account["profiles"]?.firstOrNull { it["id"]?.asText() == id }
        }
        val region = session?.get("portabilityLocation")?.get("countryCode")?.asText()
            ?: session?.get("location")?.get("countryCode")?.asText()
            ?: "US"
        val maturity = session?.get("preferredMaturityRating")?.get("impliedMaturityRating")?.asInt() ?: 1850
        val kidsMode = profile?.get("attributes")?.get("kidsModeEnabled")?.asBoolean(false) ?: false
        val appLanguage =
            profile?.get("attributes")?.get("languagePreferences")?.get("appLanguage")?.asText() ?: "en-US"
        return ProfileContext(region, maturity, kidsMode, appLanguage, activeProfileId)
    }

    private fun formatHref(href: String, ctx: ProfileContext, extra: Map<String, String>): String {
        var out = href
        val args = linkedMapOf(
            "apiVersion" to "5.1",
            "version" to DisneyConstants.EXPLORE_VERSION,
            "region" to ctx.region,
            "impliedMaturityRating" to ctx.maturity.toString(),
            "kidsModeEnabled" to if (ctx.kidsMode) "true" else "false",
            "appLanguage" to ctx.appLanguage,
            "partner" to DisneyConstants.BAM_PARTNER,
        ) + extra
        args.forEach { (k, v) -> out = out.replace("{$k}", v) }
        return out
    }

    private suspend fun exploreGet(
        pathHref: String,
        extraPath: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
    ): JsonNode {
        val ctx = profileContext()
        val url = formatHref(pathHref, ctx, extraPath)
        val query = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") { (k, v) ->
            "${k}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val resp = app.get(url + query, headers = baseHeaders + ("authorization" to accessToken.orEmpty()))
        val json = mapper.readTree(resp.text)
            ?: throw ErrorLoadingException("Disney+ API: respuesta vacía")
        checkErrors(json)
        return json["data"]?.takeIf { !it.isNull }
            ?: throw ErrorLoadingException("Disney+ API: respuesta sin datos")
    }

    private suspend fun explorePost(
        pathHref: String,
        body: Any,
    ): JsonNode {
        val ctx = profileContext()
        val url = formatHref(pathHref, ctx, emptyMap())
        val resp = app.post(
            url,
            headers = baseHeaders + ("authorization" to accessToken.orEmpty()),
            json = body,
        )
        val json = mapper.readTree(resp.text)
            ?: throw ErrorLoadingException("Disney+ API: respuesta vacía")
        checkErrors(json)
        return json["data"]?.takeIf { !it.isNull }
            ?: throw ErrorLoadingException("Disney+ API: respuesta sin datos")
    }

    private fun JsonNode.req(name: String): JsonNode =
        this[name]?.takeIf { !it.isNull }
            ?: throw ErrorLoadingException("Disney+ API: falta '$name' en la respuesta")

    suspend fun deeplink(refId: String, refType: String = "deeplinkId", action: String = "browse"): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["getDeeplink"]["href"].asText()
        return exploreGet(
            pathHref = href,
            params = mapOf("refId" to refId, "refIdType" to refType, "action" to action),
        ).req("deeplink")
    }

    suspend fun page(pageId: String, limit: Int = 999, enhancedLimit: Int = 0): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["getPage"]["href"].asText()
        return exploreGet(
            pathHref = href,
            extraPath = mapOf("pageId" to pageId),
            params = mapOf(
                "disableSmartFocus" to "true",
                "limit" to limit.toString(),
                "enhancedContainersLimit" to enhancedLimit.toString(),
            ),
        ).req("page")
    }

    suspend fun search(query: String): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["search"]["href"].asText()
        return exploreGet(pathHref = href, params = mapOf("query" to query)).req("page")
    }

    /** Live guide views; the ESPN hub is exposed as one or more of these views. */
    suspend fun guideViews(): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["getGuideViews"]["href"].asText()
        return exploreGet(pathHref = href).req("views")
    }

    /** Programs airing in [channelIds] between [startTime] and [endTime] (ISO-8601). */
    suspend fun guideListings(
        channelIds: List<String>,
        startTime: String,
        endTime: String,
    ): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["getGuideListings"]["href"].asText()
        return explorePost(
            pathHref = href,
            body = mapOf(
                "startTime" to startTime,
                "endTime" to endTime,
                "channelIds" to channelIds,
                "includeVisuals" to true,
            ),
        ).req("channels")
    }

    /**
     * Fetches a page of a row/set.
     *
     * Disney's clients paginate sets with a cursor (`after`) taken from the previous
     * response's `cursorPagination.next`. `offset` is kept as a fallback for clients
     * that do not expose a cursor.
     */
    suspend fun getSet(
        setId: String,
        limit: Int = 20,
        page: Int = 1,
        after: String? = null,
    ): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["getSet"]["href"].asText()
        val params = if (after.isNullOrBlank()) {
            mapOf("limit" to limit.toString(), "offset" to (limit * (page - 1)).toString())
        } else {
            mapOf("limit" to limit.toString(), "after" to after)
        }
        return exploreGet(
            pathHref = href,
            extraPath = mapOf("setId" to setId),
            params = params,
        ).req("set")
    }

    suspend fun season(seasonId: String, limit: Int = 100, page: Int = 1): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["getSeason"]["href"].asText()
        return exploreGet(
            pathHref = href,
            extraPath = mapOf("seasonId" to seasonId),
            params = mapOf("limit" to limit.toString(), "offset" to (limit * (page - 1)).toString()),
        ).req("season")
    }

    suspend fun playerExperience(availableId: String): JsonNode {
        val href = getConfig()["services"]["explore"]["client"]["endpoints"]["getPlayerExperience"]["href"].asText()
        return exploreGet(pathHref = href, extraPath = mapOf("availId" to availableId)).req("playerExperience")
    }

    val accessToken: String?
        get() = prefs.getString(DisneyConstants.KEY_ACCESS_TOKEN, null)

    suspend fun widevineLicenseEndpoints(): Pair<String, String> {
        val drm = getConfig()["services"]["drm"]["client"]["endpoints"]
        val vod = drm["widevineLicense"]["href"].asText()
        val linear = drm["widevineLinearLicense"]["href"].asText()
        return vod to linear
    }

    /**
     * Resolves the Widevine license URL for a DRM endpoint key.
     *
     * Disney's config maps each DRM system and content type to an endpoint key in
     * `services.drm.client.extras.licenseEndpoints`. For Widevine that mapping is:
     *   event  -> widevineLicense        (/widevine/v1/obtain-license)
     *   linear -> widevineLinearLicense  (/widevine/v1/channel/obtain-license)
     *   vod    -> widevineLicense        (/widevine/v1/obtain-license)
     *
     * Live *events* report `security.drmEndpointKey = "event"`, so their challenge
     * must go to the VOD-style obtain-license endpoint. Sending an event challenge
     * to the linear-channel endpoint makes the license server answer
     * `400 bad-license-request`, which ExoPlayer surfaces as
     * ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED / MSE_ACQUISITION_FAILED.
     */
    suspend fun widevineLicenseUrl(drmEndpointKey: String?): String? {
        val config = getConfig()
        val drm = config["services"]["drm"]["client"]
        val mapping = drm["extras"]?.get("licenseEndpoints")?.get("WIDEVINE")
        val key = drmEndpointKey?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "vod"
        val endpointName = mapping?.get(key)?.asText()?.takeIf { it.isNotBlank() }
            ?: mapping?.get("vod")?.asText()?.takeIf { it.isNotBlank() }
            ?: "widevineLicense"
        return drm["endpoints"]?.get(endpointName)?.get("href")?.asText()?.takeIf { it.isNotBlank() }
            ?: drm["endpoints"]?.get("widevineLicense")?.get("href")?.asText()
    }

    suspend fun playback(resourceId: String): JsonNode {
        refreshToken()
        val headers = baseHeaders + mapOf(
            "accept" to "application/vnd.media-service+json",
            "authorization" to (prefs.getString(DisneyConstants.KEY_ACCESS_TOKEN, null) ?: ""),
            "x-dss-feature-filtering" to "true",
        )
        val payload = mapOf(
            "playbackId" to resourceId,
            "playback" to mapOf(
                "attributes" to mapOf(
                    "codecs" to mapOf(
                        "supportsMultiCodecMaster" to false,
                        "video" to listOf("h.264"),
                    ),
                    "protocol" to "HTTPS",
                    "frameRates" to listOf(60),
                    // Plural + slide settings are what the live/linear endpoint expects;
                    // VOD accepts them too.
                    "assetInsertionStrategies" to mapOf(
                        "point" to "SGAI",
                        "range" to "SGAI",
                    ),
                    "playbackInitiationContext" to "ONLINE",
                    "slugDuration" to "SLUG_500_MS",
                    "maxSlideDuration" to "4_HOUR",
                    "resolution" to mapOf("max" to listOf("1280x720")),
                ),
            ),
        )
        val href = getConfig()["services"]["media"]["client"]["endpoints"]["mediaPayload"]["href"].asText()
        val endpoint = formatHref(href, profileContext(), mapOf("scenario" to "ctr-regular"))
        val resp = app.post(endpoint, headers = headers, json = payload)
        val json = mapper.readTree(resp.text)
            ?: throw ErrorLoadingException("Disney+ API: respuesta vacía al solicitar playback")
        checkErrors(json)
        return json
    }
}
