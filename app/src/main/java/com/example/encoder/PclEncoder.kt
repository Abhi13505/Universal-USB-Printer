package com.example.encoder

import com.example.document.HalftoneDitherEngine
import com.example.model.DitherMode
import com.example.model.PaperSize
import com.example.usb.UsbByteTransport
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object PclEncoder {

    private const val ESC = "\u001B"
    private const val UEL = "\u001B%-12345X"

    /**
     * HP PCL 5 Technical Reference Manual: valid raster resolutions (ESC * t # R) top out at 600 DPI
     * (75, 100, 150, 200, 300, 600). Values above 600 are invalid in PCL 5 raster mode.
     */
    fun normalizePcl5Resolution(dpi: Int): Int = if (dpi <= 300) 300 else 600

    /**
     * HP PCL 5 Color Technical Reference Manual — Configure Image Data (ESC * v 6 W):
     * Byte 0: 0x00 = Device RGB color space
     * Byte 1: 0x03 = Direct by Pixel encoding (chunky 24-bit RGB)
     * Byte 2: 0x00 = Bits per index (unused for direct-by-pixel)
     * Byte 3: 0x08 = 8 bits for Component 1 (Red)
     * Byte 4: 0x08 = 8 bits for Component 2 (Green)
     * Byte 5: 0x08 = 8 bits for Component 3 (Blue)
     */
    internal val PCL5C_CONFIGURE_IMAGE_DATA_24BIT_RGB = byteArrayOf(
        0x1B, '*'.code.toByte(), 'v'.code.toByte(), '6'.code.toByte(), 'W'.code.toByte(),
        0x00, 0x03, 0x00, 0x08, 0x08, 0x08
    )

    class Pcl5Session(
        private val transport: UsbByteTransport,
        private val colorMode: Boolean,
        dpiX: Int,
        @Suppress("UNUSED_PARAMETER") dpiY: Int,
        private val paperSize: PaperSize,
        private val copies: Int,
        jobName: String
    ) {
        val resolution: Int = normalizePcl5Resolution(dpiX)

        init {
            // Note: Do not emit @PJL SET COPIES here because ESC & l # X is emitted in PCL per page;
            // setting copies in both PJL and PCL causes many printers to multiply copies (N * N).
            val pjl = buildString {
                append(UEL)
                append("@PJL JOB NAME=\"").append(jobName.take(40)).append("\"\r\n")
                append("@PJL SET RESOLUTION=").append(resolution).append("\r\n")
                append("@PJL ENTER LANGUAGE=PCL\r\n")
                append(ESC).append("E") // Single job-start PCL reset
            }
            transport.write(pjl.toByteArray(StandardCharsets.US_ASCII))
        }

        fun beginPage(widthPx: Int, heightPx: Int) {
            val sb = StringBuilder()
            sb.append(ESC).append("&u").append(resolution).append("D") // Unit of measure
            sb.append(ESC).append("&l").append(paperSize.pclCode).append("A") // Paper size
            sb.append(ESC).append("&l0O") // Portrait raster coords (already rotated in band compositor)
            sb.append(ESC).append("&l").append(copies.coerceIn(1, 99)).append("X") // Copies set once in PCL
            sb.append(ESC).append("*t").append(resolution).append("R") // Raster resolution (300 or 600)
            sb.append(ESC).append("*r").append(widthPx).append("S") // Source width
            sb.append(ESC).append("*r").append(heightPx).append("T") // Source height
            transport.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))

            if (colorMode) {
                transport.write(PCL5C_CONFIGURE_IMAGE_DATA_24BIT_RGB)
            }

            val startRaster = buildString {
                append(ESC).append("*p0x0Y")
                append(ESC).append("*r1A") // Start graphics at current cursor
                append(ESC).append("*b2M") // TIFF PackBits compression
            }
            transport.write(startRaster.toByteArray(StandardCharsets.US_ASCII))
        }

        fun writeBand(
            argb: IntArray,
            widthPx: Int,
            bandHeightPx: Int,
            startY: Int,
            ditherMode: DitherMode,
            lut: IntArray,
            ditherState: HalftoneDitherEngine.BandDitherState
        ) {
            val out = ByteArrayOutputStream(widthPx * bandHeightPx / 2 + 256)
            if (colorMode) {
                val rgb = HalftoneDitherEngine.extractRgb24Band(argb, widthPx, bandHeightPx, lut)
                val stride = widthPx * 3
                for (row in 0 until bandHeightPx) {
                    val line = rgb.copyOfRange(row * stride, (row + 1) * stride)
                    val packed = PackBitsEncoder.compressTiffPackBits(line)
                    val cmd = "${ESC}*b${packed.size}W".toByteArray(StandardCharsets.US_ASCII)
                    out.write(cmd)
                    out.write(packed)
                }
            } else {
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
                    val packed = PackBitsEncoder.compressTiffPackBits(line)
                    val cmd = "${ESC}*b${packed.size}W".toByteArray(StandardCharsets.US_ASCII)
                    out.write(cmd)
                    out.write(packed)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isNotEmpty()) {
                transport.write(bytes)
            }
        }

        fun endPage() {
            val endCmd = "${ESC}*rC\u000C".toByteArray(StandardCharsets.US_ASCII)
            transport.write(endCmd)
        }

        fun finish() {
            val footer = "${ESC}E${UEL}@PJL EOJ\r\n${UEL}".toByteArray(StandardCharsets.US_ASCII)
            transport.write(footer)
        }
    }

    /**
     * PCL 6 (PCL-XL 2.0) binary stream encoder verified against HP PCL XL Protocol Class 2.0 / pxattr.h:
     * - Operators: BeginSession (0x41), EndSession (0x42), BeginPage (0x43), EndPage (0x44),
     *   OpenDataSource (0x48), CloseDataSource (0x49), SetColorSpace (0x6A), SetCursor (0x6B),
     *   BeginImage (0xB0), ReadImage (0xB1), EndImage (0xB2)
     * - Attributes: ColorSpace (0x03), MediaSize (0x25), Orientation (0x28), PageCopies (0x31),
     *   Point (0x4C), ColorDepth (0x62), BlockHeight (0x63), ColorMapping (0x64), CompressMode (0x65),
     *   DestinationSize (0x67), SourceHeight (0x6B), SourceWidth (0x6C), StartLine (0x6D),
     *   DataOrg (0x82), Measure (0x86), SourceType (0x88), UnitsPerMeasure (0x89), ErrorReport (0x8F)
     */
    class Pcl6XlSession(
        private val transport: UsbByteTransport,
        private val colorMode: Boolean,
        private val dpiX: Int,
        private val dpiY: Int,
        private val paperSize: PaperSize,
        private val copies: Int,
        jobName: String
    ) {
        companion object {
            // Operators (pxoper.h)
            const val OP_BEGIN_SESSION = 0x41
            const val OP_END_SESSION = 0x42
            const val OP_BEGIN_PAGE = 0x43
            const val OP_END_PAGE = 0x44
            const val OP_OPEN_DATA_SOURCE = 0x48
            const val OP_CLOSE_DATA_SOURCE = 0x49
            const val OP_SET_COLOR_SPACE = 0x6A
            const val OP_SET_CURSOR = 0x6B
            const val OP_BEGIN_IMAGE = 0xB0
            const val OP_READ_IMAGE = 0xB1
            const val OP_END_IMAGE = 0xB2

            // Attributes (pxattr.h)
            const val ATTR_COLOR_SPACE = 0x03
            const val ATTR_MEDIA_SIZE = 0x25
            const val ATTR_ORIENTATION = 0x28
            const val ATTR_PAGE_COPIES = 0x31
            const val ATTR_POINT = 0x4C
            const val ATTR_COLOR_DEPTH = 0x62
            const val ATTR_BLOCK_HEIGHT = 0x63
            const val ATTR_COLOR_MAPPING = 0x64
            const val ATTR_COMPRESS_MODE = 0x65
            const val ATTR_DESTINATION_SIZE = 0x67
            const val ATTR_SOURCE_HEIGHT = 0x6B
            const val ATTR_SOURCE_WIDTH = 0x6C
            const val ATTR_START_LINE = 0x6D
            const val ATTR_DATA_ORG = 0x82
            const val ATTR_MEASURE = 0x86
            const val ATTR_SOURCE_TYPE = 0x88
            const val ATTR_UNITS_PER_MEASURE = 0x89
            const val ATTR_ERROR_REPORT = 0x8F

            // Enumerations (pxenum.h)
            const val ENUM_COLOR_GRAY = 0x01
            const val ENUM_COLOR_RGB = 0x02
            const val ENUM_COLOR_MAPPING_DIRECT_PIXEL = 0x00
            const val ENUM_COLOR_DEPTH_8BIT = 0x02
            const val ENUM_COMPRESS_NONE = 0x00
            const val ENUM_MEASURE_INCH = 0x00
            const val ENUM_SOURCE_DEFAULT = 0x00
            const val ENUM_DATA_ORG_BINARY_LOW_BYTE_FIRST = 0x01
            const val ENUM_ERROR_BACK_CH_AND_ERR_PAGE = 0x03
        }

        init {
            val header = buildString {
                append(UEL)
                append("@PJL JOB NAME=\"").append(jobName.take(40)).append("\"\r\n")
                append("@PJL ENTER LANGUAGE=PCLXL\r\n")
                append(") HP-PCL XL;2;0;Universal USB Printer\n")
            }
            transport.write(header.toByteArray(StandardCharsets.US_ASCII))

            // BeginSession + OpenDataSource immediately after
            val sess = ByteArrayOutputStream()
            attrUByte(sess, ATTR_MEASURE, ENUM_MEASURE_INCH)
            attrUInt16XY(sess, ATTR_UNITS_PER_MEASURE, dpiX, dpiY)
            attrUByte(sess, ATTR_ERROR_REPORT, ENUM_ERROR_BACK_CH_AND_ERR_PAGE)
            sess.write(OP_BEGIN_SESSION)

            attrUByte(sess, ATTR_SOURCE_TYPE, ENUM_SOURCE_DEFAULT)
            attrUByte(sess, ATTR_DATA_ORG, ENUM_DATA_ORG_BINARY_LOW_BYTE_FIRST)
            sess.write(OP_OPEN_DATA_SOURCE)
            transport.write(sess.toByteArray())
        }

        fun beginPage(widthPx: Int, heightPx: Int) {
            val page = ByteArrayOutputStream()
            attrUByte(page, ATTR_ORIENTATION, 0x00) // ePortraitOrientation
            val mediaSize = when (paperSize) {
                PaperSize.LETTER -> 0
                PaperSize.LEGAL -> 1
                PaperSize.A4 -> 2
                PaperSize.A5 -> 16
            }
            attrUByte(page, ATTR_MEDIA_SIZE, mediaSize)
            page.write(OP_BEGIN_PAGE)

            // SetColorSpace
            attrUByte(page, ATTR_COLOR_SPACE, if (colorMode) ENUM_COLOR_RGB else ENUM_COLOR_GRAY)
            page.write(OP_SET_COLOR_SPACE)

            // SetCursor(0, 0)
            attrUInt16XY(page, ATTR_POINT, 0, 0)
            page.write(OP_SET_CURSOR)

            // BeginImage: ColorMapping(0x64)=0, ColorDepth(0x62)=2, SourceWidth(0x6C), SourceHeight(0x6B), DestinationSize(0x67)
            attrUByte(page, ATTR_COLOR_MAPPING, ENUM_COLOR_MAPPING_DIRECT_PIXEL)
            attrUByte(page, ATTR_COLOR_DEPTH, ENUM_COLOR_DEPTH_8BIT)
            attrUInt16(page, ATTR_SOURCE_WIDTH, widthPx)
            attrUInt16(page, ATTR_SOURCE_HEIGHT, heightPx)
            attrUInt16XY(page, ATTR_DESTINATION_SIZE, widthPx, heightPx)
            page.write(OP_BEGIN_IMAGE)
            transport.write(page.toByteArray())
        }

        fun writeBand(
            argb: IntArray,
            widthPx: Int,
            bandHeightPx: Int,
            startY: Int,
            lut: IntArray
        ) {
            val pixelBytes = if (colorMode) {
                HalftoneDitherEngine.extractRgb24Band(argb, widthPx, bandHeightPx, lut)
            } else {
                HalftoneDitherEngine.extractGray8Band(argb, widthPx, bandHeightPx, lut)
            }
            val cmd = ByteArrayOutputStream(pixelBytes.size + 32)
            attrUInt16(cmd, ATTR_START_LINE, startY)
            attrUInt16(cmd, ATTR_BLOCK_HEIGHT, bandHeightPx)
            attrUByte(cmd, ATTR_COMPRESS_MODE, ENUM_COMPRESS_NONE)
            cmd.write(OP_READ_IMAGE)
            // Embedded data length tag: 0xFA (dataLength) + uint32 LE
            cmd.write(0xFA)
            writeIntLE(cmd, pixelBytes.size)
            cmd.write(pixelBytes)
            transport.write(cmd.toByteArray())
        }

        fun endPage() {
            val end = ByteArrayOutputStream()
            end.write(OP_END_IMAGE)
            attrUInt16(end, ATTR_PAGE_COPIES, copies.coerceIn(1, 99))
            end.write(OP_END_PAGE)
            transport.write(end.toByteArray())
        }

        fun finish() {
            val out = ByteArrayOutputStream()
            out.write(OP_CLOSE_DATA_SOURCE)
            out.write(OP_END_SESSION)
            out.write(UEL.toByteArray(StandardCharsets.US_ASCII))
            transport.write(out.toByteArray())
        }

        private fun attrUByte(out: ByteArrayOutputStream, attrId: Int, value: Int) {
            out.write(0xC0) // ubyte
            out.write(value and 0xFF)
            out.write(0xF8) // attr_ubyte
            out.write(attrId and 0xFF)
        }

        private fun attrUInt16(out: ByteArrayOutputStream, attrId: Int, value: Int) {
            out.write(0xC1) // uint16
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write(0xF8)
            out.write(attrId and 0xFF)
        }

        private fun attrUInt16XY(out: ByteArrayOutputStream, attrId: Int, x: Int, y: Int) {
            out.write(0xD1) // uint16_xy
            out.write(x and 0xFF)
            out.write((x ushr 8) and 0xFF)
            out.write(y and 0xFF)
            out.write((y ushr 8) and 0xFF)
            out.write(0xF8)
            out.write(attrId and 0xFF)
        }

        private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
    }
}
