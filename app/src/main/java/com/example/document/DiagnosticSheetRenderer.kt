package com.example.document

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

data class DiagnosticMetadata(
    val printerName: String,
    val vidPid: String,
    val activeInterface: String,
    val resolvedProtocol: String,
    val colorSupported: Boolean,
    val ieee1284Cmd: String,
    val paperLabel: String,
    val dpiLabel: String
)

object DiagnosticSheetRenderer {

    fun drawIntoCanvas(
        canvas: Canvas,
        pageWidthPx: Float,
        pageHeightPx: Float,
        metadata: DiagnosticMetadata
    ) {
        canvas.drawColor(Color.WHITE)

        val scale = (pageWidthPx / 1200f).coerceAtLeast(0.4f)
        val margin = 56f * scale

        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * scale
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f * scale
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 20f * scale
            typeface = Typeface.MONOSPACE
        }

        // Corner registration marks
        val markLen = 28f * scale
        val corners = listOf(
            margin to margin,
            pageWidthPx - margin to margin,
            margin to pageHeightPx - margin,
            pageWidthPx - margin to pageHeightPx - margin
        )
        for ((cx, cy) in corners) {
            val dx = if (cx < pageWidthPx / 2f) markLen else -markLen
            val dy = if (cy < pageHeightPx / 2f) markLen else -markLen
            canvas.drawLine(cx, cy, cx + dx, cy, inkPaint)
            canvas.drawLine(cx, cy, cx, cy + dy, inkPaint)
        }

        var y = margin + 48f * scale
        canvas.drawText("UNIVERSAL USB PRINTER — DIAGNOSTIC TEST SHEET", margin + 12f * scale, y, textPaint)
        y += 38f * scale

        val metaLines = listOf(
            "Printer: ${metadata.printerName}",
            "USB VID:PID: ${metadata.vidPid}   Interface: ${metadata.activeInterface}",
            "Protocol: ${metadata.resolvedProtocol}   Color Mode: ${if (metadata.colorSupported) "Color" else "Monochrome"}",
            "IEEE 1284 CMD: ${metadata.ieee1284Cmd.ifBlank { "(none / fallback)" }}",
            "Page: ${metadata.paperLabel} @ ${metadata.dpiLabel}"
        )
        for (line in metaLines) {
            canvas.drawText(line, margin + 12f * scale, y, monoPaint)
            y += 28f * scale
        }

        y += 18f * scale
        canvas.drawLine(margin, y, pageWidthPx - margin, y, inkPaint)
        y += 36f * scale

        // Step wedge (Color RGBCMYK or Monochrome Grayscale)
        canvas.drawText(
            if (metadata.colorSupported) "COLOR & GRAYSCALE STEP WEDGE" else "MONOCHROME HALFTONE STEP WEDGE",
            margin,
            y,
            textPaint.apply { textSize = 20f * scale }
        )
        y += 18f * scale

        val barWidth = (pageWidthPx - 2f * margin) / 10f
        val barHeight = 64f * scale
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (step in 0 until 10) {
            val gray = (255 - (step * 28)).coerceIn(0, 255)
            fillPaint.color = Color.rgb(gray, gray, gray)
            val rect = RectF(
                margin + step * barWidth,
                y,
                margin + (step + 1) * barWidth - 4f * scale,
                y + barHeight
            )
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, inkPaint.apply { strokeWidth = 1.2f * scale })
        }
        y += barHeight + 24f * scale

        if (metadata.colorSupported) {
            val swatches = listOf(
                Color.rgb(0, 174, 239),   // Cyan
                Color.rgb(236, 0, 140),   // Magenta
                Color.rgb(255, 242, 0),   // Yellow
                Color.rgb(29, 95, 214),   // Cobalt
                Color.rgb(21, 128, 61),   // Emerald
                Color.rgb(200, 30, 30)    // Crimson
            )
            val swatchW = (pageWidthPx - 2f * margin) / swatches.size
            swatches.forEachIndexed { idx, color ->
                fillPaint.color = color
                val rect = RectF(
                    margin + idx * swatchW,
                    y,
                    margin + (idx + 1) * swatchW - 6f * scale,
                    y + barHeight
                )
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect, inkPaint)
            }
            y += barHeight + 30f * scale
        }

        // Line weight targets
        canvas.drawText("LINE WEIGHT & ALIGNMENT TARGETS", margin, y, textPaint)
        y += 22f * scale
        val weights = listOf(1f, 2f, 4f, 8f)
        for (w in weights) {
            val lp = Paint().apply {
                color = Color.BLACK
                strokeWidth = w * scale
            }
            canvas.drawText("${w.toInt()}px", margin, y + 6f * scale, monoPaint)
            canvas.drawLine(margin + 72f * scale, y, pageWidthPx - margin, y, lp)
            y += 26f * scale
        }

        // Center crosshair target
        val centerX = pageWidthPx / 2f
        val targetY = (y + 130f * scale).coerceAtMost(pageHeightPx - margin - 90f * scale)
        val radius = 70f * scale
        inkPaint.strokeWidth = 2f * scale
        canvas.drawCircle(centerX, targetY, radius, inkPaint)
        canvas.drawCircle(centerX, targetY, radius * 0.5f, inkPaint)
        canvas.drawLine(centerX - radius * 1.2f, targetY, centerX + radius * 1.2f, targetY, inkPaint)
        canvas.drawLine(centerX, targetY - radius * 1.2f, centerX, targetY + radius * 1.2f, inkPaint)
    }
}
