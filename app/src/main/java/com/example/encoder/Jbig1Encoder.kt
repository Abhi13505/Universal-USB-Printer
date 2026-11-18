package com.example.encoder

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin ITU-T T.82 (JBIG1) QM-arithmetic stripe encoder.
 * Produces a 20-byte BIH (Bitmap Image Header) + QM-coded sequential stripe + 0xFF 0x02 (SDNORM).
 */
object Jbig1Encoder {

    // ITU-T T.82 Table 23 QM-coder probability estimation state table (113 states)
    private val LSZ = intArrayOf(
        0x5A1D, 0x2586, 0x1114, 0x080B, 0x03D8, 0x01DA, 0x00E5, 0x006F,
        0x0036, 0x001A, 0x000D, 0x0006, 0x0003, 0x0001, 0x5A7F, 0x3F25,
        0x2CF2, 0x207C, 0x17B9, 0x1182, 0x0CEF, 0x09A1, 0x072F, 0x055C,
        0x0406, 0x0303, 0x0240, 0x01B1, 0x0144, 0x00F5, 0x00B7, 0x008A,
        0x0068, 0x004E, 0x003B, 0x002C, 0x5AE1, 0x484C, 0x3A0D, 0x2EF1,
        0x261F, 0x1F33, 0x19A8, 0x1518, 0x1177, 0x0E74, 0x0BFB, 0x09F8,
        0x0861, 0x0706, 0x05CD, 0x04DE, 0x040F, 0x0363, 0x02D4, 0x025C,
        0x01F8, 0x01A4, 0x0160, 0x0125, 0x00F6, 0x00CB, 0x00AB, 0x008F,
        0x5B12, 0x4D04, 0x412C, 0x37D8, 0x2FE8, 0x293C, 0x2379, 0x1EDF,
        0x1AA9, 0x174E, 0x1424, 0x119C, 0x0F6B, 0x0D51, 0x0BB6, 0x0A40,
        0x5832, 0x4D1C, 0x438E, 0x3BDD, 0x34EE, 0x2EAE, 0x299A, 0x2516,
        0x5570, 0x4CA9, 0x44D9, 0x3E22, 0x3824, 0x32B4, 0x2E17, 0x56A8,
        0x4F46, 0x47E5, 0x41CF, 0x3C3D, 0x375E, 0x5231, 0x4C0F, 0x4639,
        0x415E, 0x5627, 0x50E7, 0x4B85, 0x5597, 0x504F, 0x5A10, 0x5522,
        0x59EB
    )

    private val NLPS = intArrayOf(
        1, 14, 16, 18, 20, 23, 25, 28,
        30, 33, 35, 9, 10, 12, 15, 36,
        38, 39, 40, 42, 43, 45, 46, 48,
        49, 51, 52, 54, 56, 57, 59, 60,
        62, 63, 32, 33, 37, 64, 65, 67,
        68, 69, 70, 72, 73, 74, 75, 77,
        78, 79, 48, 50, 50, 51, 52, 53,
        54, 55, 56, 57, 58, 59, 61, 61,
        65, 80, 81, 82, 83, 84, 86, 87,
        87, 72, 72, 74, 74, 75, 77, 77,
        80, 88, 89, 90, 91, 92, 93, 86,
        88, 95, 96, 97, 99, 99, 93, 95,
        101, 102, 103, 104, 99, 105, 106, 107,
        103, 105, 108, 109, 110, 111, 110, 112,
        112
    )

