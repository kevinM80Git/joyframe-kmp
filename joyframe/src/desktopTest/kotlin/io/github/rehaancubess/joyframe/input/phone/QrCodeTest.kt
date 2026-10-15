// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrCodeTest {
    /** ISO/IEC 18004 Annex I: "01234567" as 1-M; its 16 data codewords and their 10 error correction codewords. */
    @Test fun reedSolomonMatchesTheStandardsExample() {
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        val ec = QrCode.rsRemainder(data, QrCode.rsDivisor(10))
        assertContentEquals(intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ec)
    }

    @Test fun versionGrowsWithTheText_andFindersAreInTheCorners() {
        val url = QrCode.encode("http://192.168.0.120:47800/")
        assertEquals(3, url.version, "27 bytes: version 3-M")
        assertEquals(29, url.size)
        for ((cx, cy) in listOf(3 to 3, url.size - 4 to 3, 3 to url.size - 4)) {
            assertTrue(url[cx, cy], "finder centre dark")
            assertTrue(!url[cx + 2, cy], "finder ring light")
            assertTrue(url[cx + 3, cy], "finder edge dark")
        }
        assertTrue(url[8, url.size - 8], "the dark module")
        assertEquals(10, QrCode.encode("x".repeat(200)).version)
        assertFailsWith<IllegalArgumentException> { QrCode.encode("x".repeat(300)) }
    }

    /** The format information around the top-left finder decodes to level M and the chosen mask. */
    @Test fun formatBitsReadBack() {
        val qr = QrCode.encode("http://192.168.0.120:47800/")
        var bits = 0
        val coords = listOf(8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8, 7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8)
        for ((i, c) in coords.withIndex()) if (qr[c.first, c.second]) bits = bits or (1 shl i)
        val data = (bits xor 0x5412) ushr 10
        assertEquals(0, data ushr 3, "error correction level M")
        assertEquals(qr.mask, data and 7)
    }
}
