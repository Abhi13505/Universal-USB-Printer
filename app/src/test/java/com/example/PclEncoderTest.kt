package com.example

import com.example.document.HalftoneDitherEngine
import com.example.encoder.PackBitsEncoder
import com.example.encoder.PclEncoder
import com.example.model.DitherMode
import com.example.model.PaperSize
import com.example.usb.UsbByteTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class PclEncoderTest {

    private class FakeTransport : UsbByteTransport {
        val stream = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            stream.write(data, offset, length)
            return length
        }
        override fun read(maxLength: Int, timeoutMs: Int): ByteArray = ByteArray(0)
        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray?,
            length: Int,
            timeoutMs: Int
        ): Int = 0
    }

    @Test
    fun `compressTiffPackBits encodes runs and literals accurately`() {
        val input = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0x11, 0x22)
        val compressed = PackBitsEncoder.compressTiffPackBits(input)
        // Run of 4x 0xAA -> control (-3 = 0xFD), byte 0xAA; Literal 2 bytes -> control 1, 0x11, 0x22
        assertArrayEquals(
            byteArrayOf(0xFD.toByte(), 0xAA.toByte(), 0x01, 0x11, 0x22),
            compressed
        )
    }

    @Test
    fun `PCL 5c Configure Image Data uses 00 03 00 08 08 08 for 24-bit RGB channels`() {
        val expected = byteArrayOf(
            0x1B, '*'.code.toByte(), 'v'.code.toByte(), '6'.code.toByte(), 'W'.code.toByte(),
            0x00, 0x03, 0x00, 0x08, 0x08, 0x08
        )
        assertArrayEquals(expected, PclEncoder.PCL5C_CONFIGURE_IMAGE_DATA_24BIT_RGB)
    }

    @Test
    fun `PCL 5c session emits PJL header, valid 00 03 00 08 08 08 CID, single copy command, and clamps 1200 DPI to 600`() {
        val transport = FakeTransport()
        val session = PclEncoder.Pcl5Session(
            transport = transport,
            colorMode = true,
            dpiX = 1200,
            dpiY = 600,
            paperSize = PaperSize.A4,
            copies = 3,
            jobName = "TestJob"
        )
        assertEquals(600, session.resolution)
        session.beginPage(64, 4)
        val argb = IntArray(64 * 4) { 0xFF1D5FD6.toInt() }
        val lut = HalftoneDitherEngine.buildBrightnessContrastLut(0f, 1f)
        session.writeBand(argb, 64, 4, 0, DitherMode.HALFTONE_FLOYD_STEINBERG, lut, HalftoneDitherEngine.BandDitherState(64))
        session.endPage()
        session.finish()

        val bytes = transport.stream.toByteArray()
        val ascii = String(bytes, StandardCharsets.ISO_8859_1)
        assertTrue(ascii.contains("@PJL ENTER LANGUAGE=PCL"))
        assertTrue(ascii.contains("@PJL SET RESOLUTION=600"))
        // Ensure copies are NOT duplicated in PJL and PCL
        assertFalse(ascii.contains("@PJL SET COPIES="))
        assertTrue(ascii.contains("\u001B&l3X"))
        assertTrue(ascii.contains("\u001B*t600R"))
        assertTrue(ascii.contains("\u001B*v6W\u0000\u0003\u0000\u0008\u0008\u0008"))
        assertTrue(ascii.contains("\u001B*b2M")) // TIFF PackBits mode
        assertTrue(ascii.contains("\u001B*rC\u000C")) // End graphics + Form feed
        assertTrue(ascii.contains("@PJL EOJ"))
    }

    @Test
    fun `PCL-XL session emits exact OpenDataSource, BeginImage ColorMapping 0x64 ColorDepth 0x62, ReadImage CompressMode 0x65, and CloseDataSource bytes`() {
        val transport = FakeTransport()
        val session = PclEncoder.Pcl6XlSession(
            transport = transport,
            colorMode = true,
            dpiX = 600,
            dpiY = 600,
            paperSize = PaperSize.A4,
            copies = 2,
            jobName = "XLTest"
        )
        session.beginPage(widthPx = 32, heightPx = 4)
        val argb = IntArray(32 * 4) { 0xFFFFFFFF.toInt() }
        val lut = HalftoneDitherEngine.buildBrightnessContrastLut(0f, 1f)
        session.writeBand(argb, widthPx = 32, bandHeightPx = 4, startY = 0, lut = lut)
        session.endPage()
        session.finish()

        val bytes = transport.stream.toByteArray()

        // 1. BeginSession (0x41) followed immediately by OpenDataSource (0x48):
        // SourceType (0x88) = 0 (eDefaultDataSource), DataOrg (0x82) = 1 (eBinaryLowByteFirst)
        val expectedSessionAndOpen = byteArrayOf(
            // Measure = eInch (0)
            0xC0.toByte(), 0x00, 0xF8.toByte(), 0x86.toByte(),
            // UnitsPerMeasure = (600, 600) LE
            0xD1.toByte(), 0x58, 0x02, 0x58, 0x02, 0xF8.toByte(), 0x89.toByte(),
            // ErrorReport = 3
            0xC0.toByte(), 0x03, 0xF8.toByte(), 0x8F.toByte(),
            // BeginSession
            0x41,
            // SourceType = 0 (eDefaultDataSource)
            0xC0.toByte(), 0x00, 0xF8.toByte(), 0x88.toByte(),
            // DataOrg = 1 (eBinaryLowByteFirst)
            0xC0.toByte(), 0x01, 0xF8.toByte(), 0x82.toByte(),
            // OpenDataSource
            0x48
        )
        assertTrue(
            "Expected exact BeginSession + OpenDataSource byte sequence",
            containsSubsequence(bytes, expectedSessionAndOpen)
        )

        // 2. BeginImage (0xB0): ColorMapping (0x64) = 0 (eDirectPixel), ColorDepth (0x62) = 2 (e8Bit)
        val expectedBeginImage = byteArrayOf(
            // ColorMapping(0x64) = 0 (eDirectPixel)
            0xC0.toByte(), 0x00, 0xF8.toByte(), 0x64,
            // ColorDepth(0x62) = 2 (e8Bit)
            0xC0.toByte(), 0x02, 0xF8.toByte(), 0x62,
            // SourceWidth(0x6C) = 32
            0xC1.toByte(), 0x20, 0x00, 0xF8.toByte(), 0x6C,
            // SourceHeight(0x6B) = 4
            0xC1.toByte(), 0x04, 0x00, 0xF8.toByte(), 0x6B,
            // DestinationSize(0x67) = (32, 4)
            0xD1.toByte(), 0x20, 0x00, 0x04, 0x00, 0xF8.toByte(), 0x67,
            // BeginImage operator
            0xB0.toByte()
        )
        assertTrue(
            "Expected exact BeginImage byte sequence with ColorMapping=0x64 and ColorDepth=0x62",
            containsSubsequence(bytes, expectedBeginImage)
        )

        // 3. ReadImage (0xB1): StartLine(0x6D)=0, BlockHeight(0x63)=4, CompressMode(0x65)=0 (eNoCompression), then 0xFA + uint32 LE(32*4*3=384 = 0x00000180)
        val expectedReadImage = byteArrayOf(
            0xC1.toByte(), 0x00, 0x00, 0xF8.toByte(), 0x6D,
            0xC1.toByte(), 0x04, 0x00, 0xF8.toByte(), 0x63,
            0xC0.toByte(), 0x00, 0xF8.toByte(), 0x65,
            0xB1.toByte(),
            0xFA.toByte(), 0x80.toByte(), 0x01, 0x00, 0x00
        )
        assertTrue(
            "Expected exact ReadImage byte sequence with CompressMode=0x65",
            containsSubsequence(bytes, expectedReadImage)
        )

        // 4. EndImage (0xB2) -> PageCopies(0x31)=2 -> EndPage(0x44) -> CloseDataSource(0x49) -> EndSession(0x42)
        val expectedEndAndClose = byteArrayOf(
            0xB2.toByte(),
            0xC1.toByte(), 0x02, 0x00, 0xF8.toByte(), 0x31,
            0x44,
            0x49,
            0x42
        )
        assertTrue(
            "Expected exact EndImage, PageCopies, EndPage, CloseDataSource, EndSession byte sequence",
            containsSubsequence(bytes, expectedEndAndClose)
        )
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
