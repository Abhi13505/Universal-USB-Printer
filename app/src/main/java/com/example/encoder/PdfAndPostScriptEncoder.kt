package com.example.encoder

import com.example.document.HalftoneDitherEngine
import com.example.model.DitherMode
import com.example.usb.UsbByteTransport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

object PdfAndPostScriptEncoder {

    private const val UEL = "\u001B%-12345X"

    fun streamPdfDirect(
        transport: UsbByteTransport,
        jobName: String,
        copies: Int,
        pdfStream: InputStream
    ) {
        val header = buildString {
            append(UEL)
            append("@PJL JOB NAME=\"").append(jobName.take(40)).append("\"\r\n")
            append("@PJL SET COPIES=").append(copies.coerceIn(1, 99)).append("\r\n")
            append("@PJL ENTER LANGUAGE = PDF\r\n")
        }
        transport.write(header.toByteArray(StandardCharsets.US_ASCII))

        val buf = ByteArray(16 * 1024)
        while (true) {
            val read = pdfStream.read(buf)
            if (read <= 0) break
            transport.write(buf, 0, read)
        }

        val footer = "${UEL}@PJL EOJ\r\n${UEL}".toByteArray(StandardCharsets.US_ASCII)
        transport.write(footer)
    }

    class PostScriptSession(
        private val transport: UsbByteTransport,
        private val colorMode: Boolean,
        private val copies: Int,
        jobName: String
    ) {
        init {
            val header = buildString {
                append(UEL)
                append("@PJL JOB NAME=\"").append(jobName.take(40)).append("\"\r\n")
                append("@PJL ENTER LANGUAGE = POSTSCRIPT\r\n")
                append("%!PS-Adobe-3.0\n")
                append("%%Creator: Universal USB Printer\n")
                append("%%LanguageLevel: 2\n")
                append("%%EndComments\n")
            }
            transport.write(header.toByteArray(StandardCharsets.US_ASCII))
        }

        fun beginPage(
            pageIndex: Int,
            paperWidthMm: Float,
            paperHeightMm: Float,
            widthPx: Int,
            heightPx: Int
        ) {
            val widthPt = (paperWidthMm / 25.4f) * 72f
            val heightPt = (paperHeightMm / 25.4f) * 72f
            val ps = buildString {
                append("%%Page: ").append(pageIndex + 1).append(" ").append(pageIndex + 1).append("\n")
                append("<< /PageSize [").append("%.1f %.1f".format(widthPt, heightPt))
                append("] /NumCopies ").append(copies.coerceIn(1, 99)).append(" >> setpagedevice\n")
                append("gsave\n")
                append("0 ").append("%.1f".format(heightPt)).append(" translate\n")
                append("%.1f %.1f".format(widthPt, heightPt)).append(" scale\n")
                append("/picstr ").append(if (colorMode) widthPx * 3 else (widthPx + 7) / 8).append(" string def\n")
                append(widthPx).append(" ").append(heightPx).append(" ")
                append(if (colorMode) 8 else 1).append(" ")
                append("[").append(widthPx).append(" 0 0 -").append(heightPx).append(" 0 0]\n")
                append("{ currentfile picstr readhexstring pop }\n")
                if (colorMode) {
                    append("false 3 colorimage\n")
                } else {
                    append("image\n")
                }
            }
            transport.write(ps.toByteArray(StandardCharsets.US_ASCII))
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
            val rawBytes = if (colorMode) {
                HalftoneDitherEngine.extractRgb24Band(argb, widthPx, bandHeightPx, lut)
            } else {
                // PostScript 1bpp image: 1 = white, 0 = black (inverted relative to ink mask)
                val inkMask = HalftoneDitherEngine.ditherBand1bpp(
                    argb = argb,
                    width = widthPx,
                    height = bandHeightPx,
                    startY = startY,
                    ditherMode = ditherMode,
                    lut = lut,
                    state = ditherState
                )
                ByteArray(inkMask.size) { idx -> (inkMask[idx].toInt() xor 0xFF).toByte() }
            }

            val hex = toHexLines(rawBytes)
            transport.write(hex)
        }

        fun endPage() {
            transport.write("\ngrestore\nshowpage\n".toByteArray(StandardCharsets.US_ASCII))
        }

        fun finish() {
            val footer = "%%Trailer\n%%EOF\n${UEL}@PJL EOJ\r\n${UEL}".toByteArray(StandardCharsets.US_ASCII)
            transport.write(footer)
        }

        private fun toHexLines(bytes: ByteArray): ByteArray {
            val digits = "0123456789ABCDEF".toCharArray()
            val out = ByteArrayOutputStream(bytes.size * 2 + bytes.size / 32 + 4)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out.write(digits[v ushr 4].code)
                out.write(digits[v and 0x0F].code)
                if ((i and 31) == 31) out.write('\n'.code)
            }
            return out.toByteArray()
        }
    }
}
