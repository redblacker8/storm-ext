package com.stormunblessed

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Embed69Extractor {
    private const val MAX_ALTCHA_ATTEMPTS = 1024
    private val VOE_REDIRECT = Regex("""window\.location\.href\s*=\s*'([^']+)'""")
    private val ALTCHA_PARAMETERS = Regex(""""parameters"\s*:\s*(\{[^{}]*\})""")
    private val ALTCHA_SIGNATURE = Regex(""""signature"\s*:\s*"([^"]+)"""")
    private val ALTCHA_NONCE = Regex(""""nonce"\s*:\s*"([0-9a-fA-F]+)"""")
    private val ALTCHA_SALT = Regex(""""salt"\s*:\s*"([0-9a-fA-F]+)"""")
    private val ALTCHA_KEY_PREFIX = Regex(""""keyPrefix"\s*:\s*"([0-9a-fA-F]+)"""")
    private val ALTCHA_COST = Regex(""""cost"\s*:\s*(\d+)""")
    private val ALTCHA_KEY_LENGTH = Regex(""""keyLength"\s*:\s*(\d+)""")
    private val VOE_SOURCE = Regex(""""source"\s*:\s*"([^"]+)"""")
    private val VOE_DIRECT = Regex(""""direct_access_url"\s*:\s*"([^"]+)"""")

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var aesKey: ByteArray;
        app.get(url).document.select("script")
            .firstOrNull { it.html().contains("dataLink = [") }?.html()?.let {
                val POW_CHALLENGE = it.substringAfter("const POW_CHALLENGE = '").substringBefore("';")
                val POW_DIFFICULTY = it.substringAfter("const POW_DIFFICULTY = ").substringBefore(";").toInt()
                val POW_SALT = it.substringAfter("const POW_SALT = '").substringBefore("';")
                aesKey = deriveAesKey(POW_CHALLENGE, POW_DIFFICULTY, POW_SALT)
                it
            }?.substringAfter("dataLink = ")
            ?.substringBefore(";")?.let {
                AppUtils.tryParseJson<List<ServersByLang>>(it)?.amap { lang ->
                    val links = lang.sortedEmbeds.amap { decryptAES(it.link!!, aesKey ) }
                    if (links.isNotEmpty()) {
                        links.filterNotNull().amap {
                            loadServer(
                                lang.videoLanguage,
                                it,
                                referer,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
    }

    private suspend fun loadServer(
        lang: String?,
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.startsWith("https://voe.sx/") && loadVoe(lang, url, callback)) return
        loadSourceNameExtractor(lang, url, referer, subtitleCallback, callback)
    }

    private suspend fun loadVoe(
        lang: String?,
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var target = url
            var response = app.get(url)
            val redirect = VOE_REDIRECT.find(response.text)?.groupValues?.get(1)
            if (redirect != null) {
                target = redirect
                response = app.get(target)
            } else {
                target = response.url
            }
            var cookies = response.cookies

            if (response.text.contains("altcha-widget")) {
                val token = response.document.selectFirst("input[name=_token]")?.attr("value")
                val challengeUrl = response.document.selectFirst("altcha-widget")?.attr("challenge")
                if (token.isNullOrBlank() || challengeUrl.isNullOrBlank()) return false

                val challengeResponse = app.get(challengeUrl, referer = target, cookies = cookies)
                cookies = cookies + challengeResponse.cookies
                val challengeText = challengeResponse.text
                val solution = solveAltcha(challengeText) ?: return false
                val parameters = ALTCHA_PARAMETERS.find(challengeText)?.groupValues?.get(1) ?: return false
                val signature = ALTCHA_SIGNATURE.find(challengeText)?.groupValues?.get(1) ?: return false

                val payload = "{\"challenge\":{\"parameters\":$parameters,\"signature\":\"$signature\"}," +
                        "\"solution\":{\"counter\":${solution.first},\"derivedKey\":\"${solution.second}\",\"time\":1}}"
                val encodedPayload = URLEncoder.encode(
                    Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                    "UTF-8"
                )
                val origin = runCatching {
                    URI(target).let { "${it.scheme}://${it.host}" }
                }.getOrNull()

                response = app.post(
                    target,
                    headers = if (origin != null) mapOf("Origin" to origin) else emptyMap(),
                    referer = target,
                    cookies = cookies,
                    data = mapOf("_token" to token, "access" to "0", "altcha" to encodedPayload)
                )
            }

            var configText: String? = null
            for (element in response.document.select("script[type=application/json]")) {
                val first = AppUtils.tryParseJson<List<String>>(element.html())?.firstOrNull()
                if (first != null) {
                    configText = first
                    break
                }
            }
            val config = configText?.let { decodeVoeConfig(it) } ?: return false
            val hls = VOE_SOURCE.find(config)?.groupValues?.get(1)?.replace("\\/", "/")
            val direct = VOE_DIRECT.find(config)?.groupValues?.get(1)?.replace("\\/", "/")
            val stream = hls ?: direct ?: return false
            val name = standardSourceName(lang, "Voe")
            callback(
                newExtractorLink("Voe", name, stream) {
                    this.type = if (hls != null) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.referer = target
                }
            )
            return true
        } catch (e: Exception) {
            Log.d("Embed69Extractor", "voe extraction failed: ${e.message}")
            return false
        }
    }

    private fun solveAltcha(challengeText: String): Pair<Long, String>? {
        return try {
            val nonceHex = ALTCHA_NONCE.find(challengeText)?.groupValues?.get(1) ?: return null
            val saltHex = ALTCHA_SALT.find(challengeText)?.groupValues?.get(1) ?: return null
            val prefix = ALTCHA_KEY_PREFIX.find(challengeText)?.groupValues?.get(1)?.lowercase()
                ?: return null
            val cost = ALTCHA_COST.find(challengeText)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val keyLength = ALTCHA_KEY_LENGTH.find(challengeText)?.groupValues?.get(1)?.toIntOrNull() ?: 32
            if (prefix.isEmpty() || cost <= 0) return null
            val nonce = hexToBytes(nonceHex) ?: return null
            val salt = hexToBytes(saltHex) ?: return null

            val password = ByteArray(nonce.size + 4)
            System.arraycopy(nonce, 0, password, 0, nonce.size)
            var counter = 0
            while (counter < MAX_ALTCHA_ATTEMPTS) {
                password[nonce.size] = (counter ushr 24).toByte()
                password[nonce.size + 1] = (counter ushr 16).toByte()
                password[nonce.size + 2] = (counter ushr 8).toByte()
                password[nonce.size + 3] = counter.toByte()
                val derived = pbkdf2Sha256(password, salt, cost, keyLength)
                val derivedHex = derived.joinToString("") { "%02x".format(it) }
                if (derivedHex.startsWith(prefix)) return counter.toLong() to derivedHex
                counter++
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLength = 32
        val output = ByteArray(keyLength)
        val mixed = ByteArray(hashLength)
        val blockIndex = ByteArray(4)
        var current = ByteArray(hashLength)
        var next = ByteArray(hashLength)
        var offset = 0
        var block = 1
        while (offset < keyLength) {
            blockIndex[0] = (block ushr 24).toByte()
            blockIndex[1] = (block ushr 16).toByte()
            blockIndex[2] = (block ushr 8).toByte()
            blockIndex[3] = block.toByte()
            mac.reset()
            mac.update(salt)
            mac.update(blockIndex)
            mac.doFinal(current, 0)
            System.arraycopy(current, 0, mixed, 0, hashLength)
            for (i in 1 until iterations) {
                mac.update(current)
                mac.doFinal(next, 0)
                for (j in 0 until hashLength) mixed[j] = (mixed[j].toInt() xor next[j].toInt()).toByte()
                val swap = current
                current = next
                next = swap
            }
            val length = minOf(hashLength, keyLength - offset)
            System.arraycopy(mixed, 0, output, offset, length)
            offset += length
            block++
        }
        return output
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBase64(value: String): ByteArray? {
        return try {
            val cleaned = value.filter { !it.isWhitespace() }
            val padded = when (cleaned.length % 4) {
                2 -> cleaned + "=="
                3 -> cleaned + "="
                else -> cleaned
            }
            Base64.decode(padded, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    private fun rot13(value: String): String {
        val builder = StringBuilder(value.length)
        for (char in value) {
            builder.append(
                when (char) {
                    in 'A'..'Z' -> ('A'.code + (char.code - 'A'.code + 13) % 26).toChar()
                    in 'a'..'z' -> ('a'.code + (char.code - 'a'.code + 13) % 26).toChar()
                    else -> char
                }
            )
        }
        return builder.toString()
    }

    fun decodeVoeConfig(encoded: String): String? {
        return try {
            var value = rot13(encoded)
            for (delimiter in VOE_DELIMITERS) value = value.replace(delimiter, "_")
            value = value.replace("_", "")
            val shifted = decodeBase64(value) ?: return null
            val builder = StringBuilder(shifted.size)
            for (byte in shifted) {
                builder.append(((byte.toInt() and 0xFF) - 3 and 0xFFFF).toChar())
            }
            val reversed = builder.reverse().toString()
            val config = decodeBase64(reversed) ?: return null
            String(config, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private val VOE_DELIMITERS = listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")

    fun deriveAesKey(challenge: String, difficulty: Int, salt: String): ByteArray {
        val prefix = "0".repeat(difficulty)
        var nonce: Long = 0
        val batchSize = 5000

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        fun sha256Bytes(input: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray(Charsets.UTF_8))
        }

        while (true) {
            for (i in 0 until batchSize) {
                val hashHex = sha256Hex(challenge + nonce)
                if (hashHex.startsWith(prefix)) {
                    return sha256Bytes(challenge + nonce + salt)
                }
                nonce++
            }
        }
    }
    fun decryptAES(encryptedBase64: String, aesKey: ByteArray): String? {
        return try {
            val raw = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)
            val keyBytes = aesKey.copyOfRange(0, 32)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

data class Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class ServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<Server> = emptyList<Server>(),
    @JsonProperty("downloadEmbeds") val downloadEmbeds: List<Server> = emptyList<Server>(),
)

data class LinksRequest(
    val links: List<String>,
)

data class Loadlinks(
    val success: Boolean,
    val links: List<Link>,
)

data class Link(
    val index: Long,
    val link: String,
)

/**
 * Extension name this module's link labels must end with (AGENTS.md "Source-name standard").
 * Keep in sync with the provider's `override var name`.
 */
const val EXTENSION_NAME = "SoloLatino"

/**
 * Standard visible label for a stream: "<Idioma> - <Servidor> - <Extensión>",
 * e.g. "Latino - Voe - PelisplusHD".
 */
fun standardSourceName(lang: String?, server: String?): String =
    listOf(normalizeSourceLang(lang), normalizeSourceServer(server), EXTENSION_NAME)
        .joinToString(" - ")

/** Canonical language labels, most common first: Latino, Español, English. Blank/null -> provider default (Latino). */
fun normalizeSourceLang(lang: String?): String {
    val raw = lang?.trim().orEmpty()
    if (raw.isEmpty()) return "Latino"
    val flat = raw.lowercase()
    return when {
        flat == "mx" || flat.endsWith("-mx") || flat.contains("lat") || flat.contains("419") -> "Latino"
        flat == "en" || flat.startsWith("en-") || flat.contains("eng") || flat.contains("ingl") -> "English"
        flat == "es" || flat.startsWith("es-") || flat.contains("esp") ||
                flat.contains("spanish") || flat.contains("castellano") -> "Español"

        flat.contains("sub") -> "Subtitulado"
        else -> raw.replaceFirstChar { it.uppercaseChar() }
    }
}

/** Stream source/host display name: drops emoji/decorations, capitalizes all-lowercase ids, falls back to "Directo". */
fun normalizeSourceServer(server: String?): String {
    val cleaned = server?.replace(Regex("[^\\p{L}\\p{N} .&+_-]"), "")?.trim().orEmpty()
    if (cleaned.isEmpty()) return "Directo"
    val needsCapital = cleaned.all { it.isLowerCase() || !it.isLetter() }
    return if (needsCapital) cleaned.replaceFirstChar { it.uppercaseChar() } else cleaned
}

/**
 * Forwards an embedded url to the matching extractor and relabels the result with the
 * standard "<Idioma> - <Servidor> - <Extensión>" name while keeping `link.source` untouched.
 */
suspend fun loadSourceNameExtractor(
    lang: String?,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(fixHostsLinks(url), referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            val name = standardSourceName(lang, link.source)
            callback.invoke(
                newExtractorLink(
                    link.source,
                    name,
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://morencius.com", "https://vidhidepro.com")
        .replaceFirst("https://hglink.to", "https://hgplaycdn.com")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}
