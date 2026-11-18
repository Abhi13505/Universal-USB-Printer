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

    class Pcl5Session(
        private val transport: UsbByteTransport,
        private val colorMode: Boolean,
        private val dpiX: Int,
        private val dpiY: Int,
        private val paperSize: PaperSize,
        private val copies: Int,
        jobName: String
    ) {
        private val resolution = dpiX.coerceAtLeast(300)

        init {
            val pjl = buildString {
                append(UEL)
                append("@PJL JOB NAME=\"").append(jobName.take(40)).append("\"\r\n")
                append("@PJL SET RESOLUTION=").append(resolution).append("\r\n")
                append("@PJL SET COPIES=").append(copies.coerceIn(1, 99)).append("\r\n")
                append("@PJL ENTER LANGUAGE=PCL\r\n")
            }
            transport.write(pjl.toByteArray(StandardCharsets.US_ASCII))
        }

        fun beginPage(widthPx: Int, heightPx: Int) {
            val sb = StringBuilder()
            sb.append(ESC).append("E") // Printer reset
            sb.append(ESC).append("&u").append(resolution).append("D") // Unit of measure
            sb.append(ESC).append("&l").append(paperSize.pclCode).append("A") // Paper size
            sb.append(ESC).append("&l0O") // Portrait raster coords (already rotated in band compositor)
            sb.append(ESC).append("&l").append(copies.coerceIn(1, 99)).append("X")
            sb.append(ESC).append("*t").append(resolution).append("R") // Raster resolution
            sb.append(ESC).append("*r").append(widthPx).append("S") // Source width
            sb.append(ESC).append("*r").append(heightPx).append("T") // Source height
            transport.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))

            if (colorMode) {
                // PCL 5c Configure Image Data (ESC * v 6 W): 24-bit direct sRGB (02 03 08 08 08 00)
                val cidCmd = byteArrayOf(
                    0x1B, '*'.code.toByte(), 'v'.code.toByte(), '6'.code.toByte(), 'W'.code.toByte(),
                    0x02, 0x03, 0x08, 0x08, 0x08, 0x00
                )
                transport.write(cidCmd)
            }

            val startRaster = buildString {
                sb.clear()
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
     * PCL 6 (PCL-XL 2.0) binary stream encoder using uncompressed/RLE 128-line raster image strips.
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
        init {
            val header = buildString {
                append(UEL)
                append("@PJL JOB NAME=\"").append(jobName.take(40)).append("\"\r\n")
                append("@PJL ENTER LANGUAGE=PCLXL\r\n")
                append(") HP-PCL XL;2;0;Universal USB Printer\n")
            }
            transport.write(header.toByteArray(StandardCharsets.US_ASCII))

            // BeginSession: Measure(eInch=0), UnitsPerMeasure(dpiX, dpiY), ErrorReport(eBackChannelAndPage=3)
            val sess = ByteArrayOutputStream()
            attrUByte(sess, 0x86, 0x00) // Measure = eInch
            attrUInt16XY(sess, 0x89, dpiX, dpiY) // UnitsPerMeasure
            attrUByte(sess, 0x8F, 0x03) // ErrorReport
            sess.write(0x41) // BeginSession
            transport.write(sess.toByteArray())
        }

        fun beginPage(widthPx: Int, heightPx: Int) {
            val page = ByteArrayOutputStream()
            attrUByte(page, 0x28, 0x00) // Orientation = Portrait
            val mediaSize = when (paperSize) {
                PaperSize.LETTER -> 0
                PaperSize.LEGAL -> 1
                PaperSize.A4 -> 2
                PaperSize.A5 -> 16
            }
            attrUByte(page, 0x25, mediaSize) // MediaSize
            page.write(0x43) // BeginPage

            // SetColorSpace
            attrUByte(page, 0x03, if (colorMode) 2 else 1) // 2=eRGB, 1=eGray
            page.write(0x6A) // SetColorSpace

            // SetCursor(0,0)
            attrUInt16XY(page, 0x4C, 0, 0)
            page.write(0x6B) // SetCursor

            // BeginImage
            attrUByte(page, 0x62, 0x00) // ColorMapping = eDirectPixel
            attrUByte(page, 0x61, 0x02) // ColorDepth = e8Bit
            attrUInt16(page, 0x6C, widthPx) // SourceWidth
            attrUInt16(page, 0x6B, heightPx) // SourceHeight
            attrUInt16XY(page, 0x67, widthPx, heightPx) // DestinationSize
            page.write(0xB0) // BeginImage
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
            attrUInt16(cmd, 0x6D, startY) // StartLine
            attrUInt16(cmd, 0x63, bandHeightPx) // BlockHeight
            attrUByte(cmd, 0x64, 0x00) // CompressMode = eNoCompression (safe universal XL)
            cmd.write(0xB1) // ReadImage
            // Embedded data length tag: 0xFA (dataLength) + uint32 LE
            cmd.write(0xFA)
            writeIntLE(cmd, pixelBytes.size)
            cmd.write(pixelBytes)
            transport.write(cmd.toByteArray())
        }

        fun endPage() {
            val end = ByteArrayOutputStream()
            end.write(0xB2) // EndImage
            attrUInt16(end, 0x31, copies.coerceIn(1, 99)) // PageCopies
            end.write(0x44) // EndPage
            transport.write(end.toByteArray())
        }

        fun finish() {
            val out = ByteArrayOutputStream()
            out.write(0x42) // EndSession
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
