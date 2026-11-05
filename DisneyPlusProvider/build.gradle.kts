// use an integer for version numbers
version = 3

cloudstream {
    language = "mx"
    description = "Disney+ (contenido oficial). REQUIERE una cuenta Disney+ con suscripción activa. Inicia sesión en: Ajustes del proveedor (icono de engranaje) -> Disney+ Login."
    authors = listOf("redblacker8")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Live",
    )

    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3e/Disney%2B_logo.svg/2560px-Disney%2B_logo.svg.png"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
