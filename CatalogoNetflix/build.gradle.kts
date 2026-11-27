version = 6

cloudstream {
    language = "mx"

    description = "Catálogo Netflix México/LATAM en español: popular, mejor calificada, estrenos y géneros. No aloja fuentes: busca contenido con la lupa del detalle."
    authors = listOf("redblacker8")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.netflix.com&sz=%size%"
}

dependencies {
    // BottomSheetDialogFragment for the "Fuentes permitidas" settings panel.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
