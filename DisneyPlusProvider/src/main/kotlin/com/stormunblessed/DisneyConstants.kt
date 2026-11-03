package com.stormunblessed

object DisneyConstants {
    const val APP_VERSION = "26.6.0+rc5-2026.04.21.0"
    const val CLIENT_VERSION = "19.0.0"
    const val SDK_VERSION = "7.0"
    const val EXPLORE_VERSION = "v1.10"
    const val CLIENT_ID = "disney-svod-3d9324fc"
    const val API_KEY = "ZGlzbmV5JmFuZHJvaWQmMS4wLjA.bkeb0m230uUhv8qrAXuNu39tbE_mD5EEhM_NAcohjyA"
    const val CONFIG_URL = "https://client-sdk-configs.bamgrid.com/bam-sdk/v$SDK_VERSION/$CLIENT_ID/android/v$CLIENT_VERSION/google/tv/prod.json"
    const val BAM_PARTNER = "disney"
    const val IMAGE_URL = "https://disney.images.edge.bamgrid.com/ripcut-delivery/v2/variant/disney"

    const val KEY_EMAIL = "email"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_EXPIRES = "expires"
    const val KEY_PROFILE_PIN = "profile_pin"

    fun userAgent(): String =
        "BAMSDK/v$CLIENT_VERSION ($CLIENT_ID $APP_VERSION; v$SDK_VERSION/v$CLIENT_VERSION; android; tv)"
}
