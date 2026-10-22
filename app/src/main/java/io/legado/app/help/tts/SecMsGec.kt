package io.legado.app.help.tts

import java.util.Locale

/**
 * Edge「大声朗读」握手令牌 Sec-MS-GEC 的生成器。
 *
 * 微软在 2026-10 把握手从 v1 升级到 v2：
 * - `Sec-MS-GEC` 由纯 SHA-256 改为 `<16位hex>-<64位hex>` 两段拼接（CS64 + SHA-256）；
 * - 查询参数 `Sec-MS-GEC-Version` 的前缀由 `1-` 改为 `2-`；
 * - 握手请求额外需要 `Cookie: muid=<32位hex>`。
 *
 * 算法移植自 edge-tts(https://github.com/rany2/edge-tts, MIT) 的 drm.py。
 * 其中 CS64 与「改版 SHA-256」都是微软客户端的私有校验，必须逐位复刻，
 * 因此保留了与上游一致的常量表与运算顺序，并用 `SecMsGecTest` 对拍 Python 输出。
 */
internal object SecMsGec {

    /**
     * 1601-01-01 到 1970-01-01 的秒数
     */
    private const val WIN_EPOCH = 11644473600.0

    /**
     * 时间窗口：对齐到 5 分钟，服务端据此校验请求时效
     */
    private const val WINDOW_SECONDS = 300.0