    private val NMPS = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 13, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 35, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48,
        49, 50, 51, 52, 53, 54, 55, 56,
        57, 58, 59, 60, 61, 62, 63, 32,
        65, 66, 67, 68, 69, 70, 71, 72,
        73, 74, 75, 76, 77, 78, 79, 48,
        81, 82, 83, 84, 85, 86, 87, 71,
        89, 90, 91, 92, 93, 94, 86, 96,
        97, 98, 99, 100, 93, 102, 103, 104,
        99, 106, 107, 103, 109, 107, 111, 109,
        112
    )

    private val SWITCH = intArrayOf(
        1, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 1, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 1, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        1, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        1, 0, 0, 0, 0, 0, 0, 0,
        1, 0, 0, 0, 0, 0, 0, 1,
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 0, 0, 0, 0, 1, 0,
        1
    )

    fun buildBih(
        widthPx: Int,
        heightPx: Int,
        stripeHeightPx: Int = 128
    ): ByteArray {
        val bih = ByteArray(20)
        bih[0] = 0x00 // dl
        bih[1] = 0x00 // d
        bih[2] = 0x01 // p (1 bitplane)
        bih[3] = 0x00 // fill
        writeIntBE(bih, 4, widthPx)
        writeIntBE(bih, 8, heightPx)
        writeIntBE(bih, 12, stripeHeightPx)
        bih[16] = 0x08 // mx = 8 (default AT max offset)
        bih[17] = 0x00 // my = 0
        bih[18] = 0x03 // order = ILEAVE | SEQ
        bih[19] = 0x00 // options = 0x00 (sequential 3-line template, no TPBON)
        return bih
    }

    /**
     * Encodes a 1bpp MSB-first band ([widthPx] x [lines]) into a JBIG1 stripe
     * terminated with SDNORM (0xFF 0x02). Optionally prepends the 20-byte BIH.
     */
    fun encodeStripe(
        packed1bpp: ByteArray,
        widthPx: Int,
        lines: Int,
        includeBih: Boolean,
        totalImageHeightPx: Int = lines
    ): ByteArray {
        val bytesPerRow = (widthPx + 7) / 8
        val out = ByteArrayOutputStream(packed1bpp.size / 2 + 64)
        if (includeBih) {
            out.write(buildBih(widthPx, totalImageHeightPx, lines))
        }

        val st = IntArray(1024)
        val mps = IntArray(1024)
        val coder = QmEncoder(out)

        fun getBit(y: Int, x: Int): Int {
            if (y < 0 || x < 0 || x >= widthPx || y >= lines) return 0
            val idx = y * bytesPerRow + (x ushr 3)
            if (idx >= packed1bpp.size) return 0
            return (packed1bpp[idx].toInt() ushr (7 - (x and 7))) and 1
        }

        for (y in 0 until lines) {
            for (x in 0 until widthPx) {
                // ITU-T T.82 3-line default context (10 bits)
                var cx = 0
                cx = (cx shl 1) or getBit(y - 2, x - 1)
                cx = (cx shl 1) or getBit(y - 2, x)
                cx = (cx shl 1) or getBit(y - 2, x + 1)
                cx = (cx shl 1) or getBit(y - 1, x - 2)
                cx = (cx shl 1) or getBit(y - 1, x - 1)
                cx = (cx shl 1) or getBit(y - 1, x)
                cx = (cx shl 1) or getBit(y - 1, x + 1)
                cx = (cx shl 1) or getBit(y - 1, x + 2)
                cx = (cx shl 1) or getBit(y, x - 2)
                cx = (cx shl 1) or getBit(y, x - 1)

                val pix = getBit(y, x)
                coder.encode(pix, cx, st, mps)
            }
        }
        coder.flush()
        // SDNORM marker (End of Stripe)
        out.write(0xFF)
        out.write(0x02)
        return out.toByteArray()
    }

    private class QmEncoder(private val out: ByteArrayOutputStream) {
        private var a = 0x10000
        private var c = 0
        private var ct = 11
        private var buffer = 0
        private var sc = 0
        private var started = false

        fun encode(pix: Int, cx: Int, st: IntArray, mps: IntArray) {
            val state = st[cx]
            val lsz = LSZ[state]
            if (pix == mps[cx]) {
                a -= lsz
                if (a < 0x8000) {
                    if (a < lsz) {
                        c += a
                        a = lsz
                    }
                    st[cx] = NMPS[state]
                    renormalize()
                }
            } else {
                a -= lsz
                if (a >= lsz) {
                    c += a
                    a = lsz
                }
                if (SWITCH[state] != 0) {
                    mps[cx] = 1 - mps[cx]
                }
                st[cx] = NLPS[state]
                renormalize()
            }
        }

        private fun renormalize() {
            do {
                a = a shl 1
                c = c shl 1
                ct--
                if (ct == 0) {
                    byteOut()
                    ct = 8
                }
            } while (a < 0x8000)
        }

        private fun byteOut() {
            var temp = c ushr 19
            if (temp > 0xFF) {
                if (started) out.write(buffer + 1)
                while (sc > 0) {
                    out.write(0x00)
                    sc--
                }
                buffer = temp and 0xFF
                started = true
            } else if (temp == 0xFF) {
                sc++
            } else {
                if (started) out.write(buffer)
                while (sc > 0) {
                    out.write(0xFF)
                    out.write(0x00)
                    sc--
                }
                buffer = temp and 0xFF
                started = true
            }
            c = c and 0x7FFFF
        }

        fun flush() {
            val temp = (a - 1 + c) and 0xFFFF0000.toInt()
            c = if (temp < c) temp + 0x8000 else temp
            c = c shl ct
            byteOut()
            c = c shl 8
            byteOut()
            if (started) {
                out.write(buffer)
                if (buffer == 0xFF) out.write(0x00)
            }
            while (sc > 0) {
                out.write(0xFF)
                out.write(0x00)
                sc--
            }
        }
    }

    private fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
