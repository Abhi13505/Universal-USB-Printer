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
import kotlin.math.roundToInt

object IppUsbEncoder {

    private const val PWG_HEADER_SIZE = 1796
    private const val PWG_COLOR_SPACE_BLACK = 3
    private const val PWG_COLOR_SPACE_SGRAY = 18
    private const val PWG_COLOR_SPACE_SRGB = 19

    enum class PwgColorMode(
        val bitsPerColor: Int,
        val bitsPerPixel: Int,
        val colorSpace: Int,
        val numColors: Int
    ) {
        SRGB_24(8, 24, PWG_COLOR_SPACE_SRGB, 3),
        SGRAY_8(8, 8, PWG_COLOR_SPACE_SGRAY, 1),
        BLACK_1(1, 1, PWG_COLOR_SPACE_BLACK, 1)
    }

    /**
     * Builds a PWG 5102.4 Table 4 compliant 1,796-byte big-endian Page Header:
     * - 0..63: PwgRaster ("PwgRaster")
     * - 128..191: MediaType (e.g. "stationery")
     * - 192..255: PrintContentOptimize (e.g. "auto")
     * - 276..283: HWResolution (dpiX, dpiY)
     * - 340..343: NumCopies
     * - 352..359: PageSize in points (1/72 inch): widthPt at 352, heightPt at 356
     * - 372..379: Width (372), Height (376) in pixels
     * - 384..403: BitsPerColor (384), BitsPerPixel (388), BytesPerLine (392), ColorOrder (396), ColorSpace (400)
     * - 420..423: NumColors (420)
     * - 452..479: TotalPageCount (452), CrossFeedTransform (456), FeedTransform (460), ImageBox (464..479)
     * - 480..483: AlternatePrimary (0x00FFFFFF)
     * - 1668..1731: RenderingIntent ("auto")
     * - 1732..1795: PageSizeName (PWG media keyword, e.g. "iso_a4_210x297mm")
     */
    fun buildPwgRasterPageHeader(
        widthPx: Int,
        heightPx: Int,
        dpiX: Int,
        dpiY: Int,
        colorMode: PwgColorMode,
        mediaName: String,
        totalPages: Int = 1,
        mediaType: String = "stationery",
        numCopies: Int = 1
    ): ByteArray {
        val buf = ByteBuffer.allocate(PWG_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val bytesPerLine = when (colorMode) {
            PwgColorMode.SRGB_24 -> widthPx * 3
            PwgColorMode.SGRAY_8 -> widthPx
            PwgColorMode.BLACK_1 -> (widthPx + 7) / 8
        }

        // Bytes 0..63: PwgRaster magic string
        buf.position(0)
        buf.put("PwgRaster".toByteArray(StandardCharsets.US_ASCII))

        // Bytes 128..191: MediaType (64-byte nul-terminated ASCII)
        buf.position(128)
        buf.put(mediaType.take(63).toByteArray(StandardCharsets.US_ASCII))

        // Bytes 192..255: PrintContentOptimize (64-byte nul-terminated ASCII)
        buf.position(192)
        buf.put("auto".toByteArray(StandardCharsets.US_ASCII))

        // Bytes 276..283: HWResolution (2 x uint32 BE)
        buf.position(276)
        buf.putInt(dpiX)
        buf.putInt(dpiY)

        // Bytes 340..343: NumCopies (uint32 BE)
        buf.position(340)
        buf.putInt(numCopies.coerceAtLeast(1))

        // Bytes 352..359: PageSize in points (1/72 inch, 2 x uint32 BE)
        val pageWidthPt = ((widthPx * 72.0) / dpiX.coerceAtLeast(1)).roundToInt()
        val pageHeightPt = ((heightPx * 72.0) / dpiY.coerceAtLeast(1)).roundToInt()
        buf.position(352)
        buf.putInt(pageWidthPt)
        buf.putInt(pageHeightPt)

        // Bytes 372..379: Width, Height in pixels
        buf.position(372)
        buf.putInt(widthPx)
        buf.putInt(heightPx)

        // Bytes 384..403: BitsPerColor (384), BitsPerPixel (388), BytesPerLine (392), ColorOrder (396), ColorSpace (400)
        buf.position(384)
        buf.putInt(colorMode.bitsPerColor)
        buf.putInt(colorMode.bitsPerPixel)
        buf.putInt(bytesPerLine)
        buf.putInt(0) // Chunky
        buf.putInt(colorMode.colorSpace)

        // Bytes 420..423: NumColors
        buf.position(420)
        buf.putInt(colorMode.numColors)

        // Bytes 452..463: TotalPageCount (452), CrossFeedTransform (456), FeedTransform (460)
        buf.position(452)
        buf.putInt(totalPages)
        buf.putInt(1)
        buf.putInt(1)

        // Bytes 464..479: ImageBoxLeft/Top/Right/Bottom
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(widthPx)
        buf.putInt(heightPx)

        // Bytes 480..483: AlternatePrimary (white = 0x00FFFFFF)
        buf.putInt(0x00FFFFFF)

        // Bytes 1668..1731: RenderingIntent
        buf.position(1668)
        buf.put("auto".toByteArray(StandardCharsets.US_ASCII))

        // Bytes 1732..1795: PageSizeName (PWG self-describing media size name)
        buf.position(1732)
        buf.put(mediaName.take(63).toByteArray(StandardCharsets.US_ASCII))

        return buf.array()
    }

    class PwgStreamSession(
        private val transport: UsbByteTransport,
        jobName: String,
        private val copies: Int,
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
                totalPages = totalPages,
                numCopies = 1
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
                IppProtocolCodec.readAndVerifyPrintJobResponse(transport)
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
        IppProtocolCodec.readAndVerifyPrintJobResponse(transport)
    }
}
