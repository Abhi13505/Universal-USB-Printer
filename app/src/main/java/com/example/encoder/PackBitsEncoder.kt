package com.example.encoder

import java.io.ByteArrayOutputStream

object PackBitsEncoder {

    /**
     * Standard TIFF PackBits compression (used by PCL 5 ESC*b2M).
     * Control byte n (signed -128..127):
     *   0..127   -> copy next (n + 1) literal bytes
     *   -1..-127 -> repeat next byte (1 - n) times (i.e. 2..128 times)
     */
    fun compressTiffPackBits(input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        val n = input.size
        while (i < n) {
            // Check for run of identical bytes >= 2
            var runLen = 1
            while (i + runLen < n && runLen < 128 && input[i + runLen] == input[i]) {
                runLen++
            }
            if (runLen >= 2) {
                out.write((257 - runLen) and 0xFF) // signed -(runLen - 1)
                out.write(input[i].toInt() and 0xFF)
                i += runLen
            } else {
                // Literal run until a 3-byte repeat starts (or 2-byte repeat at end)
                val litStart = i
                var litLen = 0
                while (i < n && litLen < 128) {
                    val hasRun = (i + 2 < n && input[i] == input[i + 1] && input[i] == input[i + 2]) ||
                        (i + 1 < n && i + 2 == n && input[i] == input[i + 1])
                    if (hasRun && litLen > 0) break
                    i++
                    litLen++
                }
                out.write(litLen - 1)
                out.write(input, litStart, litLen)
            }
        }
        return out.toByteArray()
    }

    /**
     * PWG 5102.4 PackBits pixel-group scanline encoder (without the leading line-repeat byte).
     * Control byte c (0..255):
     *   0..127   -> repeat next pixel (bytesPerPixel bytes) (c + 1) times
     *   128..255 -> copy next (257 - c) literal pixels (2..129)
     */
    fun compressPwgPixels(scanline: ByteArray, bytesPerPixel: Int): ByteArray {
        val totalPixels = scanline.size / bytesPerPixel
        if (totalPixels <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream(scanline.size + 16)
        var px = 0

        while (px < totalPixels) {
            var run = 1
            while (px + run < totalPixels && run < 128 && pixelsEqual(scanline, px, px + run, bytesPerPixel)) {
                run++
            }
            if (run >= 2 || px + 1 == totalPixels) {
                out.write(run - 1)
                out.write(scanline, px * bytesPerPixel, bytesPerPixel)
                px += run
            } else {
                val litStart = px
                var litCount = 0
                while (px < totalPixels && litCount < 128) {
                    if (px + 1 < totalPixels && pixelsEqual(scanline, px, px + 1, bytesPerPixel)) {
                        if (litCount >= 1) break
                    }
                    px++
                    litCount++
                }
                if (litCount == 1) {
                    out.write(0)
                    out.write(scanline, litStart * bytesPerPixel, bytesPerPixel)
                } else {
                    out.write((257 - litCount) and 0xFF)
                    out.write(scanline, litStart * bytesPerPixel, litCount * bytesPerPixel)
                }
            }
        }
        return out.toByteArray()
    }

    private fun pixelsEqual(buf: ByteArray, p1: Int, p2: Int, bpp: Int): Boolean {
        val o1 = p1 * bpp
        val o2 = p2 * bpp
        for (k in 0 until bpp) {
            if (buf[o1 + k] != buf[o2 + k]) return false
        }
        return true
    }
}
