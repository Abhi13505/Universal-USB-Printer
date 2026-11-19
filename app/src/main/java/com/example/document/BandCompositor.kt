package com.example.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import com.example.model.CutGuideStyle
import com.example.model.NormalizedCropRect
import com.example.model.PageOrientation
import com.example.model.PaperSize
import com.example.model.PrintQuality
import com.example.model.PrintTuningOptions
import com.example.model.ScaleMode

object BandCompositor {

    const val BAND_HEIGHT = 128

    /**
     * Aligns page width to 32-pixel (4-byte) boundary so all encoders (especially Samsung QPDL)
     * share an exact stride-aligned coordinate space.
     */
    fun alignedPageDimensionsPx(
        paperWidthMm: Float,
        paperHeightMm: Float,
        dpiX: Int,
        dpiY: Int
    ): Pair<Int, Int> {
        val rawW = ((paperWidthMm / 25.4f) * dpiX).toInt().coerceAtLeast(32)
        val alignedW = ((rawW + 31) / 32) * 32
        val rawH = ((paperHeightMm / 25.4f) * dpiY).toInt().coerceAtLeast(128)
        return alignedW to rawH
    }

    fun resolvePdfPaperDimensionsMm(
        paperSize: PaperSize,
        orientation: PageOrientation,
        pdfPageWidthPt: Int,
        pdfPageHeightPt: Int
    ): Pair<Float, Float> {
        val baseW = paperSize.widthMm
        val baseH = paperSize.heightMm
        val landscape = when (orientation) {
            PageOrientation.PORTRAIT -> false
            PageOrientation.LANDSCAPE -> true
            PageOrientation.AUTO -> pdfPageWidthPt > pdfPageHeightPt
        }
        return if (landscape) maxOf(baseW, baseH) to minOf(baseW, baseH)
        else minOf(baseW, baseH) to maxOf(baseW, baseH)
    }

    /**
     * Computes symmetric safe-margin inset in device pixels for printers that clip edges
     * (PCL 5, PCL-XL, PWG Raster). Returns (0f, 0f) when safeMarginMm <= 0f.
     */
    fun computeSafeMarginInsetPx(
        paperWidthMm: Float,
        paperHeightMm: Float,
        pageWidthPx: Int,
        pageHeightPx: Int,
        safeMarginMm: Float
    ): Pair<Float, Float> {
        if (safeMarginMm <= 0f) return 0f to 0f
        val pxPerMmX = pageWidthPx.toFloat() / paperWidthMm.coerceAtLeast(1f)
        val pxPerMmY = pageHeightPx.toFloat() / paperHeightMm.coerceAtLeast(1f)
        val insetX = (safeMarginMm * pxPerMmX).coerceIn(0f, pageWidthPx * 0.25f)
        val insetY = (safeMarginMm * pxPerMmY).coerceIn(0f, pageHeightPx * 0.25f)
        return insetX to insetY
    }

