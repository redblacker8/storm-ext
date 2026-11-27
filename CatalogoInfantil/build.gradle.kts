// use an integer for version numbers
version = 6

cloudstream {
    language = "mx"
    // All of these properties are optional, you can safely remove them

    description = "Catálogo infantil y familiar clasificación AA: tendencias, películas, series, animación y aventura. No aloja fuentes: busca contenido con la lupa del detalle."
    authors = listOf("redblacker8")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.themoviedb.org&sz=%size%"
}
