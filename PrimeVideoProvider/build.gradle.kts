// use an integer for version numbers
version = 1

cloudstream {
    language = "mx"
    description = "Prime Video (contenido oficial). REQUIERE una cuenta Prime Video activa. " +
        "Inicia sesión en: Ajustes del proveedor (icono de engranaje) -> Prime Video Login."
    authors = listOf("redblacker8")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Live",
    )

    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fb/Prime_Video_logo.svg/1280px-Prime_Video_logo.svg.png"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