    /**
     * Renders a single 128-line (or remaining tail) ARGB band for a PDF page without ever
     * allocating a full-page 600 DPI bitmap.
     */
    fun renderPdfBandArgb(
        pdfPage: PdfRenderer.Page,
        pageWidthPx: Int,
        pageHeightPx: Int,
        bandTopY: Int,
        bandHeightPx: Int,
        scaleMode: ScaleMode,
        tuning: PrintTuningOptions,
        paperWidthMm: Float = 210f,
        paperHeightMm: Float = 297f,
        safeMarginMm: Float = 0f
    ): IntArray {
        val bandBitmap = Bitmap.createBitmap(pageWidthPx, bandHeightPx, Bitmap.Config.ARGB_8888)
        bandBitmap.eraseColor(Color.WHITE)

        val (insetX, insetY) = computeSafeMarginInsetPx(
            paperWidthMm = paperWidthMm,
            paperHeightMm = paperHeightMm,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            safeMarginMm = safeMarginMm
        )
        val availW = (pageWidthPx - 2f * insetX).coerceAtLeast(1f)
        val availH = (pageHeightPx - 2f * insetY).coerceAtLeast(1f)

        val clipLeft = insetX.toInt().coerceIn(0, pageWidthPx)
        val clipRight = (pageWidthPx - insetX).toInt().coerceIn(0, pageWidthPx)
        val clipTop = (insetY - bandTopY).toInt().coerceIn(0, bandHeightPx)
        val clipBottom = (pageHeightPx - insetY - bandTopY).toInt().coerceIn(0, bandHeightPx)
        val destClip: Rect? = if (safeMarginMm > 0f) {
            if (clipRight <= clipLeft || clipBottom <= clipTop) {
                val whitePixels = IntArray(pageWidthPx * bandHeightPx)
                bandBitmap.getPixels(whitePixels, 0, pageWidthPx, 0, 0, pageWidthPx, bandHeightPx)
                bandBitmap.recycle()
                return whitePixels
            }
            Rect(clipLeft, clipTop, clipRight, clipBottom)
        } else {
            null
        }

        val srcW = pdfPage.width.toFloat().coerceAtLeast(1f)
        val srcH = pdfPage.height.toFloat().coerceAtLeast(1f)
        val crop = tuning.cropRect.normalized()

        val rotatedDims = if (tuning.rotationDegrees % 180 != 0) srcH to srcW else srcW to srcH
        val effSrcW = rotatedDims.first * (crop.right - crop.left)
        val effSrcH = rotatedDims.second * (crop.bottom - crop.top)

        val scaleX: Float
        val scaleY: Float
        when (scaleMode) {
            ScaleMode.SCALE_TO_FIT -> {
                val s = minOf(availW / effSrcW, availH / effSrcH)
                scaleX = s
                scaleY = s
            }
            ScaleMode.CROP_TO_FILL -> {
                val s = maxOf(availW / effSrcW, availH / effSrcH)
                scaleX = s
                scaleY = s
            }
            ScaleMode.STRETCH -> {
                scaleX = availW / effSrcW
                scaleY = availH / effSrcH
            }
        }

        val matrix = Matrix()
        matrix.postTranslate(-srcW / 2f, -srcH / 2f)
        matrix.postRotate(tuning.rotationDegrees.toFloat())
        matrix.postTranslate(rotatedDims.first / 2f, rotatedDims.second / 2f)

        // Apply crop translation so cropped region starts at (0,0)
        matrix.postTranslate(
            -crop.left * rotatedDims.first,
            -crop.top * rotatedDims.second
        )
        matrix.postScale(scaleX, scaleY)

        val drawnW = effSrcW * scaleX
        val drawnH = effSrcH * scaleY
        val offsetX = insetX + (availW - drawnW) / 2f
        val offsetY = insetY + (availH - drawnH) / 2f - bandTopY.toFloat()
        matrix.postTranslate(offsetX, offsetY)

        pdfPage.render(bandBitmap, destClip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

        val pixels = IntArray(pageWidthPx * bandHeightPx)
        bandBitmap.getPixels(pixels, 0, pageWidthPx, 0, 0, pageWidthPx, bandHeightPx)
        bandBitmap.recycle()
        return pixels
    }

    /**
     * Renders a single 128-line ARGB band for a Photo Layout sheet page using the exact
     * millimeter slots from [PhotoLayoutGeometry], scaled into printable area when safeMarginMm > 0.
     */
    fun renderPhotoSheetBandArgb(
        sheetPlan: PhotoSheetPlan,
        pageAssignment: PhotoPageAssignment,
        photos: List<Bitmap>,
        pageWidthPx: Int,
        pageHeightPx: Int,
        dpiX: Int,
        dpiY: Int,
        bandTopY: Int,
        bandHeightPx: Int,
        cutGuideStyle: CutGuideStyle,
        tuning: PrintTuningOptions,
        autoRotateToCell: Boolean,
        safeMarginMm: Float = 0f
    ): IntArray {
        val bandBitmap = Bitmap.createBitmap(pageWidthPx, bandHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bandBitmap)
        canvas.drawColor(Color.WHITE)

        val (insetX, insetY) = computeSafeMarginInsetPx(
            paperWidthMm = sheetPlan.paperWidthMm,
            paperHeightMm = sheetPlan.paperHeightMm,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            safeMarginMm = safeMarginMm
        )
        val contentScaleX = (pageWidthPx - 2f * insetX) / pageWidthPx.toFloat().coerceAtLeast(1f)
        val contentScaleY = (pageHeightPx - 2f * insetY) / pageHeightPx.toFloat().coerceAtLeast(1f)

        val pxPerMmX = (dpiX / 25.4f) * contentScaleX
        val pxPerMmY = (dpiY / 25.4f) * contentScaleY
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 70, 70, 70)
            style = Paint.Style.STROKE
            strokeWidth = (0.3f * pxPerMmX).coerceAtLeast(1f)
        }

