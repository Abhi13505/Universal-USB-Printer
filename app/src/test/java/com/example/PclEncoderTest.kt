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
}
