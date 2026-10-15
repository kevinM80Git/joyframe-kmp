// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import kotlin.math.abs

/**
 * A QR code (ISO/IEC 18004) for a short text such as a URL: byte mode, error correction level M,
 * versions 1–10 (up to 213 bytes of UTF-8). Enough to show a "scan to join" link on a TV; draw
 * [size] × [size] modules with [get] (true is dark) inside a light quiet zone of 4 modules.
 *
 * ```
 * val qr = QrCode.encode("http://192.168.0.20:47800/")
 * Canvas(Modifier.size(240.dp)) { for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x, y]) drawRect(...) }
 * ```
 */
class QrCode private constructor(val size: Int, private val modules: BooleanArray, val version: Int, val mask: Int) {
    /** True when the module at column [x], row [y] is dark. */
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        /** Encodes [text] (UTF-8). Throws [IllegalArgumentException] when it does not fit version 10-M. */
        fun encode(text: String): QrCode = encodeBytes(text.encodeToByteArray())

        fun encodeBytes(data: ByteArray): QrCode {
            val version = (1..MAX_VERSION).firstOrNull { v -> 4 + (if (v < 10) 8 else 16) + data.size * 8 <= dataCodewords(v) * 8 }
                ?: throw IllegalArgumentException("${data.size} bytes do not fit a version $MAX_VERSION-M QR code")
            val codewords = addErrorCorrection(version, dataCodewordsFor(version, data))
            return Builder(version).build(codewords)
        }

        internal const val MAX_VERSION = 10
        // Level M, per version (index 1..10): total codewords, error correction codewords per block, blocks.
        private val TOTAL = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
        private val EC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private val ALIGN = arrayOf(
            intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )
        /** Format bits of level M. */
        private const val LEVEL_M = 0

        internal fun dataCodewords(version: Int) = TOTAL[version] - EC_PER_BLOCK[version] * BLOCKS[version]

