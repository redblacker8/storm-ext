package com.stormunblessed

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.movieproviders.transformHourToLocal
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StreamedInfo {
    var mainUrl = "https://sportsbite.org"
    var matches: List<SBMatch> = emptyList()
    var matchImages: Map<String, String> = emptyMap()

    suspend fun init() {
        // streamed.pk is gone; sportsbite.org serves the same match data (plus team logos).
        val loaded = runCatching {
            val res = app.get("$mainUrl/api/forestgump/matches")
            AppUtils.tryParseJson<SportsBiteResponse>(res.text)?.days?.flatMap { it.events.orEmpty() }
        }.getOrNull()
        this.matches = loaded ?: emptyList()
        this.matchImages = runCatching { fetchImages(this.matches) }.getOrNull() ?: emptyMap()
    }

    /**
     * POST /api/event-images/batch -> team/league logos per event id.
     * The batch "poster" field is a resize-proxy url that currently 404s, so prefer
     * the plain /img/c/<hash>.webp team badges which serve fine.
     */
    private suspend fun fetchImages(events: List<SBMatch>): Map<String, String> {
        if (events.isEmpty()) return emptyMap()
        val body = EventImagesRequest(
            events.map {
                EventImageRequest(
                    id = it.imageId(),
                    title = it.title,
                    league = it.category,
                    sport = it.sport,
                    home = it.teams?.home?.name.orEmpty(),
                    away = it.teams?.away?.name.orEmpty(),
                )
            }
        ).toJson()
        val res = app.post(
            "$mainUrl/api/event-images/batch",
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf("Accept" to "application/json")
        )
        return AppUtils.tryParseJson<EventImagesResponse>(res.text)?.images?.mapNotNull { (id, img) ->
            (img.homeLogo ?: img.awayLogo)
                ?.let { logo -> if (logo.startsWith("http")) logo else "$mainUrl$logo" }
                ?.let { id to it }
        }?.toMap() ?: emptyMap()
    }

    private val translation = mapOf(
        // === NATIONAL TEAMS (countries) ===
        "espana" to "spain",
        "espanola" to "spain",
        "inglaterra" to "england",
        "francia" to "france",
        "alemania" to "germany",
        "alemana" to "germany",
        "italia" to "italy",
        "portugal" to "portugal",
        "portuguesa" to "portugal",
        "paises bajos" to "netherlands",
        "holanda" to "netherlands",
        "belgica" to "belgium",
        "suiza" to "switzerland",
        "helvetica" to "switzerland",
        "suecia" to "sweden",
        "dinamarca" to "denmark",
        "noruega" to "norway",
        "polonia" to "poland",
        "rusia" to "russia",
        "ucrania" to "ukraine",
        "turquia" to "turkey",
        "grecia" to "greece",
        "croacia" to "croatia",
        "serbia" to "serbia",
        "rumania" to "romania",
        "hungria" to "hungary",
        "austria" to "austria",
        "republica checa" to "czech republic",
        "checa" to "czech",
        "eslovaquia" to "slovakia",
        "eslovenia" to "slovenia",
        "bosnia" to "bosnia",
        "montenegro" to "montenegro",
        "macedonia" to "north macedonia",
        "albania" to "albania",
        "kosovo" to "kosovo",
        "irlanda" to "ireland",
        "escocia" to "scotland",
        "gales" to "wales",
        "irlanda del norte" to "northern ireland",
        "japon" to "japan",
        "corea" to "south korea",
        "corea del sur" to "south korea",
        "china" to "china",
        "india" to "india",
        "arabia saudita" to "saudi arabia",
        "iran" to "iran",
        "irak" to "iraq",
        "catar" to "qatar",
        "emiratos arabes" to "united arab emirates",
        "israel" to "israel",
        "australia" to "australia",
        "nueva zelanda" to "new zealand",
        "brasil" to "brazil",
        "argentina" to "argentina",
        "uruguay" to "uruguay",
        "paraguay" to "paraguay",
        "colombia" to "colombia",
        "chile" to "chile",
        "peru" to "peru",
        "bolivia" to "bolivia",
        "ecuador" to "ecuador",
        "venezuela" to "venezuela",
        "estados unidos" to "united states",
        "eeuu" to "united states",
        "usa" to "united states",
        "mexico" to "mexico",
        "canada" to "canada",
        "costa rica" to "costa rica",
        "panama" to "panama",
        "honduras" to "honduras",
        "el salvador" to "el salvador",
        "guatemala" to "guatemala",
        "jamaica" to "jamaica",
        "cuba" to "cuba",
        "haiti" to "haiti",
        "republica dominicana" to "dominican republic",
        "trinidad y tobago" to "trinidad and tobago",
        "marruecos" to "morocco",
        "argelia" to "algeria",
        "tunez" to "tunisia",
        "egipto" to "egypt",
        "costa de marfil" to "ivory coast",
        "camerun" to "cameroon",
        "senegal" to "senegal",
        "nigeria" to "nigeria",
        "ghana" to "ghana",
        "sudafrica" to "south africa",
        "angola" to "angola",
        "cabo verde" to "cape verde",
        "rd congo" to "dr congo",
        "congo" to "congo",
        "guinea ecuatorial" to "equatorial guinea",
        "mali" to "mali",
        "burkina faso" to "burkina faso",
        "zambia" to "zambia",
        "zimbabue" to "zimbabwe",
        "etiopia" to "ethiopia",
        "kenia" to "kenya",
        "uganda" to "uganda",
        "tanzania" to "tanzania",
        "mozambique" to "mozambique",
        "madagascar" to "madagascar",
        "mauritania" to "mauritania",
        "niger" to "niger",
        "chad" to "chad",
        "sudan" to "sudan",
        "sierra leona" to "sierra leone",
        "liberia" to "liberia",
        "guinea" to "guinea",
        "finlandia" to "finland",
        "islandia" to "iceland",
        "letonia" to "latvia",
        "lituania" to "lithuania",
        "estonia" to "estonia",
        "bielorrusia" to "belarus",
        "moldavia" to "moldova",
        "georgia" to "georgia",
        "armenia" to "armenia",
        "azerbaiyan" to "azerbaijan",
        "kazajistan" to "kazakhstan",
        "uzbekistan" to "uzbekistan",
        "luxemburgo" to "luxembourg",
        "malta" to "malta",
        "chipre" to "cyprus",
        "liechtenstein" to "liechtenstein",
        "san marino" to "san marino",
        "andorra" to "andorra",
        "islas feroe" to "faroe islands",
        "gibraltar" to "gibraltar",
        "tailandia" to "thailand",
        "vietnam" to "vietnam",
        "indonesia" to "indonesia",
        "malasia" to "malaysia",
        "filipinas" to "philippines",
        "singapur" to "singapore",
        "pakistan" to "pakistan",
        "bangladesh" to "bangladesh",
        "barein" to "bahrain",
        "oman" to "oman",
        "kuwait" to "kuwait",
        "jordania" to "jordan",
        "libano" to "lebanon",
        "siria" to "syria",
        "yemen" to "yemen",
        "corea del norte" to "north korea",

        // === SOCCER CLUB TEAMS ===

        // Spanish→English specific mismatches
        "napoles" to "napoli",
        "oporto" to "porto",
        "psg" to "paris saint germain",
        "b. munich" to "bayern munich",
        "b munich" to "bayern munich",
        "bayer munich" to "bayern munich",
        "inter de milan" to "inter milan",
        "ac milan" to "milan",
        "atletico de madrid" to "atletico madrid",
        "barcelona sc" to "barcelona sporting club",
        "colonia" to "koln",
        "friburgo" to "freiburg",
        "wolfsburgo" to "wolfsburg",
        "augsburgo" to "augsburg",
        "munch" to "munich",
        "munich" to "munich",
        "pumas" to "unam",
        "chivas" to "guadalajara",
        "tigres" to "tigres uanl",
        "rayados" to "monterrey",
        "boca" to "boca juniors",
        "river" to "river plate",
        "penarol" to "penarol",
        "colo colo" to "colo-colo",
        "ldu" to "ldu quito",
        "liga de quito" to "ldu quito",
        "junior" to "atletico junior",
        "medellin" to "independiente medellin",
        "cali" to "deportivo cali",
        "tolima" to "deportes tolima",
        "santa fe" to "independiente santa fe",
        "equidad" to "la equidad",
        "millonarios" to "millonarios",
        "nacional" to "nacional",
        "bologna" to "bologna",
        "bolonia" to "bologna",
        "turin" to "torino",
        "torino" to "torino",
        "genova" to "genoa",
        "genoa" to "genoa",
        "napoli" to "napoli",
        "lieja" to "standard liege",
        "brujas" to "club brugge",
        "brugge" to "club brugge",
        "copenhague" to "fc copenhagen",
        "copenhagen" to "fc copenhagen",
        "estrela roja" to "red star belgrade",
        "crvena zvezda" to "red star belgrade",
        "ajax" to "ajax",
        "eindhoven" to "psv eindhoven",
        "sporting" to "sporting cp",
        "sporting lisboa" to "sporting cp",
        "shakhtar" to "shakhtar donetsk",
        "dinamo kiev" to "dynamo kyiv",
        "dynamo kiev" to "dynamo kyiv",
        "slavia praga" to "slavia prague",
        "sparta praga" to "sparta prague",
        "viktoria plzen" to "viktoria plzen",
        "olimpia" to "olympiacos",
        "olympiacos" to "olympiacos",
        "midtjylland" to "fc midtjylland",
        "bodo glimt" to "bodo/glimt",

        // === NBA ===
        "lakers" to "los angeles lakers",
        "la lakers" to "los angeles lakers",
        "celtics" to "boston celtics",
        "bos" to "boston celtics",
        "warriors" to "golden state warriors",
        "gs" to "golden state warriors",
        "gsw" to "golden state warriors",
        "bulls" to "chicago bulls",
        "chi" to "chicago bulls",
        "heat" to "miami heat",
        "mia" to "miami heat",
        "nuggets" to "denver nuggets",
        "den" to "denver nuggets",
        "bucks" to "milwaukee bucks",
        "mil" to "milwaukee bucks",
        "sixers" to "philadelphia 76ers",
        "76ers" to "philadelphia 76ers",
        "phi" to "philadelphia 76ers",
        "cavaliers" to "cleveland cavaliers",
        "cavs" to "cleveland cavaliers",
        "cle" to "cleveland cavaliers",
        "knicks" to "new york knicks",
        "nyk" to "new york knicks",
        "nets" to "brooklyn nets",
        "bkn" to "brooklyn nets",
        "raptors" to "toronto raptors",
        "tor" to "toronto raptors",
        "suns" to "phoenix suns",
        "phx" to "phoenix suns",
        "clippers" to "la clippers",
        "lac" to "la clippers",
        "spurs" to "san antonio spurs",
        "sas" to "san antonio spurs",
        "grizzlies" to "memphis grizzlies",
        "mem" to "memphis grizzlies",
        "pelicans" to "new orleans pelicans",
        "nop" to "new orleans pelicans",
        "kings" to "sacramento kings",
        "sac" to "sacramento kings",
        "mavericks" to "dallas mavericks",
        "mavs" to "dallas mavericks",
        "dal" to "dallas mavericks",
        "rockets" to "houston rockets",
        "hou" to "houston rockets",
        "jazz" to "utah jazz",
        "uth" to "utah jazz",
        "thunder" to "oklahoma city thunder",
        "okc" to "oklahoma city thunder",
        "timberwolves" to "minnesota timberwolves",
        "t-wolves" to "minnesota timberwolves",
        "blazers" to "portland trail blazers",
        "trail blazers" to "portland trail blazers",
        "por" to "portland trail blazers",
        "pacers" to "indiana pacers",
        "ind" to "indiana pacers",
        "hornets" to "charlotte hornets",
        "cha" to "charlotte hornets",
        "hawks" to "atlanta hawks",
        "atl" to "atlanta hawks",
        "magic" to "orlando magic",
        "orl" to "orlando magic",
        "pistons" to "detroit pistons",
        "det" to "detroit pistons",
        "wizards" to "washington wizards",
        "was" to "washington wizards",

        // === NFL ===
        "chiefs" to "kansas city chiefs",
        "kc" to "kansas city chiefs",
        "49ers" to "san francisco 49ers",
        "eagles" to "philadelphia eagles",
        "cowboys" to "dallas cowboys",
        "dal" to "dallas cowboys",
        "patriots" to "new england patriots",
        "ne" to "new england patriots",
        "packers" to "green bay packers",
        "gb" to "green bay packers",
        "steelers" to "pittsburgh steelers",
        "pit" to "pittsburgh steelers",
        "ravens" to "baltimore ravens",
        "bal" to "baltimore ravens",
        "bills" to "buffalo bills",
        "buf" to "buffalo bills",
        "bengals" to "cincinnati bengals",
        "cin" to "cincinnati bengals",
        "browns" to "cleveland browns",
        "cle" to "cleveland browns",
        "broncos" to "denver broncos",
        "den" to "denver broncos",
        "lions" to "detroit lions",
        "det" to "detroit lions",
        "texans" to "houston texans",
        "hou" to "houston texans",
        "colts" to "indianapolis colts",
        "ind" to "indianapolis colts",
        "jaguars" to "jacksonville jaguars",
        "jax" to "jacksonville jaguars",
        "raiders" to "las vegas raiders",
        "lv" to "las vegas raiders",
        "chargers" to "los angeles chargers",
        "lac" to "los angeles chargers",
        "dolphins" to "miami dolphins",
        "mia" to "miami dolphins",
        "vikings" to "minnesota vikings",
        "min" to "minnesota vikings",
        "saints" to "new orleans saints",
        "no" to "new orleans saints",
        "giants" to "new york giants",
        "nyg" to "new york giants",
        "jets" to "new york jets",
        "nyj" to "new york jets",
        "panthers" to "carolina panthers",
        "car" to "carolina panthers",
        "rams" to "los angeles rams",
        "lar" to "los angeles rams",
        "seahawks" to "seattle seahawks",
        "sea" to "seattle seahawks",
        "buccaneers" to "tampa bay buccaneers",
        "bucs" to "tampa bay buccaneers",
        "tb" to "tampa bay buccaneers",
        "titans" to "tennessee titans",
        "ten" to "tennessee titans",
        "commanders" to "washington commanders",
        "was" to "washington commanders",
        "cardinals" to "arizona cardinals",
        "ari" to "arizona cardinals",
        "bears" to "chicago bears",
        "chi" to "chicago bears",
        "falcons" to "atlanta falcons",
        "atl" to "atlanta falcons",

        // === MLB ===
        "yankees" to "new york yankees",
        "nyy" to "new york yankees",
        "dodgers" to "los angeles dodgers",
        "lad" to "los angeles dodgers",
        "red sox" to "boston red sox",
        "bos" to "boston red sox",
        "astros" to "houston astros",
        "hou" to "houston astros",
        "braves" to "atlanta braves",
        "atl" to "atlanta braves",
        "mets" to "new york mets",
        "nym" to "new york mets",
        "phillies" to "philadelphia phillies",
        "phi" to "philadelphia phillies",
        "padres" to "san diego padres",
        "sd" to "san diego padres",
        "cardinals" to "st. louis cardinals",
        "stl" to "st. louis cardinals",
        "blue jays" to "toronto blue jays",
        "tor" to "toronto blue jays",
        "mariners" to "seattle mariners",
        "sea" to "seattle mariners",
        "rays" to "tampa bay rays",
        "tb" to "tampa bay rays",
        "brewers" to "milwaukee brewers",
        "mil" to "milwaukee brewers",
        "twins" to "minnesota twins",
        "min" to "minnesota twins",
        "cubs" to "chicago cubs",
        "chi" to "chicago cubs",
        "guardians" to "cleveland guardians",
        "cle" to "cleveland guardians",
        "orioles" to "baltimore orioles",
        "bal" to "baltimore orioles",
        "giants" to "san francisco giants",
        "sf" to "san francisco giants",
        "reds" to "cincinnati reds",
        "cin" to "cincinnati reds",
        "rangers" to "texas rangers",
        "tex" to "texas rangers",
        "diamondbacks" to "arizona diamondbacks",
        "dbacks" to "arizona diamondbacks",
        "ari" to "arizona diamondbacks",
        "pirates" to "pittsburgh pirates",
        "pit" to "pittsburgh pirates",
        "angels" to "los angeles angels",
        "laa" to "los angeles angels",
        "royals" to "kansas city royals",
        "kc" to "kansas city royals",
        "tigers" to "detroit tigers",
        "det" to "detroit tigers",
        "marlins" to "miami marlins",
        "mia" to "miami marlins",
        "white sox" to "chicago white sox",
        "cws" to "chicago white sox",
        "athletics" to "oakland athletics",
        "as" to "oakland athletics",
        "rockies" to "colorado rockies",
        "col" to "colorado rockies",
    )

    fun String.trimAndClean(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        return normalized
            .replace("\\p{M}+".toRegex(), "")
            .replace("-", " ")
            .trim().lowercase()
    }

    fun String.normalizeName(): String {
        val n = this.trimAndClean()
            .replace(" vs. ", " vs ").replace(" v ", " vs ")
        val parts = n.split("\\s+".toRegex())
        val mapped = parts.map { translation[it] ?: it }
        return mapped.joinToString(" ")
    }

    fun teamMatches(teamRaw: String, searchRaw: String): Boolean {
        val team = teamRaw.lowercase().trim()
        val search = searchRaw.lowercase().trim()
        if (team == search) return true

        val teamN = team.normalizeName()
        val searchN = search.normalizeName()
        if (teamN == searchN) return true

        val teamWords = teamN.split("\\s+".toRegex()).filter { it.length > 1 }
        val searchWords = searchN.split("\\s+".toRegex()).filter { it.length > 1 }

        for (tw in teamWords) {
            for (sw in searchWords) {
                if (tw == sw) return true
                if (tw.length > 2 && sw.contains(tw)) return true
                if (sw.length > 2 && tw.contains(sw)) return true
            }
        }

        return teamWords.intersect(searchWords.toSet()).isNotEmpty()
    }

    fun searchPosterByTitle(title: String): MatchId {
        val searchTitle = title.replace(" vs. ", " vs ").replace(" v ", " vs ")
        val searchHome = searchTitle.substringBefore(" vs ").trim()
        val searchAway = searchTitle.substringAfterLast(" vs ").trim()

        return this.matches.firstOrNull { match ->
            val apiTitle = match.title.orEmpty().replace(" vs. ", " vs ").replace(" v ", " vs ")
            val home = match.teams?.home?.name ?: apiTitle.substringBefore(" vs ").trim()
            val away = match.teams?.away?.name ?: apiTitle.substringAfterLast(" vs ").trim()

            (teamMatches(home, searchHome) && teamMatches(away, searchAway)) ||
                    (teamMatches(home, searchAway) && teamMatches(away, searchHome))
        }?.let {
            val hourFormat = SimpleDateFormat("HH:mm", Locale.US)
            val hour = it.kickoffMs?.let { ms -> transformHourToLocal(hourFormat.format(Date(ms))) }
            MatchId(it.title.orEmpty(), matchImages[it.imageId()], hour)
        } ?: MatchId(searchHome.lowercase() + " vs " + searchAway.lowercase())
    }
}

