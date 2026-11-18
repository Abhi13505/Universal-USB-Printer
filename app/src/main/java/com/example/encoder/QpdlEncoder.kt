package com.example.encoder

import com.example.document.HalftoneDitherEngine
import com.example.model.DitherMode
import com.example.model.DriverProtocol
import com.example.model.PaperSize
import com.example.usb.UsbByteTransport
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Samsung / Xerox QPDL / SPL wire encoder supporting:
 * - 0x11 (SCX-3200 / ML-1660 Algo0x11: 128-line column-major bitwise-inverted LZS + 64-entry ptr table + uint32 LE prefix)
 * - 0x13 (Monochrome JBIG1 ITU-T T.82 + 32-byte BE subheader 39 AB CD EF)
 * - 0x15 (Color/v3 QPDL with 12-byte 0x0C header including plane byte 0x04 + JBIG1 BIH)
 */
object QpdlEncoder {

    const val BAND_HEIGHT = 128
    private const val UEL = "\u001B%-12345X"

    fun buildStartPageHeader(
        widthPx: Int,
        heightPx: Int,
        dpiX: Int,
        dpiY: Int,
        paperSize: PaperSize,
        copies: Int,
        isColor: Boolean
    ): ByteArray {
        val alignedW = ((widthPx + 31) / 32) * 32
        // Normalize dimensions to 300-DPI coordinates per Samsung QPDL specification
        val normWidth = ((alignedW.toLong() * 300L) / dpiX).toInt().coerceIn(1, 0xFFFF)
        val normHeight = ((heightPx.toLong() * 300L) / dpiY).toInt().coerceIn(1, 0xFFFF)
        val yResCode = (dpiY / 100).coerceIn(1, 12)
        val xResCode = (dpiX / 100).coerceIn(1, 12)
        val duplexSimplexByte = if (isColor) 0x00 else 0x01

        val hdr = ByteArray(17)
        hdr[0] = 0x00 // RECTYPE 0x00: Start Page
        hdr[1] = yResCode.toByte()
        hdr[2] = ((copies ushr 8) and 0xFF).toByte()
        hdr[3] = (copies and 0xFF).toByte()
        hdr[4] = paperSize.qpdlCode.toByte()
        hdr[5] = ((normWidth ushr 8) and 0xFF).toByte()
        hdr[6] = (normWidth and 0xFF).toByte()
        hdr[7] = ((normHeight ushr 8) and 0xFF).toByte()
        hdr[8] = (normHeight and 0xFF).toByte()
        hdr[9] = 0x01 // Paper source Auto
        hdr[10] = 0x00 // Media type Plain
        hdr[11] = duplexSimplexByte.toByte()
        hdr[12] = 0x00
        hdr[13] = 0x00
        hdr[14] = 0x03 // QPDL version 3
        hdr[15] = 0x00
        hdr[16] = xResCode.toByte() // X-res code at byte 16
        return hdr
    }

