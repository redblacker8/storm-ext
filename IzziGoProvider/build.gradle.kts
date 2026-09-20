// use an integer for version numbers
version = 15

cloudstream {
    language = "mx"
    description = "izzi go (izzi telecom, México). REQUIERE una cuenta izzi go con suscripción activa. Inicia sesión en: Ajustes del proveedor (icono de engranaje) -> izzi go Login."
    authors = listOf("redblacker8")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // Beta only
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Live",
    )

    iconUrl = "https://www.izzigo.tv/webclient/img/favicon/apple-touch-icon.png"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
