package com.example

import com.example.document.HalftoneDitherEngine
import com.example.encoder.PackBitsEncoder
import com.example.encoder.PclEncoder
import com.example.model.DitherMode
import com.example.model.PaperSize
import com.example.usb.UsbByteTransport
import org.junit.Assert.assertArrayEquals
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
    fun `PCL 5c session emits PJL header, Configure Image Data, PackBits mode, and UEL footer`() {
        val transport = FakeTransport()
        val session = PclEncoder.Pcl5Session(
            transport = transport,
            colorMode = true,
            dpiX = 300,
            dpiY = 300,
            paperSize = PaperSize.A4,
            copies = 1,
            jobName = "TestJob"
        )
        session.beginPage(64, 4)
        val argb = IntArray(64 * 4) { 0xFF1D5FD6.toInt() }
        val lut = HalftoneDitherEngine.buildBrightnessContrastLut(0f, 1f)
        session.writeBand(argb, 64, 4, 0, DitherMode.HALFTONE_FLOYD_STEINBERG, lut, HalftoneDitherEngine.BandDitherState(64))
        session.endPage()
        session.finish()

        val bytes = transport.stream.toByteArray()
        val ascii = String(bytes, StandardCharsets.ISO_8859_1)
        assertTrue(ascii.contains("@PJL ENTER LANGUAGE=PCL"))
        assertTrue(ascii.contains("\u001B*v6W")) // Configure Image Data 24-bit
        assertTrue(ascii.contains("\u001B*b2M")) // TIFF PackBits mode
        assertTrue(ascii.contains("\u001B*rC\u000C")) // End graphics + Form feed
        assertTrue(ascii.contains("@PJL EOJ"))
    }
}
