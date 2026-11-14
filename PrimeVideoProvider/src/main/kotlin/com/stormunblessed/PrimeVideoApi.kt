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
import java.util.UUID

/**
 * Amazon Prime Video API client.
 *
 * Dual-mode API:
 *   1) Web/HTML scraping (legacy, primevideo.com only) — fetchPageJson from HTML pages
 *   2) Mobile/CDP API (all regions) — getMobilePage using Atza token via
 *      GET {atvUrl}/cdp/mobile/getDataByTransform/v1/{transform}?{params}
 *      with header x-amzn-identity-auth-token = {at-main-av cookie value}
 *
 * Auth chain:
 *   - Atza token (at-main-av cookie) → mobile API (primary, simplest)
 *   - Refresh token → Bearer token → mobile API + playback
 *   - Cookies → web page scraping (legacy fallback)
 */
class PrimeVideoApi(private val prefs: SharedPreferences) {

    private val mapper = ObjectMapper()
    private val configMutex = Mutex()
    private val refreshMutex = Mutex()

    // ────────────────────────────────────────────────────────────────────
    // Session state
    // ────────────────────────────────────────────────────────────────────

    val isLoggedIn: Boolean
        get() = !atvToken.isNullOrBlank() ||
            !prefs.getString(PrimeVideoConstants.KEY_REFRESH_TOKEN, null).isNullOrBlank() ||
            !prefs.getString(PrimeVideoConstants.KEY_COOKIES, null).isNullOrBlank()

    /** Atza token from at-main-av cookie, used as x-amzn-identity-auth-token */
    val atvToken: String?
        get() = prefs.getString(PrimeVideoConstants.KEY_ATV_TOKEN, null)?.takeIf { it.isNotBlank() }

    // ────────────────────────────────────────────────────────────────────
    // Territory
    // ────────────────────────────────────────────────────────────────────

    val region: Int
        get() = prefs.getInt(PrimeVideoConstants.KEY_REGION, 0)

    val baseUrl: String
        get() = prefs.getString(PrimeVideoConstants.KEY_BASE_URL, "https://www.primevideo.com")
            ?.takeIf { it.isNotBlank() } ?: "https://www.primevideo.com"

    val atvUrl: String
        get() = prefs.getString(PrimeVideoConstants.KEY_ATV_URL, "https://atv-ps.primevideo.com") ?: "https://atv-ps.primevideo.com"

    val siDomain: String
        get() = prefs.getString(PrimeVideoConstants.KEY_SI_DOMAIN, "amazon.com") ?: "amazon.com"

    val marketplaceId: String
        get() = prefs.getString(PrimeVideoConstants.KEY_MID, "ART4WZ8MWBX2Y") ?: "ART4WZ8MWBX2Y"

    val usePrimeVideo: Boolean
        get() = prefs.getString(PrimeVideoConstants.KEY_PV, "false") == "true"

    private val deviceId: String
        get() {
            val d = prefs.getString(PrimeVideoConstants.KEY_DEVICE_ID, null)
            if (d != null) return d
            val newId = UUID.randomUUID().toString().lowercase().replace("-", "")
            prefs.edit { putString(PrimeVideoConstants.KEY_DEVICE_ID, newId) }
            return newId
        }

    val deviceId32: String
        get() {
            val d = deviceId
            if (d.length >= 32) return d.substring(0, 32)
            return d.padEnd(32, '0')
        }

