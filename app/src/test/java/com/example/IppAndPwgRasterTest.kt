package com.example

import com.example.encoder.IppUsbEncoder
import com.example.encoder.PackBitsEncoder
import com.example.usb.IppProtocolCodec
import com.example.usb.UsbByteTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class IppAndPwgRasterTest {

    @Test
    fun `buildGetPrinterAttributesIppPayload contains IPP 2_0 header and requested attributes`() {
        val payload = IppProtocolCodec.buildGetPrinterAttributesIppPayload()
        assertEquals(0x02, payload[0].toInt() and 0xFF)
        assertEquals(0x00, payload[1].toInt() and 0xFF)
        assertEquals(0x00, payload[2].toInt() and 0xFF)
        assertEquals(0x0B, payload[3].toInt() and 0xFF) // Get-Printer-Attributes
        assertEquals(0x03, payload.last().toInt() and 0xFF) // End of attributes
    }

    @Test
    fun `parseHttpIppResponse handles both Content-Length and chunked encoding`() {
        val ippResp = buildMockIppAttributesResponse()

        // 1. Content-Length framing
        val clHeader = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${ippResp.size}\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
        val clRaw = clHeader + ippResp
        assertTrue(IppProtocolCodec.isHttpResponseComplete(clRaw))
        val parsedCl = IppProtocolCodec.parseHttpIppResponse(clRaw)
        assertNotNull(parsedCl)
        val capsCl = IppProtocolCodec.parseIppAttributes(parsedCl!!.ippBody)
        assertNotNull(capsCl)
        assertEquals("Canon PIXMA G3010", capsCl!!.makeAndModel)
        assertEquals(true, capsCl.colorSupported)
        assertTrue(capsCl.documentFormatsSupported.contains("image/pwg-raster"))
        assertEquals(600, capsCl.defaultResolutionDpi)

        // 2. Chunked framing
        val chunk1 = IppProtocolCodec.encodeHttpChunk(ippResp.copyOfRange(0, 24))
        val chunk2 = IppProtocolCodec.encodeHttpChunk(ippResp.copyOfRange(24, ippResp.size))
        val chunkHeader = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nTransfer-Encoding: chunked\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
        val chunkRaw = chunkHeader + chunk1 + chunk2 + IppProtocolCodec.encodeHttpFinalChunk()
        assertTrue(IppProtocolCodec.isHttpResponseComplete(chunkRaw))
        val parsedChunked = IppProtocolCodec.parseHttpIppResponse(chunkRaw)
        assertNotNull(parsedChunked)
        val capsChunked = IppProtocolCodec.parseIppAttributes(parsedChunked!!.ippBody)
        assertEquals("Canon PIXMA G3010", capsChunked?.makeAndModel)
    }

    @Test
    fun `buildPwgRasterPageHeader creates exact 1796-byte PWG 5102_4 Table 4 header`() {
        val hdr = IppUsbEncoder.buildPwgRasterPageHeader(
            widthPx = 4960,
            heightPx = 7016,
            dpiX = 600,
            dpiY = 600,
            colorMode = IppUsbEncoder.PwgColorMode.SRGB_24,
            mediaName = "iso_a4_210x297mm",
            totalPages = 2
        )
        assertEquals(1796, hdr.size)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)

        // MediaType at 128..191 = "stationery"
        val mediaTypeStr = String(hdr, 128, "stationery".length, StandardCharsets.US_ASCII)
        assertEquals("stationery", mediaTypeStr)

        // PrintContentOptimize at 192..255 = "auto"
        val optimizeStr = String(hdr, 192, "auto".length, StandardCharsets.US_ASCII)
        assertEquals("auto", optimizeStr)

        // HWResolution at 276, 280
        assertEquals(600, buf.getInt(276))
        assertEquals(600, buf.getInt(280))

        // PageSize in points at 352, 356 (4960 * 72 / 600 = 595, 7016 * 72 / 600 = 842)
        assertEquals(595, buf.getInt(352))
        assertEquals(842, buf.getInt(356))

        // Width, Height in pixels at 372, 376
        assertEquals(4960, buf.getInt(372))
        assertEquals(7016, buf.getInt(376))

        // BitsPerColor (384), BitsPerPixel (388), BytesPerLine (392), ColorOrder (396), ColorSpace (400), NumColors (420)
        assertEquals(8, buf.getInt(384))
        assertEquals(24, buf.getInt(388))
        assertEquals(4960 * 3, buf.getInt(392))
        assertEquals(0, buf.getInt(396))
        assertEquals(19, buf.getInt(400)) // sRGB
        assertEquals(3, buf.getInt(420)) // 3 channels

        // TotalPageCount at 452
        assertEquals(2, buf.getInt(452))

        // PageSizeName at 1732..1795
        val pageSizeNameStr = String(hdr, 1732, "iso_a4_210x297mm".length, StandardCharsets.US_ASCII)
        assertEquals("iso_a4_210x297mm", pageSizeNameStr)
    }

    @Test
    fun `readAndVerifyPrintJobResponse accepts IPP 0x0000 and throws on IPP error status`() {
        val okBody = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x03)
        val okHttp = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${okBody.size}\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII) + okBody

        val okTransport = object : UsbByteTransport {
            var readOnce = false
            override fun write(data: ByteArray, offset: Int, length: Int): Int = length
            override fun read(maxLength: Int, timeoutMs: Int): ByteArray {
                return if (!readOnce) {
                    readOnce = true
                    okHttp
                } else ByteArray(0)
            }
            override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int = 0
        }

        val status = IppProtocolCodec.readAndVerifyPrintJobResponse(okTransport)
        assertNotNull(status)
        assertTrue(status!!.isSuccess)

        // Rejection response: 0x040A client-error-document-format-not-supported
        val errBody = byteArrayOf(0x02, 0x00, 0x04, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x03)
        val errHttp = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${errBody.size}\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII) + errBody

        val errTransport = object : UsbByteTransport {
            var readOnce = false
            override fun write(data: ByteArray, offset: Int, length: Int): Int = length
            override fun read(maxLength: Int, timeoutMs: Int): ByteArray {
                return if (!readOnce) {
                    readOnce = true
                    errHttp
                } else ByteArray(0)
            }
            override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int = 0
        }

        try {
            IppProtocolCodec.readAndVerifyPrintJobResponse(errTransport)
            fail("Expected IOException when IPP printer returns 0x040A")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("0x040A"))
        }
    }

    @Test
    fun `compressPwgPixels packs repeating 24-bit RGB pixels cleanly`() {
        val whitePixel = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val line = ByteArray(10 * 3) { idx -> whitePixel[idx % 3] }
        val packed = PackBitsEncoder.compressPwgPixels(line, 3)
        // Repeat 10 times -> command byte = 9, followed by 3 RGB bytes
        assertArrayEquals(byteArrayOf(9, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), packed)
    }

    private fun buildMockIppAttributesResponse(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x02); out.write(0x00) // IPP 2.0
        out.write(0x00); out.write(0x00) // successful-ok
        out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x01)
        out.write(0x04) // printer-attributes-tag

        fun writeAttr(tag: Int, name: String, value: ByteArray) {
            out.write(tag)
            val nb = name.toByteArray(StandardCharsets.UTF_8)
            out.write((nb.size ushr 8) and 0xFF); out.write(nb.size and 0xFF)
            out.write(nb)
            out.write((value.size ushr 8) and 0xFF); out.write(value.size and 0xFF)
            out.write(value)
        }

        writeAttr(0x41, "printer-make-and-model", "Canon PIXMA G3010".toByteArray(StandardCharsets.UTF_8))
        writeAttr(0x22, "color-supported", byteArrayOf(1))
        writeAttr(0x49, "document-format-supported", "image/pwg-raster".toByteArray(StandardCharsets.UTF_8))
        writeAttr(0x49, "", "application/pdf".toByteArray(StandardCharsets.UTF_8))
        val resBytes = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN).putInt(600).putInt(600).put(3).array()
        writeAttr(0x32, "printer-resolution-default", resBytes)
        out.write(0x03) // end-of-attributes
        return out.toByteArray()
    }
}
