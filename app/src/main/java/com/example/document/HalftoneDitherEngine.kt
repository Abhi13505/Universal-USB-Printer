package com.example.document

import com.example.model.DitherMode
import kotlin.math.roundToInt

/**
 * Pure-Kotlin brightness/contrast adjuster and 1-bit monochrome dithering engine.
 * Supports continuous error carry-over across 128-line horizontal bands so Floyd-Steinberg
 * produces zero seam artifacts at band boundaries.
 */
object HalftoneDitherEngine {

    private val BAYER_4X4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )

    class BandDitherState(val widthPx: Int) {
        var currentErrorRow = FloatArray(widthPx + 2)
        var nextErrorRow = FloatArray(widthPx + 2)

        fun advanceRow() {
            val tmp = currentErrorRow
            currentErrorRow = nextErrorRow
            nextErrorRow = tmp
            nextErrorRow.fill(0f)
        }
    }

    fun buildBrightnessContrastLut(brightness: Float, contrast: Float): IntArray {
        val lut = IntArray(256)
        val bOffset = brightness * 255f
        for (i in 0..255) {
            val adjusted = ((i - 128f) * contrast + 128f + bOffset).roundToInt().coerceIn(0, 255)
            lut[i] = adjusted
        }
        return lut
    }

    fun adjustArgbPixelsForPreview(
        argb: IntArray,
        width: Int,
        height: Int,
        brightness: Float,
        contrast: Float,
        monochrome: Boolean,
        ditherMode: DitherMode
    ) {
        val lut = buildBrightnessContrastLut(brightness, contrast)
        if (!monochrome) {
            for (i in argb.indices) {
                val c = argb[i]
                val a = (c ushr 24) and 0xFF
                val r = lut[(c ushr 16) and 0xFF]
                val g = lut[(c ushr 8) and 0xFF]
                val b = lut[c and 0xFF]
                argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return
        }

        val state = BandDitherState(width)
        val packedBand = ditherBand1bpp(
            argb = argb,
            width = width,
            height = height,
            startY = 0,
            ditherMode = ditherMode,
            lut = lut,
            state = state
        )
        val bytesPerRow = (width + 7) / 8
        for (y in 0 until height) {
            val rowOffset = y * bytesPerRow
            val pxRowOffset = y * width
            for (x in 0 until width) {
                val byteVal = packedBand[rowOffset + (x ushr 3)].toInt() and 0xFF
                val bit = (byteVal ushr (7 - (x and 7))) and 1
                // 1 = black ink, 0 = white paper
                argb[pxRowOffset + x] = if (bit == 1) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
            }
        }
    }

    /**
     * Dithers a horizontal band of [argb] pixels ([width] x [height] starting at global [startY])
     * into packed 1bpp MSB-first bytes where 1 = black ink and 0 = white paper.
     */
    fun ditherBand1bpp(
        argb: IntArray,
        width: Int,
        height: Int,
        startY: Int,
        ditherMode: DitherMode,
        lut: IntArray,
        state: BandDitherState
    ): ByteArray {
        val bytesPerRow = (width + 7) / 8
        val out = ByteArray(bytesPerRow * height)

        for (y in 0 until height) {
            val globalY = startY + y
            val pxRowStart = y * width
            val outRowStart = y * bytesPerRow

            when (ditherMode) {
                DitherMode.HALFTONE_FLOYD_STEINBERG -> {
                    for (x in 0 until width) {
                        val lum = luminance(argb[pxRowStart + x], lut)
                        val oldVal = (lum + state.currentErrorRow[x + 1]).coerceIn(0f, 255f)
                        val isBlack = oldVal < 128f
                        val newVal = if (isBlack) 0f else 255f
                        val err = oldVal - newVal

                        state.currentErrorRow[x + 2] += err * (7f / 16f)
                        state.nextErrorRow[x] += err * (3f / 16f)
                        state.nextErrorRow[x + 1] += err * (5f / 16f)
                        state.nextErrorRow[x + 2] += err * (1f / 16f)

                        if (isBlack) {
                            val idx = outRowStart + (x ushr 3)
                            out[idx] = (out[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                    state.advanceRow()
                }

                DitherMode.CRISP_TEXT -> {
                    for (x in 0 until width) {
                        val lum = luminance(argb[pxRowStart + x], lut)
                        if (lum < 148) {
                            val idx = outRowStart + (x ushr 3)
                            out[idx] = (out[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                }

                DitherMode.SOFT_TONE_BAYER_4X4 -> {
                    val bayerRow = (globalY and 3) shl 2
                    for (x in 0 until width) {
                        val lum = luminance(argb[pxRowStart + x], lut)
                        val threshold = ((BAYER_4X4[bayerRow + (x and 3)] + 0.5f) / 16f) * 255f
                        if (lum < threshold) {
                            val idx = outRowStart + (x ushr 3)
                            out[idx] = (out[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                }
            }
        }
        return out
    }

    fun extractRgb24Band(argb: IntArray, width: Int, height: Int, lut: IntArray): ByteArray {
        val out = ByteArray(width * height * 3)
        var dst = 0
        val count = width * height
        for (i in 0 until count) {
            val c = argb[i]
            out[dst++] = lut[(c ushr 16) and 0xFF].toByte()
            out[dst++] = lut[(c ushr 8) and 0xFF].toByte()
            out[dst++] = lut[c and 0xFF].toByte()
        }
        return out
    }

    fun extractGray8Band(argb: IntArray, width: Int, height: Int, lut: IntArray): ByteArray {
        val count = width * height
        val out = ByteArray(count)
        for (i in 0 until count) {
            out[i] = luminance(argb[i], lut).toByte()
        }
        return out
    }

    private fun luminance(color: Int, lut: IntArray): Int {
        val r = lut[(color ushr 16) and 0xFF]
        val g = lut[(color ushr 8) and 0xFF]
        val b = lut[color and 0xFF]
        return (77 * r + 150 * g + 29 * b) ushr 8
    }
}