    fun setTerritory(regionIndex: Int) {
        val t = PrimeVideoConstants.TERRITORIES
        if (regionIndex < 0 || regionIndex >= t.size) return
        val entry = t[regionIndex]
        prefs.edit {
            putInt(PrimeVideoConstants.KEY_REGION, regionIndex)
            putString(PrimeVideoConstants.KEY_ATV_URL, entry.atvUrl)
            putString(PrimeVideoConstants.KEY_BASE_URL, entry.baseUrl)
            putString(PrimeVideoConstants.KEY_MID, entry.mid)
            putString(PrimeVideoConstants.KEY_PV, entry.pv.toString())
            putString(PrimeVideoConstants.KEY_SI_DOMAIN, entry.siDomain)
            putString(PrimeVideoConstants.KEY_LANG, entry.lang)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Auth headers for CDP / mobile API
    // ────────────────────────────────────────────────────────────────────

    /** Headers for the mobile/CDP API. */
    suspend fun apiHeaders(): Map<String, String> = authHeaders()

    /**
     * Auth headers for both the CDP API and the web storefront.
     *
     * Amazon's CDP playback endpoints accept a device Bearer token **or** the
     * browser cookie session. The Atza token on its own is not enough: sending
     * only `x-amzn-identity-auth-token` makes GetPlaybackResources answer
     * PRS.InvalidRequest. So we always send the cookies and, when available,
     * the Bearer token and the Atza token as well.
     */
    suspend fun authHeaders(): Map<String, String> {
        val headers = linkedMapOf<String, String>()

        if (!prefs.getString(PrimeVideoConstants.KEY_REFRESH_TOKEN, null).isNullOrBlank()) {
            refreshToken()
            accessToken?.takeIf { it.isNotBlank() }?.let { headers["authorization"] = "Bearer $it" }
        }

        val cookies = cookieJar()
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }

        atvToken?.takeIf { it.isNotBlank() }?.let { token ->
            headers["x-amzn-identity-auth-token"] = token
            headers["x-amzn-identity-auth-domain"] = "api.$siDomain"
        }

        return headers
    }

    /** True when the session comes from browser cookies / the Atza token. */
    val hasCookieSession: Boolean
        get() = !atvToken.isNullOrBlank() || cookieJar().isNotEmpty()

    /**
     * Host used for CDP playback/licensing. Cookie sessions only work against
     * the primevideo.com CDP hosts, so they always use [PrimeVideoConstants.PV_CDP_HOST]
     * regardless of the selected territory.
     */
    val cdpHost: String
        get() = if (hasCookieSession) PrimeVideoConstants.PV_CDP_HOST else atvUrl

    /** Device type for CDP calls: browser sessions authenticate as a web device. */
    val cdpDeviceTypeId: String
        get() = if (hasCookieSession) PrimeVideoConstants.DTID_WEB else PrimeVideoConstants.DTID_ANDROID

    /** Storefront used for HTML scraping (search/detail pages). */
    val webUrl: String
        get() = if (hasCookieSession) PrimeVideoConstants.PV_WEB_URL else baseUrl

    /** Amazon customer id, scraped from the web storefront when available. */
    val customerId: String?
        get() = prefs.getString(PrimeVideoConstants.KEY_CUSTOMER_ID, null)?.takeIf { it.isNotBlank() }

    val accessToken: String?
        get() = prefs.getString(PrimeVideoConstants.KEY_ACCESS_TOKEN, null)

    // ────────────────────────────────────────────────────────────────────
    // Mobile API (CDP getDataByTransform)
    // ────────────────────────────────────────────────────────────────────

    /** Base URL params for mobile/CDP API calls. */
    private fun mobileParams(): String {
        return "deviceTypeID=${PrimeVideoConstants.DTID_ANDROID}" +
            "&firmware=fmw:22-app:3.0.351.3955" +
            "&softwareVersion=351" +
            "&priorityLevel=2" +
            "&format=json" +
            "&featureScheme=mobile-android-features-v13-hdr" +
            "&deviceID=$deviceId32" +
            "&version=1" +
            "&screenWidth=sw1600dp" +
            "&osLocale=en_US" +
            "&uxLocale=en_US" +
            "&supportsPKMZ=false" +
            "&isLiveEventsV2OverrideEnabled=true" +
            "&swiftPriorityLevel=critical" +
            "&supportsCategories=true"
    }

    /**
     * Calls the CDP mobile getDataByTransform endpoint.
     *
     * @param transform the JS transform file (e.g. "dv-android/landing/v1.js")
     * @param extraParams additional query params (e.g. "&pageType=home&pageId=home")
     * @return the JSON response, unwrapped from {"resource": ...} -> resource
     */
    suspend fun getMobilePage(transform: String, extraParams: String = ""): JsonNode {
        val url = "$atvUrl/cdp/mobile/getDataByTransform/v1/$transform" +
            "?${mobileParams()}$extraParams"

        val headers = mapOf(
            "User-Agent" to PrimeVideoConstants.ANDROID_USER_AGENT,
            "Accept" to "application/json",
            "Accept-Language" to "en-US",
        ) + apiHeaders()

        val resp = app.get(url, headers = headers)
        val text = resp.text
        if (text.isNullOrBlank()) {
            throw ErrorLoadingException("Prime Video: mobile API response vacía")
        }

        val json = mapper.readTree(text)
            ?: throw ErrorLoadingException("Prime Video: respuesta inválida de mobile API")

        checkErrors(json)

        // Unwrap the resource envelope
        val resource = json["resource"]?.takeIf { it.isObject }
        if (resource != null) return resource

        return json
    }

    // ────────────────────────────────────────────────────────────────────
    // Web storefront (HTML + hydration JSON)
    // ────────────────────────────────────────────────────────────────────

    private suspend fun webHeaders(): Map<String, String> = linkedMapOf(
        "User-Agent" to PrimeVideoConstants.USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to PrimeVideoConstants.ACCEPT_LANGUAGES,
    ) + authHeaders()

    /**
     * Fetches a storefront page and returns its `init.preparations.body` node
     * (the part that holds `containers`, `atf`/`btf` state, etc.).
     *
     * This is the only detail/search source that works with a browser session:
     * the mobile detail transform needs a `customerId` the mobile API never
     * exposes, and the mobile search transform always answers "no results".
     *
     * @param path absolute URL or path relative to [webUrl]
     */
    suspend fun getWebPageBody(path: String): JsonNode? {
        val url = if (path.startsWith("http")) path else "$webUrl$path"
        val resp = try {
            app.get(url, headers = webHeaders())
        } catch (e: Exception) {
            Log.w("PrimeVideoWeb", "GET $url failed: ${e.message}")
            return null
        }
        if (resp.code !in 200..299) {
            Log.w("PrimeVideoWeb", "GET $url -> HTTP ${resp.code}")
            return null
        }
        val html = resp.text
        if (html.isNullOrBlank()) return null

        // Cache the customer id while we are here; harmless if it fails.
        scriptJson(html, "dv-web-global-store-data")
            ?.get("RequestContext")?.get("customerID")?.asText()
            ?.takeIf { it.isNotBlank() }
            ?.let { cid ->
                if (cid != customerId) prefs.edit { putString(PrimeVideoConstants.KEY_CUSTOMER_ID, cid) }
            }

        val hydration = scriptJson(html, "dv-web-page-hydration-data")
            ?: run {
                Log.w("PrimeVideoWeb", "No hydration data in $url")
                return null
            }
        return hydration["init"]?.get("preparations")?.get("body")
    }

    /** Extracts and parses a `<script id="...">` JSON block. */
    private fun scriptJson(html: String, id: String): JsonNode? {
        val regex = Regex("""<script[^>]*id=["']$id["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val raw = regex.find(html)?.groupValues?.get(1) ?: return null
        return try {
            mapper.readTree(raw)
        } catch (e: Exception) {
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Token refresh
    // ────────────────────────────────────────────────────────────────────

    suspend fun refreshToken(force: Boolean = false): Boolean = refreshMutex.withLock {
        val refresh = prefs.getString(PrimeVideoConstants.KEY_REFRESH_TOKEN, null) ?: return@withLock false
        val expires = prefs.getLong(PrimeVideoConstants.KEY_EXPIRES, 0L)
        if (!force && expires > System.currentTimeMillis() / 1000L) return@withLock true

        val domain = siDomain
        val data = linkedMapOf<String, Any>(
            "domain" to "DeviceLegacy",
            "device_type" to PrimeVideoConstants.DTID_REGISTER,
            "device_serial" to deviceId32,
            "app_name" to PrimeVideoConstants.APP_NAME,
            "app_version" to PrimeVideoConstants.APP_VERSION,
            "device_model" to PrimeVideoConstants.DEVICE_MODEL,
            "os_version" to PrimeVideoConstants.OS_VERSION,
            "source_token_type" to "refresh_token",
            "source_token" to refresh,
            "requested_token_type" to "access_token",
        )

        val headers = linkedMapOf<String, String>(
            "User-Agent" to PrimeVideoConstants.USER_AGENT,
            "x-amzn-identity-auth-domain" to "api.$domain",
            "Accept-Language" to "en-US",
            "x-amzn-requestid" to UUID.randomUUID().toString().replace("-", ""),
            "Content-Type" to "application/json",
        )

        try {
            val resp = app.post("https://api.$domain/auth/token", headers = headers, json = data)
            val json = mapper.readTree(resp.text) ?: return@withLock false
            if (json.has("access_token")) {
                val now = System.currentTimeMillis() / 1000L
                val expiresIn = json["expires_in"]?.asLong() ?: 3600L
                prefs.edit {
                    putString(PrimeVideoConstants.KEY_ACCESS_TOKEN, json["access_token"].asText())
                    putLong(PrimeVideoConstants.KEY_EXPIRES, now + expiresIn - 15)
                }
                json["tokens"]?.get("bearer")?.get("refresh_token")?.asText()?.takeIf { it.isNotBlank() }
                    ?.let { newRefresh ->
                        prefs.edit { putString(PrimeVideoConstants.KEY_REFRESH_TOKEN, newRefresh) }
                    }
                json["refresh_token"]?.asText()?.takeIf { it.isNotBlank() }
                    ?.let { newRefresh ->
                        prefs.edit { putString(PrimeVideoConstants.KEY_REFRESH_TOKEN, newRefresh) }
                    }
                return@withLock true
            }
            Log.w("PrimeVideoAuth", "refreshToken failed: ${resp.text?.substringBefore("}") ?: "unknown"}...")
            return@withLock false
        } catch (e: Exception) {
            Log.e("PrimeVideoAuth", "refreshToken threw: ${e.message}")
            return@withLock false
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Import session (cookies, ATV token, or refresh token)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Import cookies from the user's browser session.
     * Extracts at-main-av as the ATV token for mobile API when present.
     */
    suspend fun importCookies(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw ErrorLoadingException("Pega las cookies del navegador.")

        val rawCookies = linkedMapOf<String, String>()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = try { mapper.readTree(trimmed) } catch (e: Exception) { null }
            val node = when {
                parsed == null -> null
                parsed.isObject -> parsed
                parsed.isArray && parsed.size() > 0 -> parsed[0]
                else -> null
            }
            if (node != null && node.isObject) {
                val cookieNode = node["cookie"]?.takeIf { it.isObject } ?: node
                cookieNode.fields().forEach { (k, v) ->
                    v?.asText()?.takeIf { it.isNotBlank() }?.let { rawCookies[k] = it }
                }
            }
        } else {
            val parts = trimmed.split(";")
            for (part in parts) {
                val eq = part.indexOf("=")
                if (eq > 0) {
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim()
                    if (k.isNotBlank() && v.isNotBlank()) rawCookies[k] = v
                }
            }
        }

        if (rawCookies.isEmpty()) throw ErrorLoadingException("No se pudieron extraer cookies.")

        // Normaliza sufijos -main <-> -main-av
        val cookieMap = linkedMapOf<String, String>()
        var foundAtvToken = false
        for ((k, v) in rawCookies) {
            val value = v.trim().removeSurrounding("\"").removeSurrounding("'")
            if (value.isBlank()) continue
            cookieMap[k] = value
            when {
                k.endsWith("-main-av") -> cookieMap[k.removeSuffix("-av")] = value
                k.endsWith("-main") -> cookieMap["$k-av"] = value
            }
            // Extract at-main-av as ATV token for mobile API
            if (k == "at-main-av" && value.startsWith("Atza|")) {
                prefs.edit { putString(PrimeVideoConstants.KEY_ATV_TOKEN, value) }
                foundAtvToken = true
                Log.i("PrimeVideoAuth", "ATV token extracted from at-main-av cookie")
            }
        }

        prefs.edit {
            putString(PrimeVideoConstants.KEY_COOKIES, mapper.writeValueAsString(cookieMap))
        }

        if (!foundAtvToken) {
            Log.w("PrimeVideoAuth", "No at-main-av cookie found. Mobile API won't work without it.")
        }

        // Best effort: caches the customer id and confirms the cookie session
        // against the storefront.
        runCatching { getWebPageBody("/") }

        validateSession()
    }

    /** Import an ATV token (at-main-av value) directly. */
    suspend fun importAtvToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isBlank()) throw ErrorLoadingException("Pega el token Atza|...")
        if (!trimmed.startsWith("Atza|")) {
            throw ErrorLoadingException("Token debe empezar con 'Atza|'. Pega el valor de la cookie 'at-main-av'.")
        }
        prefs.edit { putString(PrimeVideoConstants.KEY_ATV_TOKEN, trimmed) }
        validateSession()
    }

    suspend fun importTokens(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw ErrorLoadingException("Pega el token de sesión.")

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = try { mapper.readTree(trimmed) } catch (e: Exception) { null }
            val node = when {
                parsed == null -> null
                parsed.isObject -> parsed
                parsed.isArray && parsed.size() > 0 -> parsed[0]
                else -> null
            }
            if (node != null && node.isObject) {
                val refreshCandidates = listOf(
                    node["refresh_token"],
                    node["tokens"]?.get("bearer")?.get("refresh_token"),
                    node["token"]?.get("refresh"),
                    node["token"]?.get("refresh_token"),
                    node["bearer"]?.get("refresh_token"),
                )
                val refresh = refreshCandidates.firstOrNull { !it?.asText().isNullOrBlank() }?.asText()
                val accessCandidates = listOf(
                    node["access_token"],
                    node["tokens"]?.get("bearer")?.get("access_token"),
                    node["token"]?.get("access"),
                    node["token"]?.get("access_token"),
                    node["bearer"]?.get("access_token"),
                )
                val access = accessCandidates.firstOrNull { !it?.asText().isNullOrBlank() }?.asText()

                if (refresh.isNullOrBlank() && access.isNullOrBlank()) {
                    throw ErrorLoadingException(
                        "JSON sin token. Busca 'refresh_token' o 'token.refresh' en el archivo.",
                    )
                }
                prefs.edit {
                    if (!refresh.isNullOrBlank()) {
                        putString(PrimeVideoConstants.KEY_REFRESH_TOKEN, refresh)
                        putLong(PrimeVideoConstants.KEY_EXPIRES, 0L)
                    }
                    if (!access.isNullOrBlank()) {
                        putString(PrimeVideoConstants.KEY_ACCESS_TOKEN, access)
                    }
                }
            }
        } else {
            prefs.edit {
                putString(PrimeVideoConstants.KEY_REFRESH_TOKEN, trimmed)
                remove(PrimeVideoConstants.KEY_ACCESS_TOKEN)
                putLong(PrimeVideoConstants.KEY_EXPIRES, 0L)
            }
        }

        validateSession()
    }

    private suspend fun validateSession() {
        // Try mobile API first (fastest check)
        val token = atvToken
        if (!token.isNullOrBlank()) {
            try {
                val result = getMobilePage("dv-android/landing/v1.js", "&pageType=home&pageId=home")
                if (result != null && result.has("collections")) return
            } catch (e: Exception) {
                Log.w("PrimeVideoAuth", "Mobile API validation failed: ${e.message}")
            }
        }

        // Fallback: try page fetch with cookies
        val ok = try {
            val headers = authHeaders()
            val testUrl = if (usePrimeVideo) "$baseUrl/storefront" else "$baseUrl/gp/video/storefront"
            val resp = runCatching { app.get(testUrl, headers = headers) }
            val text = resp.getOrNull()?.text ?: ""
            text.contains("body", ignoreCase = true) || text.contains("containers", ignoreCase = true)
        } catch (e: Exception) { false }
        if (!ok) throw ErrorLoadingException("La sesión no es válida o expiró. Vuelve a copiarla del navegador.")
    }

    fun logout() {
        prefs.edit {
            remove(PrimeVideoConstants.KEY_REFRESH_TOKEN)
            remove(PrimeVideoConstants.KEY_ACCESS_TOKEN)
            remove(PrimeVideoConstants.KEY_EXPIRES)
            remove(PrimeVideoConstants.KEY_COOKIES)
            remove(PrimeVideoConstants.KEY_ATV_TOKEN)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Playback resources (CDP API)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Calls the GetPlaybackResources CDP endpoint.
     */
    suspend fun getPlaybackResources(
        asin: String,
        isLive: Boolean = false,
        extraParams: Map<String, String> = emptyMap(),
    ): JsonNode {
        var url = "$cdpHost/cdp/catalog/GetPlaybackResources" +
            "?asin=$asin" +
            "&deviceTypeID=$cdpDeviceTypeId" +
            "&firmware=1" +
            "&deviceID=$deviceId32" +
            "&marketplaceID=$marketplaceId" +
            "&format=json" +
            "&version=2" +
            "&gascEnabled=${usePrimeVideo.toString().lowercase()}" +
            "&subtitleFormat=TTMLv2" +
            "&resourceUsage=ImmediateConsumption" +
            "&consumptionType=Streaming" +
            "&deviceDrmOverride=CENC" +
            "&deviceStreamingTechnologyOverride=DASH" +
            "&deviceProtocolOverride=Https" +
            "&deviceBitrateAdaptationsOverride=CVBR%2CCBR" +
            "&audioTrackId=all" +
            "&languageFeature=MLFv2"

        if (isLive) {
            url += "&liveManifestType=accumulating,live" +
                "&playerType=xp" +
                "&playerAttributes=%7B%22frameRate%22%3A%22HFR%22%7D" +
                "&deviceFrameRateOverride=High"
        }

        extraParams.forEach { (k, v) -> url += "&${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

        val headers = PrimeVideoConstants.androidHeaders() +
            authHeaders() +
            mapOf("accept" to "application/json")
        // Sends POST with empty body (Kodi plugin uses postdata='')
        val resp = app.post(url, headers = headers)
        val json = mapper.readTree(resp.text)
            ?: throw ErrorLoadingException("Prime Video: CDP response vacía en $asin")
        checkErrors(json)
        return json
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private fun checkErrors(json: JsonNode?) {
        if (json == null) return
        // Nested: {"error":{"errorCode":"...","message":"..."}} — common in CDP
        val errNode = json["error"]?.takeIf { it.isObject }
        errNode?.get("errorCode")?.asText()?.takeIf { it.isNotBlank() }?.let { code ->
            val msg = errNode["message"]?.asText() ?: json["message"]?.asText() ?: code
            throw ErrorLoadingException("Prime Video: $msg ($code)")
        }
        // Flat: {"errorCode":"...","message":"..."}
        json["errorCode"]?.asText()?.takeIf { it.isNotBlank() }?.let { code ->
            val msg = json["message"]?.asText() ?: json["errorDescription"]?.asText() ?: code
            throw ErrorLoadingException("Prime Video: $msg ($code)")
        }
        json["errors"]?.let { errors ->
            if (errors.isArray && errors.size() > 0) {
                val err = errors[0]
                val code = err["code"]?.asText() ?: err["message"]?.asText() ?: "unknown"
                throw ErrorLoadingException("Prime Video: $code")
            }
        }
        // Per-resource failures, e.g. {"errorsByResource":{"PlaybackUrls":{...}}}
        json["errorsByResource"]?.takeIf { it.isObject }?.fields()?.forEach { (resource, err) ->
            val code = err["errorCode"]?.asText()?.takeIf { it.isNotBlank() } ?: return@forEach
            val msg = err["message"]?.asText() ?: code
            throw ErrorLoadingException("Prime Video: $msg ($code, $resource)")
        }
        // Mobile API: metadata.message
        json["metadata"]?.get("message")?.asText()?.takeIf { it.isNotBlank() }?.let { msg ->
            throw ErrorLoadingException("Prime Video: $msg")
        }
    }

    /** Extracts GTI or ASIN from a Prime Video ID/URL. */
    fun extractAsin(from: String): String {
        // GTI format: amzn1.dv.gti.xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        val gtiMatch = Regex("""amzn1\.dv\.gti\.([a-f0-9-]{36})""").find(from)
        if (gtiMatch != null) return gtiMatch.groupValues.get(1)

        // Standard ASIN: B0 + 9 alphanumeric (10 chars)
        val asinMatch = Regex("""[?&]asin=([A-Z0-9]{10})""").find(from)
        if (asinMatch != null) return asinMatch.groupValues.get(1)

        // URL path segment: /detail/B0... or /something/B0...
        val pathMatch = Regex("""/[^/]+/([A-Z0-9]{10})(?:[?/#]|$)""").find(from)
        if (pathMatch != null) return pathMatch.groupValues.get(1)

        // GTI in plain text
        val plainGti = Regex("""amzn1\.dv\.gti\.[a-f0-9-]{36}""").find(from)
        if (plainGti != null) return plainGti.groupValues.get(0)

        return from.trim()
    }

    /** Checks whether a /detail/ url or ASIN appears to be a live event. */
    fun isLiveUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("live") || lower.contains("event")
    }

    val email: String?
        get() = prefs.getString(PrimeVideoConstants.KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?.let { "••••" }
            ?: atvToken?.takeIf { it.isNotBlank() }?.let { "ATV token presente" }
            ?: cookieJar()["x-amzn-userid"]?.takeIf { it.isNotBlank() }
            ?: null

    private fun cookieJar(): Map<String, String> {
        val raw = prefs.getString(PrimeVideoConstants.KEY_COOKIES, null) ?: return emptyMap()
        return try {
            val node = mapper.readTree(raw)
            val map = linkedMapOf<String, String>()
            if (node != null && node.isObject) {
                node.fields().forEach { (k, v) ->
                    v?.asText()?.takeIf { it.isNotBlank() }?.let { map[k] = it }
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

internal object Log {
    fun d(tag: String, msg: String) = android.util.Log.d(tag, msg)
    fun w(tag: String, msg: String) = android.util.Log.w(tag, msg)
    fun e(tag: String, msg: String) = android.util.Log.e(tag, msg)
    fun i(tag: String, msg: String) = android.util.Log.i(tag, msg)
}