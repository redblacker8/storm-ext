package com.stormunblessed

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class Extractors {

    companion object {

        fun streamClean(
                name: String,
                url: String,
                referer: String,
                quality: String?,
                callback: (ExtractorLink) -> Unit,
                m3u8: Boolean
        ): Boolean {
            callback(
                    ExtractorLink(
                            name,
                            name,
                            url,
                            referer,
                            getQualityFromName(quality),
                            m3u8
                    )
            )
            return true
        }

        fun streamTest(text: String, callback: (ExtractorLink) -> Unit) {
            val testUrl = "https://rt-esp.rttv.com/live/rtesp/playlist.m3u8"
            streamClean(
                    text,
                    testUrl,
                    testUrl,
                    null,
                    callback,
                    testUrl.contains("m3u8")
            )
        }

        suspend fun filemoonsxExtractor(url: String, data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            try {
                val doc = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).document
                doc.select("script").apmap {
                    val script = JsUnpacker(it.html())
                    if (script.detect()) {
                        val regex = """sources:\[\{file:"(.*?)"""".toRegex()
                        val match = regex.find(script.unpack() ?: "")
                        val extractedurl = match?.groupValues?.get(1) ?: ""
                        if (!extractedurl.isNullOrBlank()) {
                            streamClean(
                                    "filemoon.sx $nameExt",
                                    extractedurl,
                                    "https://filemoon.sx/",
                                    null,
                                    callback,
                                    extractedurl.contains("m3u8")
                            )
                            return@apmap
                        }
                    }
                }
            } catch (e: Throwable) {
            }
        }

        suspend fun streamwishExtractor(
                url: String,
                data: String,
                callback: (ExtractorLink) -> Unit,
                nameExt: String = ""
        ) {
            try {
                val doc = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).document
                var script = doc.select("script").find {
                    it.html().contains("jwplayer(\"vplayer\").setup(")
                }
                var scriptContent = script?.html()
                val regex = """sources: \[\{file:"(.*?)"""".toRegex()
                val match = regex.find(scriptContent ?: "")
                val extractedurl = match?.groupValues?.get(1) ?: ""
                if (!extractedurl.isNullOrBlank()) {
                    streamClean(
                            "streamwish.to $nameExt",
                            extractedurl,
                            "https://streamwish.to/",
                            null,
                            callback,
                            extractedurl.contains("m3u8")
                    )
                }
            } catch (e: Throwable) {
            }
        }

        suspend fun doodstreamExtractor(
                url: String,
                data: String,
                callback: (ExtractorLink) -> Unit,
                nameExt: String = ""
        ) {
            try {
                val result = app.get(
                        url.replace("https://dooood.com", "https://d0000d.com").replace("https://doodstream.com", "https://d0000d.com"),
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = true
                )
                val htmlContent = result.document.html()
                val referer = result.url
                val regex = """'(/pass_md5/.*?)'""".toRegex()
                val match = regex.find(htmlContent)
                val endpoint = match?.groupValues?.get(1) ?: ""
                val baseurl = app.get(
                        "https://d0000d.com" + endpoint,
                        headers = mapOf(
                                "Host" to "d0000d.com",
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*",
                                "Accept-Language" to "en-US,en;q=0.5",
                                "Connection" to "keep-alive",
                                "Referer" to referer,
                                "Sec-Fetch-Dest" to "empty",
                                "Sec-Fetch-Mode" to "cors",
                                "Sec-Fetch-Site" to "same-origin",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        )
                ).document.text()

                fun makePlay(): String {
                    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                    val charsLength = chars.length
                    var result = ""
                    for (i in 0..9) {
                        val randomIndex = (Math.random() * charsLength).toInt()
                        result += chars[randomIndex]
                    }
                    val now = System.currentTimeMillis() + 5000
                    return "$result?token=gt7on07cqyy9nnzd6drfdj3z&expiry=$now"
                }

                val extractedUrl = baseurl + makePlay()
                streamClean(
                        "doodstream.com $nameExt",
                        extractedUrl,
                        "https://d0000d.com/",
                        null,
                        callback,
                        false
                )
            } catch (e: Throwable) {
            }
        }

        suspend fun plusvipnetExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            fun getParameterByKey(url: String, key: String): String? {
                val uri = Uri.parse(url)
                val queryParameterNames = uri.getQueryParameterNames()

                for (queryParameterName in queryParameterNames) {
                    if (queryParameterName == key) {
                        return uri.getQueryParameter(queryParameterName)
                    }
                }

                return null
            }

            data class PlusvipNetSources(
                    @JsonProperty("link") var link: String? = null,
            )

            fun decryptBase64AES(input: String, key: String): String {
                val inputBytes = Base64.decode(input, Base64.DEFAULT)
                val secretKey = SecretKeySpec(key.toByteArray(), "AES")
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey)
                val outputBytes = cipher.doFinal(inputBytes)
                val output = Base64.encodeToString(outputBytes, Base64.DEFAULT)
                return output
            }
            try {
                val endpointHash =
                        app.get(url).document.select("script[type=json]").first()?.html()?.replace("\"", "")
                if (endpointHash.isNullOrBlank()) {
                    return
                }
                val endpoint =
                        base64Decode(decryptBase64AES(endpointHash, "d41d8cd98f00b204e9800998ecf8427e"))
                val token = getParameterByKey(url, "data")
                val fetchurl = "https://plusvip.net$endpoint"
                val res = AppUtils.parseJson<PlusvipNetSources>(
                        app.post(
                                fetchurl,
                                headers = mapOf(
                                        "Host" to "plusvip.net",
                                        "Origin" to "https://plusvip.net",
                                        "Referer" to url,
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "*/*",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Connection" to "keep-alive",
                                        "Sec-Fetch-Dest" to "empty",
                                        "Sec-Fetch-Mode" to "cors",
                                        "Sec-Fetch-Site" to "same-origin",
                                ),
                                requestBody = "link=$token".toRequestBody(
                                        contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                                )
                        ).text
                )
                if (!res.link.isNullOrBlank()) {
                    streamClean(
                            "plusvip.net $nameExt",
                            res.link ?: "",
                            "",
                            "",
                            callback,
                            false,
                    )
                }
            } catch (e: Throwable) {
            }
        }

        suspend fun netuCineCalidadExtractor( // not working
                url: String,
                data: String,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit,
                nameExt: String = ""
        ) {
            try {
                app.get(
                        url,
                        headers = mapOf(
                                "Host" to "netu.cinecalidad.com.mx",
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                "Accept-Language" to "en-US,en;q=0.5",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false,
                ).okhttpResponse.headers.values("location").apmap { extractedurl ->
//                if (extractedurl.contains("cinestart")) {
//                    loadExtractor(extractedurl, mainUrl, subtitleCallback, callback)
//                }
                }
            } catch (e: Throwable) {
            }
        }

        suspend fun embedWishExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            try {
                val resText = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).text
                val regex = """sources: \[\{file:"(.*?)"""".toRegex()
                val match = regex.find(resText)
                val extractedurl = match?.groupValues?.get(1) ?: ""
                streamClean(
                        "embedwish.com $nameExt",
                        extractedurl,
                        data,
                        null,
                        callback,
                        extractedurl.contains("m3u8")
                )
            } catch (e: Throwable) {
            }
        }

        suspend fun okruLinkExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            fun getParameterByKey(url: String, key: String): String? {
                val uri = Uri.parse(url)
                val queryParameterNames = uri.getQueryParameterNames()

                for (queryParameterName in queryParameterNames) {
                    if (queryParameterName == key) {
                        return uri.getQueryParameter(queryParameterName)
                    }
                }

                return null
            }

            data class ApizzOkruLinkResponse(
                    @JsonProperty("url") var url: String? = null,
                    @JsonProperty("status") var status: String? = null,
            )
            try {
                val token = getParameterByKey(url, "t")
                val resultJson = AppUtils.parseJson<ApizzOkruLinkResponse>(
                        app.post(
                                "https://apizz.okru.link/decoding",
                                headers = mapOf(
                                        "Host" to "apizz.okru.link",
                                        "Origin" to "https://okru.link",
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "*/*",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Connection" to "keep-alive",
                                        "Sec-Fetch-Dest" to "empty",
                                        "Sec-Fetch-Mode" to "cors",
                                        "Sec-Fetch-Site" to "same-site",
                                ),
                                requestBody = "video=$token".toRequestBody(
                                        contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                                )
                        ).text
                )
                if (!resultJson.url.isNullOrBlank()) {
                    streamClean(
                            "okru.link $nameExt",
                            resultJson.url ?: "",
                            "",
                            "",
                            callback,
                            false,
                    )
                }
            } catch (e: Throwable) {
            }
        }

        suspend fun plusstreamExtractor(
                url: String,
                data: String,
                callback: (ExtractorLink) -> Unit,
                nameExt: String = ""
        ) {
            fun generateKeyAndIV(keyLength: Int, ivLength: Int, iterations: Int, salt: ByteArray, password: ByteArray, md: MessageDigest): Array<ByteArray> {
                val digestLength = md.digestLength
                val requiredLength = (keyLength + ivLength + digestLength - 1) / digestLength * digestLength
                val generatedData = ByteArray(requiredLength)
                var generatedLength = 0

                try {
                    md.reset()

                    while (generatedLength < keyLength + ivLength) {
                        if (generatedLength > 0)
                            md.update(generatedData, generatedLength - digestLength, digestLength)
                        md.update(password)
                        if (salt != null)
                            md.update(salt, 0, 8)
                        md.digest(generatedData, generatedLength, digestLength)

                        for (i in 1 until iterations) {
                            md.update(generatedData, generatedLength, digestLength)
                            md.digest(generatedData, generatedLength, digestLength)
                        }

                        generatedLength += digestLength
                    }

                    val result = Array(2) { ByteArray(0) }
                    result[0] = Arrays.copyOfRange(generatedData, 0, keyLength)
                    if (ivLength > 0)
                        result[1] = Arrays.copyOfRange(generatedData, keyLength, keyLength + ivLength)

                    return result
                } catch (e: Exception) {
                    throw RuntimeException(e)
                } finally {
                    Arrays.fill(generatedData, 0.toByte())
                }
            }

            fun decryptCryptoJsAES(encryptedData: String, key: String): String {
                val cipherData = Base64.decode(encryptedData, Base64.DEFAULT)
                val saltData = Arrays.copyOfRange(cipherData, 8, 16)

                val md5 = MessageDigest.getInstance("MD5")
                val keyAndIV = generateKeyAndIV(32, 16, 1, saltData, key.toByteArray(Charsets.UTF_8), md5)
                val secretKey = SecretKeySpec(keyAndIV[0], "AES")
                val iv = IvParameterSpec(keyAndIV[1])

                val encrypted = Arrays.copyOfRange(cipherData, 16, cipherData.size)
                val aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding")
                aesCBC.init(Cipher.DECRYPT_MODE, secretKey, iv)
                val decryptedData = aesCBC.doFinal(encrypted)
                val decryptedText = String(decryptedData, Charsets.UTF_8)

                return decryptedText
            }
            try {
                val doc = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).document
                var script = doc.select("script").find {
                    it.html().contains("const ")
                }

                var scriptContent = script?.html()
                val regexHash = """'([A-Za-z0-9+/]{400,}={0,2})'""".toRegex()
                val match = regexHash.find(scriptContent ?: "")
                val extractedHash = match?.groupValues?.get(1) ?: ""
                val regexKey = """'([a-fA-F0-9]{32})'""".toRegex()
                val matchKey = regexKey.find(scriptContent ?: "")
                val extractedKey = matchKey?.groupValues?.get(1) ?: ""
                val extractedurl = decryptCryptoJsAES(extractedHash, extractedKey)
                if (!extractedurl.isNullOrBlank()) {
                    streamClean(
                            "plustream.com $nameExt",
                            extractedurl,
                            data,
                            null,
                            callback,
                            extractedurl.contains("m3u8")
                    )
                }
            } catch (e: Throwable) {
            }
        }

        suspend fun wishfasttopExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            try {
                val resText = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).text
                val regex = """sources: \[\{file:"(.*?)"""".toRegex()
                val match = regex.find(resText)
                val extractedurl = match?.groupValues?.get(1) ?: ""
                streamClean(
                        "wishfast.top $nameExt",
                        extractedurl,
                        data,
                        null,
                        callback,
                        extractedurl.contains("m3u8")
                )
            } catch (e: Throwable) {
            }
        }

        suspend fun emturbovidExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            try {
                val doc = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).document
                var script = doc.select("script").find {
                    it.html().contains("var urlPlay = '")
                }
                var scriptContent = script?.html()
                val regex = """var urlPlay = '(.*?)'""".toRegex()
                val match = regex.find(scriptContent ?: "")
                val extractedurl = match?.groupValues?.get(1) ?: ""
                streamClean(
                        "emturbovid.com $nameExt",
                        extractedurl,
                        "https://emturbovid.com",
                        null,
                        callback,
                        extractedurl.contains("m3u8")
                )
            } catch (e: Throwable) {
            }
        }

        suspend fun streamtapeExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            try {
                val doc = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).document
                var scriptContent = doc.select("script").html().lines().find { it.contains("document.getElementById('botlink')") }?.replace("document.getElementById('botlink').innerHTML", "var url")
                var cx = Context.enter()
                cx.optimizationLevel = -1
                var scope = cx.initStandardObjects()
                cx.evaluateString(scope, scriptContent, "url", 1, null)
                var extractedurl = "https:${scope.get("url", scope)}&stream=1"
                if (!extractedurl.isNullOrBlank()) {
                    streamClean(
                            "streamtape.com $nameExt",
                            extractedurl,
                            url,
                            null,
                            callback,
                            extractedurl.contains("m3u8")
                    )
                }
            } catch (e: Throwable) {
            }
        }

        suspend fun
                vudeoExtractor(url: String, data: String, callback: (ExtractorLink) -> Unit, nameExt: String = "") {// TODO: link extracted succesfully but not plays
            try {
                val doc = app.get(
                        url,
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).document
                var script = doc.select("script").find {
                    it.html().contains("var player = new Clappr.Player({")
                }
                var scriptContent = script?.html()
                val regex = """sources: \["(.*?)"""".toRegex()
                val match = regex.find(scriptContent ?: "")
                val extractedurl = match?.groupValues?.get(1) ?: ""
                streamClean(
                        "vudeo.co $nameExt",
                        extractedurl,
                        "https://vudeo.co",
                        null,
                        callback,
                        false,
                )
            } catch (e: Throwable) {
            }
        }

        suspend fun filelionsLoader(
                url: String,
                data: String,
                callback: (ExtractorLink) -> Unit,
                nameExt: String = ""
        ) {
            try {
                val doc = app.get(
                        url.replace("https://filelions.live", "https://filelions.online"),
                        headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                                "Accept-Language" to "en-GB,en;q=0.9,en-US;q=0.8,es-MX;q=0.7,es;q=0.6",
                                "Connection" to "keep-alive",
                                "Referer" to data,
                                "Sec-Fetch-Dest" to "iframe",
                                "Sec-Fetch-Mode" to "navigate",
                                "Sec-Fetch-Site" to "cross-site",
                                "Sec-Fetch-User" to "?1",
                                "Upgrade-Insecure-Requests" to "1",
                        ),
                        allowRedirects = false
                ).document
                var script = doc.select("script").find {
                    it.html().contains("eval(function(p,a,c,k,e,d)")
                }
                var scriptContent = script?.html()
                var cx = Context.enter()
                cx.optimizationLevel = -1
                var scope = cx.initStandardObjects()
                cx.evaluateString(
                        scope, """
                                    var $
                                    $ = {
                                        ajaxSetup: () => {
                                            $ = () => ({on: () => null}) 
                                        }
                                    }
                                    var init = {}
                                    var jwplayer = function(info){
                                        return {
                                            setup: (data) => init = data,
                                            on: (name,callback) => null,
                                            geturl: () => init.sources[0].file,
                                            addButton: () => null,
                                            seek: () => null,
                                            getPosition: () => null,
                                        }
                                    }
                                """.trimIndent(), "script1", 1, null
                )
                cx.evaluateString(scope, scriptContent, "script2", 1, null)
                var result = cx.evaluateString(scope, "init.sources[0].file", "script3", 1, null)
                var finalUrl = result.toString()
                if (!finalUrl.isNullOrBlank()) {
                    streamClean(
                            "filelions.to $nameExt",
                            finalUrl,
                            data,
                            null,
                            callback,
                            finalUrl.contains("m3u8")
                    )
                }
            } catch (e: Throwable) {
            }
        }

        public suspend fun mainExtractor(url: String, data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, nameExt: String = "") {
            loadExtractor(url, data, subtitleCallback, callback)
            if (
                    url.startsWith("https://filelions.to") ||
                    url.startsWith("https://azipcdn.com") ||
                    url.startsWith("https://filelions.live") ||
                    url.startsWith("https://filelions.online")
            ) {
                filelionsLoader(url, data, callback, nameExt)
            } else if (url.startsWith("https://filemoon.sx")) {
                filemoonsxExtractor(url, data, subtitleCallback, callback, nameExt)
            } else if (
                    url.startsWith("https://streamwish.to") ||
                    url.startsWith("https://wishembed.pro") ||
                    url.startsWith("https://cdnwish.com") ||
                    url.startsWith("https://flaswish.com") ||
                    url.startsWith("https://sfastwish.com")
            ) {
                streamwishExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://doodstream.com") || url.startsWith("https://d0000d.com") || url.startsWith("https://dooood.com")) {
                doodstreamExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://plusvip.net")) {
                plusvipnetExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://okru.link")) {
                okruLinkExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://embedwish.com")) {
                embedWishExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://netu.cinecalidad.com.mx")) {// TODO: not working
                netuCineCalidadExtractor(url, data, subtitleCallback, callback, nameExt)
            } else if (url.startsWith("https://wishfast.top")) {
                wishfasttopExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://plustream.com")) {
                plusstreamExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://emturbovid.com")) {
                emturbovidExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://vudeo.co")) {// TODO: Not plays
                vudeoExtractor(url, data, callback, nameExt)
            } else if (url.startsWith("https://streamtape.com")) {
                streamtapeExtractor(url, data, callback, nameExt)
            }
        }
    }
}