    /**
     * Pads a 1bpp row-major band to 128 lines, transposes into column-major order (band[x * 128 + y]),
     * and bitwise-inverts all bytes (0xFF = white, 0x00 = black).
     */
    fun transposeAndInvertBandFor0x11(
        rowMajor1bpp: ByteArray,
        widthPx: Int,
        actualLines: Int
    ): ByteArray {
        val alignedW = ((widthPx + 31) / 32) * 32
        val widthBytes = alignedW / 8
        val out = ByteArray(widthBytes * BAND_HEIGHT)
        // Initialize all white (0xFF) for padded lines beyond actualLines
        out.fill(0xFF.toByte())

        val copyLines = actualLines.coerceAtMost(BAND_HEIGHT)
        for (y in 0 until copyLines) {
            val rowStart = y * widthBytes
            for (x in 0 until widthBytes) {
                val srcByte = if (rowStart + x < rowMajor1bpp.size) {
                    rowMajor1bpp[rowStart + x].toInt() and 0xFF
                } else {
                    0
                }
                // Column-major index band[x * 128 + y], bitwise-inverted
                out[x * BAND_HEIGHT + y] = (srcByte xor 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * Returns true when a column-major bitwise-inverted band contains only white bytes (0xFF).
     */
    fun isBlankInvertedBand(colMajorInverted: ByteArray): Boolean {
        for (b in colMajorInverted) {
            if (b != 0xFF.toByte()) return false
        }
        return true
    }

    /**
     * Pure-Kotlin SpliX Algo0x11 compressor:
     * - 64-entry uint16 LE pointer table (128 bytes)
     * - uint32 LE uncompressed prefix size (4 bytes)
     * - Uncompressed prefix bytes followed by LZS bit-packed stream
     */
    fun compressAlgo0x11(colMajorInverted: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(colMajorInverted.size / 2 + 160)

        // 64-entry uint16 LE pointer table
        val ptrTable = IntArray(64)
        for (i in 0 until 64) {
            val offset = (i * 2).coerceAtMost((colMajorInverted.size - 1).coerceAtLeast(0))
            ptrTable[i] = offset
            out.write(offset and 0xFF)
            out.write((offset ushr 8) and 0xFF)
        }

        // uint32 LE uncompressed prefix size (use 0 so entire band is cleanly LZS-coded)
        val prefixSize = 0
        out.write(prefixSize and 0xFF)
        out.write((prefixSize ushr 8) and 0xFF)
        out.write((prefixSize ushr 16) and 0xFF)
        out.write((prefixSize ushr 24) and 0xFF)

        // LZS bit-writer (MSB-first)
        val bits = BitWriter(out)
        var pos = prefixSize
        val total = colMajorInverted.size

        while (pos < total) {
            var bestLen = 0
            var bestOff = 0
            val maxLookback = minOf(pos, 2047)
            val maxMatch = minOf(total - pos, 271)

            if (maxLookback >= 1 && maxMatch >= 2) {
                var off = 1
                while (off <= maxLookback) {
                    if (colMajorInverted[pos - off] == colMajorInverted[pos] &&
                        colMajorInverted[pos - off + 1] == colMajorInverted[pos + 1]
                    ) {
                        var len = 2
                        while (len < maxMatch && colMajorInverted[pos - off + len] == colMajorInverted[pos + len]) {
                            len++
                        }
                        if (len > bestLen) {
                            bestLen = len
                            bestOff = off
                            if (bestLen >= 64) break
                        }
                    }
                    off++
                }
            }

            if (bestLen >= 2) {
                // Match flag bit = 1
                bits.writeBit(1)
                if (bestOff < 128) {
                    bits.writeBit(1)
                    bits.writeBits(bestOff, 7)
                } else {
                    bits.writeBit(0)
                    bits.writeBits(bestOff, 11)
                }
                writeLzsLength(bits, bestLen)
                pos += bestLen
            } else {
                // Literal flag bit = 0 + 8-bit byte
                bits.writeBit(0)
                bits.writeBits(colMajorInverted[pos].toInt() and 0xFF, 8)
                pos++
            }
        }

        // LZS End-of-Stream marker: 1 1 0000000 (9 bits)
        bits.writeBit(1)
        bits.writeBit(1)
        bits.writeBits(0, 7)
        bits.flush()

        return out.toByteArray()
    }

    private fun writeLzsLength(bits: BitWriter, length: Int) {
        when (length) {
            2 -> bits.writeBits(0b00, 2)
            3 -> bits.writeBits(0b01, 2)
            4 -> bits.writeBits(0b10, 2)
            5 -> bits.writeBits(0b1100, 4)
            6 -> bits.writeBits(0b1101, 4)
            7 -> bits.writeBits(0b1110, 4)
            else -> {
                var rem = length - 8
                while (rem >= 15) {
                    bits.writeBits(0b1111, 4)
                    rem -= 15
                }
                bits.writeBits(0b1111, 4)
                bits.writeBits(rem, 4)
            }
        }
    }

    private class BitWriter(private val out: ByteArrayOutputStream) {
        private var currentByte = 0
        private var bitCount = 0

        fun writeBit(bit: Int) {
            currentByte = (currentByte shl 1) or (bit and 1)
            bitCount++
            if (bitCount == 8) {
                out.write(currentByte)
                currentByte = 0
                bitCount = 0
            }
        }

        fun writeBits(value: Int, count: Int) {
            for (i in (count - 1) downTo 0) {
                writeBit((value ushr i) and 1)
            }
        }

        fun flush() {
            if (bitCount > 0) {
                currentByte = currentByte shl (8 - bitCount)
                out.write(currentByte)
                currentByte = 0
                bitCount = 0
            }
        }
    }

    fun buildBandPacket0x11(
        bandIndex: Int,
        alignedWidthPx: Int,
        algo0x11Payload: ByteArray
    ): ByteArray {
        val subheader = byteArrayOf(0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x09)
        val checksum = byteSum32(subheader, algo0x11Payload)
        val dataSize = subheader.size + algo0x11Payload.size + 4

        val out = ByteArrayOutputStream(11 + dataSize)
        // 11-byte monochrome RECTYPE 0x0C header (NO plane byte)
        out.write(0x0C)
        out.write(bandIndex and 0xFF)
        writeShortBE(out, alignedWidthPx)
        writeShortBE(out, BAND_HEIGHT)
        out.write(0x11)
        writeIntBE(out, dataSize)

        out.write(subheader)
        out.write(algo0x11Payload)
        writeIntBE(out, checksum)
        return out.toByteArray()
    }

    fun buildBandPacket0x13(
        bandIndex: Int,
        alignedWidthPx: Int,
        jbigStripePayload: ByteArray,
        stripeState: Int
    ): ByteArray {
        // 32-byte Big-Endian subheader: 39 AB CD EF + payloadSize (4B BE) + stripeState (1B) + 23 zeros
        val subheader = ByteArray(32)
        subheader[0] = 0x39
        subheader[1] = 0xAB.toByte()
        subheader[2] = 0xCD.toByte()
        subheader[3] = 0xEF.toByte()
        val sz = jbigStripePayload.size
        subheader[4] = ((sz ushr 24) and 0xFF).toByte()
        subheader[5] = ((sz ushr 16) and 0xFF).toByte()
        subheader[6] = ((sz ushr 8) and 0xFF).toByte()
        subheader[7] = (sz and 0xFF).toByte()
        subheader[8] = stripeState.toByte() // 0x00 first/only, 0x01 middle, 0x02 last
        // bytes 9..31 remain 0x00 (23 zeros)

        val checksum = byteSum32(subheader, jbigStripePayload)
        val dataSize = subheader.size + jbigStripePayload.size + 4

        val out = ByteArrayOutputStream(11 + dataSize)
        // 11-byte monochrome RECTYPE 0x0C header (NO plane byte)
        out.write(0x0C)
        out.write(bandIndex and 0xFF)
        writeShortBE(out, alignedWidthPx)
        writeShortBE(out, BAND_HEIGHT)
        out.write(0x13)
        writeIntBE(out, dataSize)

        out.write(subheader)
        out.write(jbigStripePayload)
        writeIntBE(out, checksum)
        return out.toByteArray()
    }

    fun buildBandPacket0x15(
        bandIndex: Int,
        alignedWidthPx: Int,
        planeByte: Int,
        jbigBihStripePayload: ByteArray
    ): ByteArray {
        val checksum = byteSum32(jbigBihStripePayload)
        val dataSize = jbigBihStripePayload.size + 4

        val out = ByteArrayOutputStream(12 + dataSize)
        // 12-byte Color/v3 RECTYPE 0x0C header INCLUDING plane byte (e.g. 0x04)
        out.write(0x0C)
        out.write(bandIndex and 0xFF)
        writeShortBE(out, alignedWidthPx)
        writeShortBE(out, BAND_HEIGHT)
        out.write(planeByte and 0xFF)
        out.write(0x15)
        writeIntBE(out, dataSize)

        out.write(jbigBihStripePayload)
        writeIntBE(out, checksum)
        return out.toByteArray()
    }

    fun byteSum32(vararg arrays: ByteArray): Int {
        var sum = 0
        for (arr in arrays) {
            for (b in arr) {
                sum += (b.toInt() and 0xFF)
            }
        }
        return sum
    }

    private fun writeShortBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    class QpdlSession(
        private val transport: UsbByteTransport,
        private val protocol: DriverProtocol,
        private val dpiX: Int,
        private val dpiY: Int,
        private val paperSize: PaperSize,
        private val copies: Int
    ) {
        init {
            val pjl = "@PJL DEFAULT SERVICEDATE=20261118\r\n@PJL ENTER LANGUAGE = QPDL\r\n"
            transport.write(pjl.toByteArray(StandardCharsets.US_ASCII))
        }

        fun beginPage(widthPx: Int, heightPx: Int) {
            val isColor = protocol == DriverProtocol.SAMSUNG_QPDL_0X15_COLOR
            val startHdr = buildStartPageHeader(
                widthPx = widthPx,
                heightPx = heightPx,
                dpiX = dpiX,
                dpiY = dpiY,
                paperSize = paperSize,
                copies = copies,
                isColor = isColor
            )
            transport.write(startHdr)
        }

        fun writeBand(
            bandIndex: Int,
            totalBands: Int,
            argb: IntArray,
            alignedWidthPx: Int,
            bandHeightPx: Int,
            startY: Int,
            totalPageHeightPx: Int,
            ditherMode: DitherMode,
            lut: IntArray,
            ditherState: HalftoneDitherEngine.BandDitherState
        ) {
            when (protocol) {
                DriverProtocol.SAMSUNG_QPDL_0X11 -> {
                    val mono = HalftoneDitherEngine.ditherBand1bpp(
                        argb = argb,
                        width = alignedWidthPx,
                        height = bandHeightPx,
                        startY = startY,
                        ditherMode = ditherMode,
                        lut = lut,
                        state = ditherState
                    )
                    val colMajorInv = transposeAndInvertBandFor0x11(mono, alignedWidthPx, bandHeightPx)
                    if (isBlankInvertedBand(colMajorInv)) return
                    val compressed = compressAlgo0x11(colMajorInv)
                    val packet = buildBandPacket0x11(bandIndex, alignedWidthPx, compressed)
                    transport.write(packet)
                }

                DriverProtocol.SAMSUNG_QPDL_0X13_JBIG1 -> {
                    val mono = padTo128Lines(
                        HalftoneDitherEngine.ditherBand1bpp(
                            argb = argb,
                            width = alignedWidthPx,
                            height = bandHeightPx,
                            startY = startY,
                            ditherMode = ditherMode,
                            lut = lut,
                            state = ditherState
                        ),
                        alignedWidthPx,
                        bandHeightPx
                    )
                    val isFirst = bandIndex == 0
                    val isLast = bandIndex == totalBands - 1
                    val stripeState = when {
                        isFirst -> 0x00
                        isLast -> 0x02
                        else -> 0x01
                    }
                    val jbig = Jbig1Encoder.encodeStripe(
                        packed1bpp = mono,
                        widthPx = alignedWidthPx,
                        lines = BAND_HEIGHT,
                        includeBih = isFirst,
                        totalImageHeightPx = totalPageHeightPx
                    )
                    val packet = buildBandPacket0x13(bandIndex, alignedWidthPx, jbig, stripeState)
                    transport.write(packet)
                }

                DriverProtocol.SAMSUNG_QPDL_0X15_COLOR -> {
                    // Emit K plane (0x04) or CMYK separated 1bpp JBIG planes
                    val mono = padTo128Lines(
                        HalftoneDitherEngine.ditherBand1bpp(
                            argb = argb,
                            width = alignedWidthPx,
                            height = bandHeightPx,
                            startY = startY,
                            ditherMode = ditherMode,
                            lut = lut,
                            state = ditherState
                        ),
                        alignedWidthPx,
                        bandHeightPx
                    )
                    val jbig = Jbig1Encoder.encodeStripe(
                        packed1bpp = mono,
                        widthPx = alignedWidthPx,
                        lines = BAND_HEIGHT,
                        includeBih = true,
                        totalImageHeightPx = BAND_HEIGHT
                    )
                    val packet = buildBandPacket0x15(
                        bandIndex = bandIndex,
                        alignedWidthPx = alignedWidthPx,
                        planeByte = 0x04,
                        jbigBihStripePayload = jbig
                    )
                    transport.write(packet)
                }

                else -> Unit
            }
        }

        fun endPage() {
            // RECTYPE 0x01 End Page
            transport.write(byteArrayOf(0x01, copies.coerceIn(1, 99).toByte()))
        }

        fun finish() {
            // 0x09 + UEL footer
            transport.write(byteArrayOf(0x09) + UEL.toByteArray(StandardCharsets.US_ASCII))
        }

        private fun padTo128Lines(mono: ByteArray, widthPx: Int, actualLines: Int): ByteArray {
            if (actualLines == BAND_HEIGHT) return mono
            val bytesPerRow = (widthPx + 7) / 8
            val padded = ByteArray(bytesPerRow * BAND_HEIGHT)
            mono.copyInto(padded, 0, 0, minOf(mono.size, padded.size))
            return padded
        }
    }
}
