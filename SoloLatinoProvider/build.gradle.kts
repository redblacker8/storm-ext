// use an integer for version numbers
version = 9


cloudstream {
    language = "mx"
    // All of these properties are optional, you can safely remove them

    description = "Peliculas, series, animes y cartoons en Español y Español Latio"
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
        "Anime",
        "Cartoon",
    )

    iconUrl = "https://sololatino.net/wp-content/uploads/2020/10/cropped-logo-final-192x192.png"
}