        /** Mode, count, data, terminator, padding: exactly [dataCodewords] bytes. */
        private fun dataCodewordsFor(version: Int, data: ByteArray): IntArray {
            val capacity = dataCodewords(version) * 8
            val bits = ArrayList<Boolean>(capacity)
            fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value ushr i) and 1 != 0 }
            put(0b0100, 4)
            put(data.size, if (version < 10) 8 else 16)
            for (b in data) put(b.toInt() and 0xFF, 8)
            put(0, minOf(4, capacity - bits.size))
            while (bits.size % 8 != 0) bits += false
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) } }
        }

        /** Splits into blocks, adds Reed–Solomon codewords to each, interleaves. */
        internal fun addErrorCorrection(version: Int, data: IntArray): IntArray {
            val blocks = BLOCKS[version]; val ec = EC_PER_BLOCK[version]; val raw = TOTAL[version]
            val shortBlocks = blocks - raw % blocks
            val shortLength = raw / blocks
            val divisor = rsDivisor(ec)
            val dataBlocks = ArrayList<IntArray>(); val ecBlocks = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until blocks) {
                val length = shortLength - ec + (if (i < shortBlocks) 0 else 1)
                val block = data.copyOfRange(k, k + length)
                k += length
                dataBlocks += block
                ecBlocks += rsRemainder(block, divisor)
            }
            val out = IntArray(raw)
            var n = 0
            for (i in 0 until dataBlocks.maxOf { it.size }) for (b in dataBlocks) if (i < b.size) out[n++] = b[i]
            for (i in 0 until ec) for (b in ecBlocks) out[n++] = b[i]
            return out
        }

        /** The generator polynomial of [degree] (leading 1 omitted), highest power first. */
        internal fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        internal fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
            return result
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private class Builder(val version: Int) {
            val size = version * 4 + 17
            val modules = BooleanArray(size * size)
            val function = BooleanArray(size * size)

            fun set(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark; function[y * size + x] = true }

            fun build(codewords: IntArray): QrCode {
                functionPatterns()
                placeCodewords(codewords)
                var bestMask = 0; var bestPenalty = Int.MAX_VALUE
                for (mask in 0 until 8) {
                    applyMask(mask); formatBits(mask)
                    val p = penalty()
                    if (p < bestPenalty) { bestPenalty = p; bestMask = mask }
                    applyMask(mask) // undo (XOR)
                }
                applyMask(bestMask); formatBits(bestMask)
                return QrCode(size, modules, version, bestMask)
            }

            private fun functionPatterns() {
                for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
                finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
                val align = ALIGN[version]
                val last = align.size - 1
                for (i in align.indices) for (j in align.indices) {
                    if (i == 0 && j == 0 || i == 0 && j == last || i == last && j == 0) continue
                    alignment(align[i], align[j])
                }
                formatBits(0) // reserves the area; rewritten with the chosen mask
                versionBits()
            }

            private fun finder(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx; val y = cy + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val d = maxOf(abs(dx), abs(dy))
                    set(x, y, d != 2 && d != 4)
                }
            }

            private fun alignment(cx: Int, cy: Int) {
                for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(abs(dx), abs(dy)) != 1)
            }

            private fun formatBits(mask: Int) {
                val data = LEVEL_M shl 3 or mask
                var rem = data
                repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
                val bits = (data shl 10 or rem) xor 0x5412
                fun bit(i: Int) = (bits ushr i) and 1 != 0
                for (i in 0..5) set(8, i, bit(i))
                set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
                for (i in 9 until 15) set(14 - i, 8, bit(i))
                for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
                for (i in 8 until 15) set(8, size - 15 + i, bit(i))
                set(8, size - 8, true) // the dark module
            }

            private fun versionBits() {
                if (version < 7) return
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = version shl 12 or rem
                for (i in 0 until 18) {
                    val dark = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3; val b = i / 3
                    set(a, b, dark); set(b, a, dark)
                }
            }

            /** The zigzag: two-module columns from the right, up and down, skipping the timing column. */
            private fun placeCodewords(data: IntArray) {
                var i = 0
                var right = size - 1
                while (right >= 1) {
                    if (right == 6) right = 5
                    for (vert in 0 until size) for (j in 0..1) {
                        val x = right - j
                        val upward = (right + 1) and 2 == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y * size + x] && i < data.size * 8) {
                            modules[y * size + x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                            i++
                        }
                    }
                    right -= 2
                }
            }

            private fun applyMask(mask: Int) {
                for (y in 0 until size) for (x in 0 until size) {
                    if (function[y * size + x]) continue
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert) modules[y * size + x] = !modules[y * size + x]
                }
            }

            private fun at(x: Int, y: Int) = modules[y * size + x]

            /** The four penalty rules: runs, 2×2 blocks, finder-like patterns, dark balance. */
            private fun penalty(): Int {
                var p = 0
                for (horizontal in listOf(true, false)) for (a in 0 until size) {
                    var run = 1
                    for (b in 1 until size) {
                        val same = if (horizontal) at(b, a) == at(b - 1, a) else at(a, b) == at(a, b - 1)
                        if (same) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
                    }
                    for (b in 0..size - 11) {
                        fun m(k: Int) = if (horizontal) at(b + k, a) else at(a, b + k)
                        val core = m(0) && !m(1) && m(2) && m(3) && m(4) && !m(5) && m(6)
                        val pattern1 = core && !m(7) && !m(8) && !m(9) && !m(10)
                        val pattern2 = !m(0) && !m(1) && !m(2) && !m(3) && m(4) && !m(5) && m(6) && m(7) && m(8) && !m(9) && m(10)
                        if (pattern1) p += 40
                        if (pattern2) p += 40
                    }
                }
                for (y in 0 until size - 1) for (x in 0 until size - 1) {
                    val c = at(x, y)
                    if (c == at(x + 1, y) && c == at(x, y + 1) && c == at(x + 1, y + 1)) p += 3
                }
                val dark = modules.count { it }
                p += abs(dark * 100 / modules.size - 50) / 5 * 10
                return p
            }
        }
    }
}