val defaultPoster: String =
    "data:image/webp;base64,UklGRqAXAABXRUJQVlA4WAoAAAAQAAAAuwEA+QAAQUxQSHYCAAAFuQpE9D8sviVJsiRJsi2oj+v/ri/rm6p7PuuT4xPXG31DxARMwL/+7S//Nv9h/yCCluUZNi2I8M9k/+xvM8ZcdzDZI8jkmmtEMsacs3Z5ky05I8Jucy5rMT2CibUsZ7dgDGM+L+wRhOVzhOhmmOuOR9lByNEYxprrMvQIhizXFiGyZhfs0rzHdskZaTHMOffp2CPomC7kDDkHc12THU8x03INQsYaTGveZWuCFsFgzjXZ0UPYkWk5g2gwWGsaZA8hg6a1IIjBsubLeYsd95YFsRjWMu1y9gjmc5O1EEuz1po/3iPoi4+ttTSfl8m+eZCZLB9jzcfpaUw+tlyXadCeRoMmy8fFiHmbMWJBQ3Mu73M5Gxqa99oQ5t4eSLsJg27zQrsNOnZDj2M+d/zxHkdfXEdfvdQR+xT2NMI+5Q+HnsbQp5//f/7/+f/n/5//f/7/+f/n/5//f/7/+f/n/5//f/7/+f/n/5//f/7/+f/n/5+3w95G7E/6NPQ2Rp9G7NNjjfnDHse++twXexx9saNjt7zQ3TqG3NcDWTdDWLenurCwLu2BtMvCgkaMHseI0XxssmA9jQXLtFvLx+xpZD6ukY9Npqcxmebz0lrLH/cI9s19rTVLI7Qm64s9gr5YpjWMRtC0fJm3uMt1TYMRpGkt65gewnQsa00zWOQMWibYQwgmazDnIFqQtbzLtQzWmBDk2jI9h8ma62DOkDP37OgR7Mjc5xxGS+TssrzHdZlzzJrIpeXahD2CMO1i7WIsIeSadznXYRhHhI5rQ49gaMd1GLspWjmb1sgeQUZrmnNrbIjDmZZ3uWbOg0lErrlmegSTXcx1jBlWUDggBBUAABBdAJ0BKrwB+gA+KRSIQyGhIRCq1DwYAoS0t34+S+xmYZbS8rafu/Pguj+X+mD3a/6r0Z698wjyH9X/zX91/JH50/5T/D/zP+QfG/8h/7v+y/AB+nn62dcb+l/8f1AfzP+4fsN7pP+d/Yv3Efrn+w3+j+QD+j/5f//+t37C37newJ+33pqftP8HX9X/5f7oe0P/+/YA///A59Oe3f+efjx+3fsP+JfKv0X8bP65/r99e1L/h/1c+rf179qPyF5y/S16gX4n/F/7F+T/93/anjbZpPUI9Qfk3+I/vX9m/439+9Fj+U9HPq7/t/cA/in8m/xX5Tfv/9Af5HxLPG/YA/jf9g/0f+U/cP/M/Sl/Ef67++flL7TfyX+1f8r/B/5P/pf5f7A/45/OP8t/bP8p/5f8v////T9xXr1/d72I/1n/4/5/hNyMVIUKtn1xP+tUe7q9a6kMZH6zdezYo93V611C7HDNLG0fJ8EmNupCklMJ+mTwkrvJy//z9a6j68xfmXd//nvP1rqQpJSAMf8SEpV4tvdgo3UhSSl0pwbqFIdaEHkq20eKVqv6wNe4fQbBBRL4lvPpUqriO+Epfz4mBvW6KMofrNJbQPt6FwpdRUB5hkH3sut3PMsGnY6OEvv8iaSOiIHQqLa11sKV0gYYUUZI4kGcc25G5icuJoHB6NhYzZM6CtknjY/uMWvKlz72ZF2rqHAJbHRLvVVtwQLrJ2U7UoFTAkGIS0+bSAOh+/ycl54oe6XJ2+GnlI/HtlNMk8VXGak1ZBPOs/T5UorYDRd7UW1fAlN87eiTCiWjNFkbOr96+M78TuQdPSpcMYkS7M7+1XdlnNzb8sufALjLE/5VgNUBvmczqVDTpWOsgtxMvP1rqQpJIFkf2JVBI3UhSSl/P/m+Tp4zFFZ0puIUkpfz/59cuua37phXSj3dXrXUhIdnMkCxWeKY26kKSUvy2F0yS9T+Rh1xCklL+f/N3mNl0Cih82dMB50dBM2MFK1FEAD++5SS//mRf9Uv7j83H/6Faq99VX9hikjKF/JpyNveFKeMZWgp2K/hRT6U1Ba3MRGU"

