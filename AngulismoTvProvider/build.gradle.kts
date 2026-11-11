// use an integer for version numbers
version = 1

cloudstream {
    language = "mx"
    // All of these properties are optional, you can safely remove them

    description = "Agenda deportiva y canales en vivo desde AngulismoTV."
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

    iconUrl = "https://angulismotv.pages.dev/assets/logo.png"
}