    private val SHA256_INITIAL = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )

    private val SHA256_STATE = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2,
    )

    private val SHA256_SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA,
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060.toInt(), 0x72766564,
    )

    private val SHA256_ROUND_CONSTANTS = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )

    private const val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0

    /**
     * 起始轮为 14 时把第 14 个轮常量替换成调整值
     */
    private val SHA256_BLOCK_CONSTANTS = SHA256_ROUND_CONSTANTS.copyOf().also {
        it[14] = SHA256_ADJUSTMENT
    }

    private val CS64_COEFFICIENTS = arrayOf(
        0xE82E91B1.toInt() to 0xB32700D0.toInt(),
        0x44DE24C6 to 0x88DFF51D.toInt(),
        0x3BEB65BC to 0x29514038,
        0xC89C5933.toInt() to 0xB328CBDF.toInt(),
        0x8D1EC211.toInt() to 0xD405B4D5.toInt(),
        0x314EF5D5 to 0x26E55F3C,
        0xE92491B1.toInt() to 0x607801C6,
        0xF0A5B9D5.toInt() to 0x82B2C1FB.toInt(),
        0x06064CF3 to 0xFA51A3DA.toInt(),
        0xDA79FA60.toInt() to 0x207CC464,
        0xBED20560.toInt() to 0x3C3C74F3,
        0x89F9C9F8.toInt() to 0xD2D14B61.toInt(),
        0x35FA38C6 to 0xBB04A2C1.toInt(),
        0xCB49E3C6.toInt() to 0xC5626167.toInt(),
    )

    private const val CS64_FACTOR = 0x82893933.toInt()
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000

    /**
     * 生成 v2 令牌：`<CS64 16 位>-<SHA-256 64 位>`。
     *
     * [nowMs] 允许调用方传入已按服务端时间校正过的时刻。
     */
    fun v2(nowMs: Long, trustedClientToken: String): String {
        var ticks = nowMs / 1000.0 + WIN_EPOCH
        ticks -= ticks % WINDOW_SECONDS
        ticks *= 1e7
        val raw = (String.format(Locale.US, "%.0f", ticks) + trustedClientToken)
            .toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(raw)
        val cs64 = cs64Digest(raw, digest)
        return toHexLe(cs64) + "-" + toHex(digest)
    }

    /**
     * 生成 32 位 MUID：握手需要 `Cookie: muid=<值>`
     */
    fun generateMuid(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return toHex(bytes)
    }

    // ------------------------------------------------------------------ 基础运算

    private fun rotr(value: Int, bits: Int): Int =
        (value ushr bits) or (value shl (32 - bits))

    /**
     * 旋转 16 位（CS64 专用）
     */
    private fun rol16(value: Int): Int = (value shl 16) or (value ushr 16)

    private fun smallSigma0(v: Int): Int = rotr(v, 7) xor rotr(v, 18) xor (v ushr 3)

    private fun smallSigma1(v: Int): Int = rotr(v, 17) xor rotr(v, 19) xor (v ushr 10)

    private fun bigSigma0(v: Int): Int = rotr(v, 2) xor rotr(v, 13) xor rotr(v, 22)

    private fun bigSigma1(v: Int): Int = rotr(v, 6) xor rotr(v, 11) xor rotr(v, 25)

    private fun ch(e: Int, f: Int, g: Int): Int = g xor (e and (f xor g))

    private fun maj(a: Int, b: Int, c: Int): Int = (a and b) or (c and (a or b))

    // ------------------------------------------------------------------ 改版 SHA-256

    /**
     * 压缩函数。Int 加法天然按 2^32 回绕，等价于上游的 `& 0xFFFFFFFF`。
     */
    private fun rounds(state: IntArray, words: IntArray, firstRound: Int): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants =
            if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        for (index in firstRound until 64) {
            val t1 = h + bigSigma1(e) + ch(e, f, g) + constants[index] + words[index]
            val t2 = bigSigma0(a) + maj(a, b, c)
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: IntArray, firstWord: Int) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            words[index] = smallSigma1(x) + words[index - 7] + smallSigma0(y) + words[index - 16]
        }
    }

    /**
     * 令牌数据的初始状态：前 7 字节参与初始化，剩余部分走分块压缩
     */
    private fun sha256Init(head: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] = ((head[0].toInt() and 0xFF) shl 16) or
            ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
        words[15] = ((head[3].toInt() and 0xFF) shl 24) or
            ((head[4].toInt() and 0xFF) shl 16) or
            ((head[5].toInt() and 0xFF) shl 8) or (head[6].toInt() and 0xFF)
        for (index in 16..29) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    smallSigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    smallSigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    smallSigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    smallSigma0(words[index - 15])
                }
            }
            words[index] = value
        }
        words[30] = smallSigma1(words[28]) + words[23] + smallSigma0(words[15]) +
            words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
        expandSchedule(words, 31)
        val working = rounds(SHA256_STATE, words, 14)
        return IntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "令牌数据至少需要 7 字节" }
        val length = 57 + data.size
        val padCount = ((55 - length) % 64 + 64) % 64
        val padded = ByteArray(data.size - 7 + 1 + padCount + 8)
        System.arraycopy(data, 7, padded, 0, data.size - 7)
        padded[data.size - 7] = 0x80.toByte()
        val bitLength = length.toLong() * 8
        for (index in 0 until 8) {
            padded[padded.size - 1 - index] =
                ((bitLength ushr (8 * index)) and 0xFF).toByte()
        }

        var state = sha256Init(data.copyOfRange(0, 7))
        val words = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                words[index] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            expandSchedule(words, 16)
            val working = rounds(state, words, 0)
            state = IntArray(8) { state[it] + working[it] }
            offset += 64
        }
        return toBytesBe(state)
    }

    // ------------------------------------------------------------------ CS64

    private fun wordsLe4Even(data: ByteArray): IntArray {
        // Python 的 (-n) % 4 取模结果非负，Kotlin 的 % 会保留负号，必须显式归一
        val padTo4 = (4 - data.size % 4) % 4
        val paddedLength = data.size + padTo4
        if (paddedLength == 0) return IntArray(0)
        var count = paddedLength / 4
        if (count % 2 == 1) count += 1
        val words = IntArray(count)
        for (index in 0 until paddedLength / 4) {
            val base = index * 4
            words[index] = byteAt(data, base) or
                (byteAt(data, base + 1) shl 8) or
                (byteAt(data, base + 2) shl 16) or
                (byteAt(data, base + 3) shl 24)
        }
        return words
    }

    /**
     * 末尾不足 4 字节时按 0 补齐，等价于 Python 的 `b"\x00" * ((-len) % 4)`
     */
    private fun byteAt(data: ByteArray, index: Int): Int =
        if (index < data.size) data[index].toInt() and 0xFF else 0

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun cs64Digest(data: ByteArray, digest: ByteArray): IntArray {
        val keyA = leInt(digest, 0) or 1
        val keyB = leInt(digest, 4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)

        val terms = ArrayList<Int>(CS64_COEFFICIENTS.size + 64)
        val offsets = ArrayList<Int>(CS64_COEFFICIENTS.size + 64)
        CS64_COEFFICIENTS.forEachIndexed { index, pair ->
            terms.add(pair.first * factors[index % 2])
            offsets.add(pair.second)
        }

        val words = wordsLe4Even(byteArrayOf(0) + data)
        words.forEachIndexed { index, word ->
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) *
                (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(offset)
        }

        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16(offsets[index] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
            t = rol16(offsets[index + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9.toInt() + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state

            index += 2
        }
        return intArrayOf(swapLast xor state, swapAcc xor acc)
    }

    // ------------------------------------------------------------------ 编码

    private fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { builder.append(String.format(Locale.US, "%02X", it.toInt() and 0xFF)) }
        return builder.toString()
    }

    /**
     * 等价于 Python 的 `struct.pack("<II", a, b).hex()`
     */
    private fun toHexLe(values: IntArray): String {
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            val base = index * 4
            for (byteIndex in 0 until 4) {
                bytes[base + byteIndex] = ((value ushr (8 * byteIndex)) and 0xFF).toByte()
            }
        }
        return toHex(bytes)
    }

    /**
     * 等价于 Python 的 `struct.pack(">8I", ...)`
     */
    private fun toBytesBe(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            val base = index * 4
            for (byteIndex in 0 until 4) {
                bytes[base + byteIndex] = ((value ushr (8 * (3 - byteIndex))) and 0xFF).toByte()
            }
        }
        return bytes
    }
}
