version = 2

cloudstream {
    language = "mx"

    description = "Catálogo general de TMDB en español: tendencias, populares, estrenos y géneros. Delega reproducción a otros proveedores instalados."
    authors = listOf("redblacker8")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.themoviedb.org&sz=%size%"
}

dependencies {
    // BottomSheetDialogFragment for the "Fuentes permitidas" settings panel.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
