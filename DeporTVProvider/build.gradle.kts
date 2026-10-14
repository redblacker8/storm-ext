// use an integer for version numbers
version = 32


cloudstream {
    language = "mx"
    // All of these properties are optional, you can safely remove them

    description = "Deportes eventos en vivo. Sports live events. Futbol. Soccer."
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
        "Live",
    )

    iconUrl = "https://yt3.googleusercontent.com/T_d2j2xZMjAxPCehiFR6hAv7jE3swcUzfgV8wCXzv1IL7rCEDv3cgQtIxjdmLVyP6ZrSgIu0nw=s900-c-k-c0x00ffffff-no-rj"
}
