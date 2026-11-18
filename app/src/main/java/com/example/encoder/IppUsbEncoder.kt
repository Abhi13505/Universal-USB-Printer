package com.example.encoder

import com.example.document.HalftoneDitherEngine
import com.example.model.DitherMode
import com.example.model.PaperSize
import com.example.usb.IppProtocolCodec
import com.example.usb.UsbByteTransport
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object IppUsbEncoder {

    private const val PWG_HEADER_SIZE = 1796
    private const val PWG_COLOR_SPACE_BLACK = 3
    private const val PWG_COLOR_SPACE_SGRAY = 18
    private const val PWG_COLOR_SPACE_SRGB = 19

    enum class PwgColorMode(
        val bitsPerColor: Int,
        val bitsPerPixel: Int,
        val colorSpace: Int
    ) {
        SRGB_24(8, 24, PWG_COLOR_SPACE_SRGB),
        SGRAY_8(8, 8, PWG_COLOR_SPACE_SGRAY),
        BLACK_1(1, 1, PWG_COLOR_SPACE_BLACK)
    }

    fun buildPwgRasterPageHeader(
        widthPx: Int,
        heightPx: Int,
        dpiX: Int,
        dpiY: Int,
        colorMode: PwgColorMode,
        mediaName: String,
        totalPages: Int = 1
    ): ByteArray {
        val buf = ByteBuffer.allocate(PWG_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val bytesPerLine = when (colorMode) {
            PwgColorMode.SRGB_24 -> widthPx * 3
            PwgColorMode.SGRAY_8 -> widthPx
            PwgColorMode.BLACK_1 -> (widthPx + 7) / 8
        }

        // Bytes 0..63: PwgRaster magic string
        val magic = "PwgRaster".toByteArray(StandardCharsets.US_ASCII)
        buf.position(0)
        buf.put(magic)

        // Bytes 64..127: MediaColor / MediaType / MediaClass -> media name at 284 if needed; MediaPosition=0
        val mediaBytes = mediaName.take(63).toByteArray(StandardCharsets.US_ASCII)
        buf.position(192) // MediaType / MediaClass
        buf.put(mediaBytes)

        // HWResolution at 276 (2 x uint32 BE)
        buf.position(276)
        buf.putInt(dpiX)
        buf.putInt(dpiY)

        // cupsWidth, cupsHeight at 372, 376
        buf.position(372)
        buf.putInt(widthPx)
        buf.putInt(heightPx)

        // cupsBitsPerColor (384), cupsBitsPerPixel (388), cupsBytesPerLine (392), cupsColorOrder (396), cupsColorSpace (400)
        buf.position(384)
        buf.putInt(colorMode.bitsPerColor)
        buf.putInt(colorMode.bitsPerPixel)
        buf.putInt(bytesPerLine)
        buf.putInt(0) // Chunky
        buf.putInt(colorMode.colorSpace)

        // TotalPageCount (420), CrossFeedTransform (424), FeedTransform (428)
        buf.position(420)
        buf.putInt(totalPages)
        buf.putInt(1)
        buf.putInt(1)

        // ImageBoxLeft/Top/Right/Bottom (432..447)
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(widthPx)
        buf.putInt(heightPx)

        return buf.array()
    }

    class PwgStreamSession(
        private val transport: UsbByteTransport,
        jobName: String,
        copies: Int,
        mediaKeyword: String
    ) {
        private var headerSent = false

        init {
            val httpStart = IppProtocolCodec.wrapInHttpPostRequest(
                ippPayloadLength = null,
                chunked = true
            )
            transport.write(httpStart)

            val ippHeader = IppProtocolCodec.buildPrintJobIppHeader(
                documentFormat = "image/pwg-raster",
                jobName = jobName,
                copies = copies,
                mediaKeyword = mediaKeyword
            )
            writeChunk(ippHeader)
            // PWG 5102.4 "RaS2" 4-byte sync word
            writeChunk("RaS2".toByteArray(StandardCharsets.US_ASCII))
            headerSent = true
        }

        fun beginPage(
            widthPx: Int,
            heightPx: Int,
            dpiX: Int,
            dpiY: Int,
            colorMode: PwgColorMode,
            paperSize: PaperSize,
            totalPages: Int
        ) {
            val pageHeader = buildPwgRasterPageHeader(
                widthPx = widthPx,
                heightPx = heightPx,
                dpiX = dpiX,
                dpiY = dpiY,
                colorMode = colorMode,
                mediaName = paperSize.pwgMediaName,
                totalPages = totalPages
            )
            writeChunk(pageHeader)
        }

        fun writeBand(
            argb: IntArray,
            widthPx: Int,
            bandHeightPx: Int,
            startY: Int,
            colorMode: PwgColorMode,
            ditherMode: DitherMode,
            lut: IntArray,
            ditherState: HalftoneDitherEngine.BandDitherState
        ) {
            val bandOut = ByteArrayOutputStream(widthPx * bandHeightPx / 2 + 128)
            when (colorMode) {
                PwgColorMode.SRGB_24 -> {
                    val rgb = HalftoneDitherEngine.extractRgb24Band(argb, widthPx, bandHeightPx, lut)
                    val stride = widthPx * 3
                    for (row in 0 until bandHeightPx) {
                        val line = rgb.copyOfRange(row * stride, (row + 1) * stride)
                        bandOut.write(0) // repeat count = 0 (1 line)
                        bandOut.write(PackBitsEncoder.compressPwgPixels(line, 3))
                    }
                }
                PwgColorMode.SGRAY_8 -> {
                    val gray = HalftoneDitherEngine.extractGray8Band(argb, widthPx, bandHeightPx, lut)
                    for (row in 0 until bandHeightPx) {
                        val line = gray.copyOfRange(row * widthPx, (row + 1) * widthPx)
                        bandOut.write(0)
                        bandOut.write(PackBitsEncoder.compressPwgPixels(line, 1))
                    }
                }
                PwgColorMode.BLACK_1 -> {
                    val mono = HalftoneDitherEngine.ditherBand1bpp(
                        argb = argb,
                        width = widthPx,
                        height = bandHeightPx,
                        startY = startY,
                        ditherMode = ditherMode,
                        lut = lut,
                        state = ditherState
                    )
                    val stride = (widthPx + 7) / 8
                    for (row in 0 until bandHeightPx) {
                        val line = mono.copyOfRange(row * stride, (row + 1) * stride)
                        bandOut.write(0)
                        bandOut.write(PackBitsEncoder.compressPwgPixels(line, 1))
                    }
                }
            }
            val bytes = bandOut.toByteArray()
            if (bytes.isNotEmpty()) {
                writeChunk(bytes)
            }
        }

        fun finish() {
            if (headerSent) {
                transport.write(IppProtocolCodec.encodeHttpFinalChunk())
                transport.read(4096, 1500) // drain HTTP/IPP response if available
            }
        }

        private fun writeChunk(data: ByteArray) {
            if (data.isEmpty()) return
            transport.write(IppProtocolCodec.encodeHttpChunk(data))
        }
    }

    fun streamPdfJob(
        transport: UsbByteTransport,
        jobName: String,
        copies: Int,
        mediaKeyword: String,
        pdfStream: java.io.InputStream
    ) {
        val httpStart = IppProtocolCodec.wrapInHttpPostRequest(
            ippPayloadLength = null,
            chunked = true
        )
        transport.write(httpStart)

        val ippHeader = IppProtocolCodec.buildPrintJobIppHeader(
            documentFormat = "application/pdf",
            jobName = jobName,
            copies = copies,
            mediaKeyword = mediaKeyword
        )
        transport.write(IppProtocolCodec.encodeHttpChunk(ippHeader))

        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = pdfStream.read(buffer)
            if (read <= 0) break
            transport.write(IppProtocolCodec.encodeHttpChunk(buffer, 0, read))
        }

        transport.write(IppProtocolCodec.encodeHttpFinalChunk())
        transport.read(4096, 1500)
    }
}
