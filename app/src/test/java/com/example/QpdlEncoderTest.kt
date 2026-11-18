package com.example

import com.example.encoder.Jbig1Encoder
import com.example.encoder.QpdlEncoder
import com.example.model.PaperSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QpdlEncoderTest {

    @Test
    fun `buildStartPageHeader emits exact 17-byte RECTYPE 0x00 with 300-DPI normalized dimensions`() {
        // 600 DPI page of 4960x7016 should normalize to 2480x3508 at 300 DPI
        val hdr = QpdlEncoder.buildStartPageHeader(
            widthPx = 4960,
            heightPx = 7016,
            dpiX = 600,
            dpiY = 600,
            paperSize = PaperSize.A4,
            copies = 1,
            isColor = false
        )
        assertEquals(17, hdr.size)
        assertEquals(0x00, hdr[0].toInt() and 0xFF) // RECTYPE 0x00
        assertEquals(6, hdr[1].toInt() and 0xFF) // yResCode 600/100
        assertEquals(2, hdr[4].toInt() and 0xFF) // PaperCode A4 = 2
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
        assertEquals(2480, buf.getShort(5).toInt() and 0xFFFF)
        assertEquals(3508, buf.getShort(7).toInt() and 0xFFFF)
        assertEquals(1, hdr[11].toInt() and 0xFF) // duplexSimplexByte = 1
        assertEquals(3, hdr[14].toInt() and 0xFF) // QPDL version 3
        assertEquals(6, hdr[16].toInt() and 0xFF) // xResCode at byte 16
    }

    @Test
    fun `transposeAndInvertBandFor0x11 produces column-major inverted 128-line buffer`() {
        // 32px wide => 4 bytes per row. Put 0xFF (black ink) at row 0, col byte 0; rest 0x00 (white paper)
        val rowMajor = ByteArray(4 * 2)
        rowMajor[0] = 0xFF.toByte()
        val colMajorInv = QpdlEncoder.transposeAndInvertBandFor0x11(rowMajor, 32, 2)
        assertEquals(4 * 128, colMajorInv.size)
        assertEquals(0x00, colMajorInv[0].toInt() and 0xFF) // black ink inverted -> 0x00
        assertEquals(0xFF, colMajorInv[1].toInt() and 0xFF) // white paper inverted -> 0xFF
        assertFalse(QpdlEncoder.isBlankInvertedBand(colMajorInv))

        val blankColMajor = QpdlEncoder.transposeAndInvertBandFor0x11(ByteArray(4 * 128), 32, 128)
        assertTrue(QpdlEncoder.isBlankInvertedBand(blankColMajor))
    }

    @Test
    fun `buildBandPacket0x11 has 11-byte mono header, LE EF CD AB 09 subheader, and BE checksum`() {
        val dummy = ByteArray(64) { 0xFF.toByte() }
        val algo = QpdlEncoder.compressAlgo0x11(dummy)
        // 64-entry uint16 LE table (128 bytes) + uint32 LE prefix (4 bytes) + LZS bytes
        assertTrue(algo.size > 132)

        val packet = QpdlEncoder.buildBandPacket0x11(0, 64, algo)
        assertEquals(0x0C, packet[0].toInt() and 0xFF)
        assertEquals(0x11, packet[6].toInt() and 0xFF) // compression 0x11 at byte 6 (NO plane byte)
        assertEquals(0xEF, packet[11].toInt() and 0xFF)
        assertEquals(0xCD, packet[12].toInt() and 0xFF)
        assertEquals(0xAB, packet[13].toInt() and 0xFF)
        assertEquals(0x09, packet[14].toInt() and 0xFF)

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val declaredSize = buf.getInt(7)
        assertEquals(packet.size - 11, declaredSize)

        val expectedSum = QpdlEncoder.byteSum32(packet.copyOfRange(11, packet.size - 4))
        val actualSum = buf.getInt(packet.size - 4)
        assertEquals(expectedSum, actualSum)
    }

    @Test
    fun `buildBandPacket0x13 uses JBIG1 SDNORM and 32-byte 39 AB CD EF subheader`() {
        val mono = ByteArray(4 * 128)
        mono[0] = 0xAA.toByte()
        val jbig = Jbig1Encoder.encodeStripe(mono, 32, 128, includeBih = true, totalImageHeightPx = 128)
        assertEquals(0xFF, jbig[jbig.size - 2].toInt() and 0xFF)
        assertEquals(0x02, jbig[jbig.size - 1].toInt() and 0xFF) // SDNORM marker

        val packet = QpdlEncoder.buildBandPacket0x13(0, 32, jbig, stripeState = 0x00)
        assertEquals(0x0C, packet[0].toInt() and 0xFF)
        assertEquals(0x13, packet[6].toInt() and 0xFF)
        assertEquals(0x39, packet[11].toInt() and 0xFF)
        assertEquals(0xAB, packet[12].toInt() and 0xFF)
        assertEquals(0xCD, packet[13].toInt() and 0xFF)
        assertEquals(0xEF, packet[14].toInt() and 0xFF)
    }

    @Test
    fun `buildBandPacket0x15 includes 12-byte header with plane byte 0x04`() {
        val jbig = Jbig1Encoder.encodeStripe(ByteArray(4 * 128), 32, 128, includeBih = true, totalImageHeightPx = 128)
        val packet = QpdlEncoder.buildBandPacket0x15(0, 32, planeByte = 0x04, jbigBihStripePayload = jbig)
        assertEquals(0x0C, packet[0].toInt() and 0xFF)
        assertEquals(0x04, packet[6].toInt() and 0xFF) // plane byte at index 6
        assertEquals(0x15, packet[7].toInt() and 0xFF) // compression 0x15 at index 7
    }
}