// sportsbite.org GET /api/forestgump/matches -> { days: [ { date, events: [...] } ] }
data class SportsBiteResponse(
    val days: List<SportsBiteDay>? = null
)

data class SportsBiteDay(
    val date: String? = null,
    val events: List<SBMatch>? = null
)

data class SBMatch(
    val title: String? = null,
    val category: String? = null,
    val sport: String? = null,
    val kickoffMs: Long? = null,
    val streamed_event_id: String? = null,
    val event_key: String? = null,
    val teams: Teams? = null,
)

/** Stable id used to key /api/event-images/batch results. */
fun SBMatch.imageId(): String = streamed_event_id ?: event_key ?: title.orEmpty()

data class MatchId(
    val title: String,
    val poster: String? = null,
    val hour: String? = null,
)

data class Teams(
    val home: TeamInfo? = null,
    val away: TeamInfo? = null
)

data class TeamInfo(
    val name: String? = null,
    val badge: String? = null
)

// sportsbite.org POST /api/event-images/batch -> { images: { <id>: { poster, homeLogo, awayLogo, leagueLogo } } }
data class EventImagesRequest(
    val events: List<EventImageRequest>? = null
)

data class EventImageRequest(
    val id: String? = null,
    val title: String? = null,
    val league: String? = null,
    val sport: String? = null,
    val home: String? = null,
    val away: String? = null,
)

data class EventImagesResponse(
    val images: Map<String, EventImage>? = null
)

data class EventImage(
    val poster: String? = null,
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    val leagueLogo: String? = null,
)