        val bandBottomY = bandTopY + bandHeightPx

        for ((cell, photoIdx) in pageAssignment.slots) {
            val cellLeft = insetX + cell.leftMm * pxPerMmX
            val cellTop = insetY + cell.topMm * pxPerMmY
            val cellRight = insetX + (cell.leftMm + cell.widthMm) * pxPerMmX
            val cellBottom = insetY + (cell.topMm + cell.heightMm) * pxPerMmY

            if (cellBottom < bandTopY - 12f || cellTop > bandBottomY + 12f) continue

            val photo = photos.getOrNull(photoIdx)
            if (photo != null && !photo.isRecycled) {
                val crop = tuning.cropRect.normalized()
                val srcRect = Rect(
                    (crop.left * photo.width).toInt().coerceIn(0, photo.width - 1),
                    (crop.top * photo.height).toInt().coerceIn(0, photo.height - 1),
                    (crop.right * photo.width).toInt().coerceIn(1, photo.width),
                    (crop.bottom * photo.height).toInt().coerceIn(1, photo.height)
                )

                val extraAutoRot = if (PhotoLayoutGeometry.shouldAutoRotatePhoto(
                        photoW = srcRect.width(),
                        photoH = srcRect.height(),
                        cellW = cell.widthMm,
                        cellH = cell.heightMm,
                        autoRotateEnabled = autoRotateToCell
                    )
                ) 90 else 0

                val totalRot = (tuning.rotationDegrees + extraAutoRot) % 360
                val destRect = RectF(
                    cellLeft,
                    cellTop - bandTopY,
                    cellRight,
                    cellBottom - bandTopY
                )

                canvas.save()
                canvas.clipRect(destRect)

                val effW = if (totalRot % 180 != 0) srcRect.height().toFloat() else srcRect.width().toFloat()
                val effH = if (totalRot % 180 != 0) srcRect.width().toFloat() else srcRect.height().toFloat()
                val coverScale = maxOf(destRect.width() / effW, destRect.height() / effH)

                val cropped = Bitmap.createBitmap(
                    photo,
                    srcRect.left,
                    srcRect.top,
                    srcRect.width().coerceAtLeast(1),
                    srcRect.height().coerceAtLeast(1)
                )
                val cropMatrix = Matrix()
                cropMatrix.postTranslate(-cropped.width / 2f, -cropped.height / 2f)
                cropMatrix.postRotate(totalRot.toFloat())
                cropMatrix.postScale(coverScale, coverScale)
                cropMatrix.postTranslate(destRect.centerX(), destRect.centerY())
                canvas.drawBitmap(cropped, cropMatrix, paint)
                if (cropped !== photo) cropped.recycle()

                canvas.restore()
            }

            // Cut guides in band coordinates
            val localTop = cellTop - bandTopY
            val localBottom = cellBottom - bandTopY
            when (cutGuideStyle) {
                CutGuideStyle.NONE -> Unit
                CutGuideStyle.THIN_FRAME -> {
                    canvas.drawRect(cellLeft, localTop, cellRight, localBottom, linePaint)
                }
                CutGuideStyle.CUT_MARKS -> {
                    val tick = 3.5f * pxPerMmX
                    // Top-left & top-right ticks
                    canvas.drawLine(cellLeft, localTop, cellLeft + tick, localTop, linePaint)
                    canvas.drawLine(cellLeft, localTop, cellLeft, localTop + tick, linePaint)
                    canvas.drawLine(cellRight, localTop, cellRight - tick, localTop, linePaint)
                    canvas.drawLine(cellRight, localTop, cellRight, localTop + tick, linePaint)
                    // Bottom-left & bottom-right ticks
                    canvas.drawLine(cellLeft, localBottom, cellLeft + tick, localBottom, linePaint)
                    canvas.drawLine(cellLeft, localBottom, cellLeft, localBottom - tick, linePaint)
                    canvas.drawLine(cellRight, localBottom, cellRight - tick, localBottom, linePaint)
                    canvas.drawLine(cellRight, localBottom, cellRight, localBottom - tick, linePaint)
                }
            }
        }

