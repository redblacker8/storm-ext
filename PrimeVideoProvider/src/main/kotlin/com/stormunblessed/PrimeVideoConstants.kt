package com.stormunblessed

object PrimeVideoConstants {
    // Prefs keys
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_EXPIRES = "expires"
    const val KEY_COOKIES = "cookies" // JSON map of cookie name → value
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_REGION = "region"
    const val KEY_ATV_URL = "atvurl"
    const val KEY_BASE_URL = "baseurl"
    const val KEY_MID = "mid"
    const val KEY_PV = "pv" // primevideo.com domain?
    const val KEY_SI_DOMAIN = "sidomain"
    const val KEY_LANG = "lang"
    /** ATV auth token from at-main-av cookie (Atza|...) for mobile API */
    const val KEY_ATV_TOKEN = "atv_token"
    /** Amazon customer id (AUS...) scraped from the web storefront */
    const val KEY_CUSTOMER_ID = "customer_id"

    /**
     * CDP host for playback/licensing. Browser cookie sessions are only accepted
     * by the primevideo.com hosts; the atv-ps*.amazon.* hosts answer
     * PRS.InvalidRequest for them.
     */
    const val PV_CDP_HOST = "https://atv-ps.primevideo.com"
    /** Storefront used for HTML scraping (search/detail). */
    const val PV_WEB_URL = "https://www.primevideo.com"

    // Device type IDs
    /** Registration / token refresh device type ID (login.py line 76) */
    val DTID_REGISTER = "A28RQHJKHM2A2W"
    /** Android TV device for playback (common.py dtid_android) */
    val DTID_ANDROID = "A43PXU4ZN2AL1"
    /** Web browser device (common.py dtid_web) */
    val DTID_WEB = "AOAGZA014O5RE"

    // Default territory entries (mirrors login.py:getTerritory)
    data class Territory(
        val atvUrl: String,
        val baseUrl: String,
        val mid: String,
        val pv: Boolean,
        val locale: String,
        val lang: String,
        val siDomain: String,
    )

    val TERRITORIES = listOf(
        // 0 = Auto. El plugin de Kodi llama a GetAppStartupConfig para geo-detectarlo.
        // Nosotros usamos amazon.com (US) como default sensato hasta implementar eso.
        Territory("https://atv-ps.amazon.com", "https://www.amazon.com", "ATVPDKIKX0DER", false, "us", "en_US", "amazon.com"),
        Territory("https://atv-ps-eu.amazon.de", "https://www.amazon.de", "A1PA6795UKMFR9", false, "de", "de_DE", "amazon.de"),
        Territory("https://atv-ps-eu.amazon.co.uk", "https://www.amazon.co.uk", "A1F83G8C2ARO7P", false, "uk", "en_US", "amazon.co.uk"),
        Territory("https://atv-ps.amazon.com", "https://www.amazon.com", "ATVPDKIKX0DER", false, "us", "en_US", "amazon.com"),
        Territory("https://atv-ps-fe.amazon.co.jp", "https://www.amazon.co.jp", "A1VC38T7YXB528", false, "jp", "ja_JP", "amazon.co.jp"),
        Territory("https://atv-ps-eu.primevideo.com", "https://www.primevideo.com", "A3K6Y4MI8GDYMT", true, "us", "en_US", "amazon.com"),
        Territory("https://atv-ps-eu.primevideo.com", "https://www.primevideo.com", "A2MFUE2XK8ZSSY", true, "us", "en_US", "amazon.com"),
        Territory("https://atv-ps-fe.primevideo.com", "https://www.primevideo.com", "A15PK738MTQHSO", true, "us", "en_US", "amazon.com"),
        Territory("https://atv-ps.primevideo.com", "https://www.primevideo.com", "ART4WZ8MWBX2Y", true, "us", "en_US", "amazon.com"),
    )

    // Android device registration data
    val APP_NAME = "com.amazon.avod.thirdpartyclient"
    val APP_VERSION = "296016847"
    val DEVICE_MODEL = "mdarcy/nvidia/SHIELD Android TV"
    val OS_VERSION = "NVIDIA/mdarcy/mdarcy:11/RQ1A.210105.003/7094531_2971.7725:user/release-keys"

    val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    /** Dalvik UA used by the plugin's headers_android for CDP playback requests */
    val ANDROID_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 11; SHIELD Android TV RQ1A.210105.003)"
    val ACCEPT_LANGUAGES = "en-US,en;q=0.9,es;q=0.8"
    val ACCEPT_ENCODING = "gzip, deflate, br"

    /** Full header set expected by Amazon's CDN for Android/ATV playback. */
    fun androidHeaders(): Map<String, String> = mapOf(
        "Accept-Charset" to "utf-8",
        "User-Agent" to ANDROID_USER_AGENT,
        "X-Requested-With" to APP_NAME,
        "x-gasc-enabled" to "true",
    )

    fun deviceData(deviceId: String): Map<String, Any> = mapOf(
        "domain" to "DeviceLegacy",
        "device_type" to DTID_ANDROID,
        "device_serial" to deviceId,
        "app_name" to APP_NAME,
        "app_version" to APP_VERSION,
        "device_model" to DEVICE_MODEL,
        "os_version" to OS_VERSION,
    )

    val WIDEVINE_UUID = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
}