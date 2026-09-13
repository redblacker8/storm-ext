package com.stormunblessed

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Calendar
import java.util.Random
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Port of the izzi go (Mediaroom IRIS) `IRIS-REQUEST-ID` proof-of-work token.
 * The login endpoint rejects requests whose IRIS-REQUEST-ID is not a valid hash.
 */
object IzziGoPoW {

    private const val M = "00000000"
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val HEXD = arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F")
    private val BIND = arrayOf(
        "0000", "0001", "0010", "0011", "0100", "0101", "0110", "0111",
        "1000", "1001", "1010", "1011", "1100", "1101", "1110", "1111"
    )

    fun generate(
        uuid: String,
        bits: Int,
        pass: String,
        ver: String,
        offset: Long = 0L,
        now: Long = System.currentTimeMillis(),
        random: Random = Random(),
    ): String {
        val p = md5Last8(uuid)
        val rnd = random

        fun y(e: Int) = Integer.toBinaryString(e)

        fun A(e: Int): String {
            val t = if (e >= 0) "00000000" + Integer.toBinaryString(e)
            else "00000000" + Integer.toBinaryString(e.inv())
            return t.substring(t.length - 8)
        }

        fun b() = if (rnd.nextDouble() > 0.5) "0" else "1"

        fun w(e: Array<String?>, t: String?, n: Int): Array<String?> {
            val r = arrayOfNulls<String>(e.size + 1)
            var i = 0
            for (o in r.indices) {
                if (o == n) i = 1
                r[o] = e[o - i]
            }
            r[n] = t
            return r
        }

        fun append(e: Array<String?>): Array<String?> {
            val r = e.copyOf(e.size + 1)
            r[e.size] = null
            return r
        }

        fun sliceLast(s: String, n: Int) = if (s.length > n) s.substring(s.length - n) else s

        fun split8(s: String): Array<String?> {
            val out = ArrayList<String?>()
            var i = 0
            while (i < s.length) {
                out.add(s.substring(i, minOf(i + 8, s.length)))
                i += 8
            }
            return out.toTypedArray()
        }

        fun bytesToBits(o: ByteArray): String {
            val sb = StringBuilder()
            for (value in o) sb.append(A(value.toInt() and 0xFF))
            return sb.toString()
        }

        fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) out[i] = hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
            return out
        }

        fun invert(s: String) = buildString { for (c in s) append(if (c == '0') '1' else '0') }

        fun hmac(key: String, data: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return b64Encode(mac.doFinal(data))
        }

        fun T(e0: String, t: Int): String {
            var e = e0
            for (n in 0 until t) {
                if (e.length >= 64) e = e.substring(1)
                e += "0"
            }
            return e
        }

        fun x(e0: String, t: Int, n: Boolean): String {
            var e = e0
            for (r in 0 until t) e = e.substring(0, e.length - 1)
            if (n && e.length < 8) repeat(8 - e.length) { e = "0" + e }
            return e
        }

        fun S(e0: String, t0: String): String {
            var e = e0
            var t = t0
            while (t.length < e.length) t = "0" + t
            while (e.length < t.length) e = "0" + e
            val n = StringBuilder()
            for (r in e.indices) n.append(if (e[r] == '1' || t[r] == '1') '1' else '0')
            return n.toString()
        }

        fun K(e0: String, t0: String): String {
            var e = e0
            var t = t0
            while (t.length < e.length) t = "0" + t
            while (e.length < t.length) e = "0" + e
            val n = StringBuilder()
            for (r in e.indices) n.append(if (e[r] == '1' && t[r] == '1') '1' else '0')
            return n.toString()
        }

        fun C(e: String): String {
            if (e.isEmpty()) return ""
            var r = e.split(".")[0]
            val t = StringBuilder()
            while (r.length % 4 != 0) r = "0" + r
            while (r.isNotEmpty()) {
                val s = r.substring(0, 4)
                r = r.substring(4)
                for (l in 0 until 16) if (s == BIND[l]) t.append(HEXD[l])
            }
            return t.toString()
        }

        fun dayOfYear(c: Calendar): Int {
            val t = c.get(Calendar.MONTH)
            val n = c.get(Calendar.DAY_OF_MONTH)
            val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
            var r = cum[t] + n
            val year = c.get(Calendar.YEAR)
            val leap = (year % 4 == 0) && (year % 100 != 0 || year % 400 == 0)
            if (t > 1 && leap) r++
            return r
        }

        fun makeTimeString(nonce: Int, off: Long): String {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.timeInMillis = now + off
            val i = c.get(Calendar.HOUR_OF_DAY)
            val o = c.get(Calendar.MINUTE)
            val a = dayOfYear(c)
            val s = ArrayList<String>()
            s.add(sliceLast(M + y(a), 6))
            s.add(b())
            s.add(sliceLast(M + y(p[2]), 8))
            s.add(b())
            s.add(b())
            s.add(sliceLast(M + y(p[6]), 8))
            s.add(b())
            s.add(b())
            s.add(b())
            s.add(sliceLast(M + y(nonce), 5))
            s.add(sliceLast(M + y(i), 5))
            s.add(b())
            s.add(sliceLast(M + y(p[7]), 8))
            s.add(b())
            s.add(b())
            s.add(sliceLast(M + y(o), 6))
            s.add(b())
            s.add(sliceLast(M + y(p[4]), 8))
            return s.joinToString("")
        }

        fun check(e: String, t: String, n: Int, r: Int): Boolean {
            val i = r + n - 1
            val o = r / 8
            val a = r % 8
            val s = i / 8
            val l = i % 8
            var u = A(255)
            u = x(u, a, true)
            val ech = split8(e)
            val tch = split8(t)
            var c = K(ech[o]!!, u)
            var d = K(tch[o]!!, u)
            if (s == o) {
                u = A(255)
                u = T(u, 8 - l - 1)
                c = K(c, u)
                d = K(d, u)
            }
            if (c != d) return false
            for (h in o + 1 until s) if (ech[h] != tch[h]) return false
            if (s != o && l > 0) {
                u = T("11111111", 8 - l - 1)
                c = K(ech[s]!!, u)
                d = K(tch[s]!!, u)
            }
            return c == d
        }

        fun rotate(e: Array<String?>, t: Int): Array<String?> {
            val i = arrayOfNulls<String>(e.size)
            val o = t / 8
            val a = t % 8
            for (s in e.indices) {
                val n = (s - o + e.size) % e.size
                i[s] = e[n]
            }
            val last = i[i.size - 1]
            for (u in i.size - 1 downTo 0) {
                var r = A(255)
                i[u] = x(i[u]!!, a, false)
                r = x(r, a, false)
                i[u] = K(i[u]!!, r)
                r = T(A(255), 8 - a)
                val c = if (u > 0) i[u - 1] else last
                i[u] = S(i[u]!!, K(T(c!!, 8 - a), r))
            }
            return i
        }

        fun v(e: IntArray, t: Int, n: Int): String {
            val a = StringBuilder()
            var s = t
            while (s < n) {
                val r = (e[s] shl 16 and 16711680) + (e[s + 1] shl 8 and 65280) + (255 and e[s + 2])
                a.append(B64[r shr 18 and 63]).append(B64[r shr 12 and 63])
                    .append(B64[r shr 6 and 63]).append(B64[63 and r])
                s += 3
            }
            return a.toString()
        }

        fun base64Encode(e: IntArray): String {
            val n = e.size
            val r = n % 3
            val o = StringBuilder()
            val a = 16383
            val l = n - r
            var s = 0
            while (s < l) {
                o.append(v(e, s, minOf(s + a, l)))
                s += a
            }
            if (r == 1) {
                val t = e[n - 1]
                o.append(B64[t shr 2]).append(B64[t shl 4 and 63]).append("==")
            } else if (r == 2) {
                val t = (e[n - 2] shl 8) + e[n - 1]
                o.append(B64[t shr 10]).append(B64[t shr 4 and 63])
                    .append(B64[t shl 2 and 63]).append("=")
            }
            return o.toString()
        }

        val t = (31 * rnd.nextDouble()).toInt()
        val n = makeTimeString(t, offset)
        var o = split8(n)
        val a = o.size
        var s = 0
        while (s < (o.size + 32 + 1) % 3) {
            o = w(o, "00000000", o.size)
            s++
        }
        val hexN = C(n)
        var pv = hmac(pass, hexToBytes(hexN))
        var bigO = bytesToBits(b64Decode(pv))
        while (true) {
            val mi = invert(bigO)
            if (check(mi, n, bits, t)) break
            var nIdx = o.size - 1
            while (nIdx >= a) {
                if (o[nIdx] != "11111111") {
                    val r = o[nIdx]!!.toInt(2) + 1
                    o[nIdx] = A(r)
                    for (l in nIdx + 1 until o.size) o[l] = "00000000"
                    break
                }
                nIdx--
            }
            if (nIdx == a - 1) {
                repeat(3) { o = append(o) }
                for (u in o.size - 1 downTo a) o[u] = "00000000"
            }
            val bHex = C(o.joinToString("") { it ?: "" })
            pv = hmac(pass, hexToBytes(bHex))
            bigO = bytesToBits(b64Decode(pv))
        }
        val o2 = arrayOfNulls<String>(o.size - 1)
        var r = 0
        for (i2 in 0 until o.size - 1) {
            if (i2 == 7) r = 1
            o2[i2] = o[i2 + r]
        }
        o = o2
        val h = (255 * rnd.nextDouble()).toInt()
        o = rotate(o, h % (8 * o.size))
        o = w(o, A(h), o.size)
        val verArr = o.copyOf(o.size + 1)
        verArr[o.size] = ver
        val oChunks = split8(bigO)
        val q = arrayOfNulls<String>(verArr.size + oChunks.size)
        System.arraycopy(verArr, 0, q, 0, verArr.size)
        System.arraycopy(oChunks, 0, q, verArr.size, oChunks.size)
        val bytes = IntArray(q.size)
        for (i3 in q.indices) bytes[i3] = q[i3]!!.toInt(2)
        return base64Encode(bytes)
    }

    private fun b64Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            sb.append(B64[b0 shr 2])
            if (b1 >= 0) {
                sb.append(B64[(b0 shl 4 and 0x30) or (b1 shr 4)])
                if (b2 >= 0) {
                    sb.append(B64[(b1 shl 2 and 0x3C) or (b2 shr 6)])
                    sb.append(B64[b2 and 0x3F])
                } else {
                    sb.append(B64[b1 shl 2 and 0x3C])
                    sb.append('=')
                }
            } else {
                sb.append(B64[b0 shl 4 and 0x30])
                sb.append("==")
            }
            i += 3
        }
        return sb.toString()
    }

    private fun b64Decode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = B64.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun md5Last8(uuid: String): IntArray {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(uuid.toByteArray(Charsets.UTF_8))
        val hex = buildString { for (b in digest) append("%02x".format(b)) }
        val all = IntArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16) }
        return all.copyOfRange(all.size - 8, all.size)
    }
}