        val pixels = IntArray(pageWidthPx * bandHeightPx)
        bandBitmap.getPixels(pixels, 0, pageWidthPx, 0, 0, pageWidthPx, bandHeightPx)
        bandBitmap.recycle()
        return pixels
    }

    /**
     * Renders a diagnostic sheet band by translating a Canvas onto the band strip.
     */
    fun renderDiagnosticBandArgb(
        metadata: DiagnosticMetadata,
        pageWidthPx: Int,
        pageHeightPx: Int,
        bandTopY: Int,
        bandHeightPx: Int,
        paperWidthMm: Float = 210f,
        paperHeightMm: Float = 297f,
        safeMarginMm: Float = 0f
    ): IntArray {
        val bandBitmap = Bitmap.createBitmap(pageWidthPx, bandHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bandBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        if (safeMarginMm > 0f) {
            val (insetX, insetY) = computeSafeMarginInsetPx(
                paperWidthMm = paperWidthMm,
                paperHeightMm = paperHeightMm,
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                safeMarginMm = safeMarginMm
            )
            val sx = (pageWidthPx - 2f * insetX) / pageWidthPx.toFloat().coerceAtLeast(1f)
            val sy = (pageHeightPx - 2f * insetY) / pageHeightPx.toFloat().coerceAtLeast(1f)
            canvas.translate(insetX, insetY - bandTopY.toFloat())
            canvas.scale(sx, sy)
        } else {
            canvas.translate(0f, -bandTopY.toFloat())
        }
        DiagnosticSheetRenderer.drawIntoCanvas(
            canvas = canvas,
            pageWidthPx = pageWidthPx.toFloat(),
            pageHeightPx = pageHeightPx.toFloat(),
            metadata = metadata
        )
        canvas.restore()
        val pixels = IntArray(pageWidthPx * bandHeightPx)
        bandBitmap.getPixels(pixels, 0, pageWidthPx, 0, 0, pageWidthPx, bandHeightPx)
        bandBitmap.recycle()
        return pixels
    }

    /**
     * Generates a crisp UI preview bitmap (scaled cleanly to [previewMaxWidthPx]) using the exact
     * same geometry, crop, rotation, brightness/contrast, and halftone dither pipeline.
     */
    fun renderPreviewBitmap(
        sourcePixelsRenderer: (w: Int, h: Int) -> IntArray,
        paperWidthMm: Float,
        paperHeightMm: Float,
        previewMaxWidthPx: Int = 720,
        tuning: PrintTuningOptions,
        effectiveMonochrome: Boolean
    ): Bitmap {
        val aspect = paperHeightMm / paperWidthMm
        val w = previewMaxWidthPx.coerceAtLeast(120)
        val h = (w * aspect).toInt().coerceAtLeast(120)
        val pixels = sourcePixelsRenderer(w, h)
        HalftoneDitherEngine.adjustArgbPixelsForPreview(
            argb = pixels,
            width = w,
            height = h,
            brightness = tuning.brightness,
            contrast = tuning.contrast,
            monochrome = effectiveMonochrome,
            ditherMode = tuning.ditherMode
        )
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
