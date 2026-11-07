// use an integer for version numbers
version = 2


cloudstream {
    language = "mx"
    // All of these properties are optional, you can safely remove them

    //description = "Lorem Ipsum"
    authors = listOf("redblacker8")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 0 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
    )

    iconUrl = "https://reydonghua.org/public/favicon.ico?v=4.71"
}
