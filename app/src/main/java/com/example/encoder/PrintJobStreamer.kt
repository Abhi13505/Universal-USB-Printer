package com.example.encoder

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.example.document.BandCompositor
import com.example.document.DiagnosticMetadata
import com.example.document.HalftoneDitherEngine
import com.example.document.PhotoLayoutGeometry
import com.example.document.PhotoSheetPlan
import com.example.model.CutGuideStyle
import com.example.model.DitherMode
import com.example.model.DriverProtocol
import com.example.model.PageOrientation
import com.example.model.PaperSize
import com.example.model.PrintJobProgress
import com.example.model.PrintQuality
import com.example.model.PrintTuningOptions
import com.example.model.ScaleMode
import com.example.usb.UsbByteTransport
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

object PrintJobStreamer {

    const val TEST_PAGE_DPI = 300
    const val TEST_PAGE_HEIGHT_PX = 300 // Exact 1 inch at 300 DPI

    fun usesSafeMargin(protocol: DriverProtocol): Boolean = when (protocol) {
        DriverProtocol.PCL_5C_COLOR,
        DriverProtocol.PCL_5E_MONO,
        DriverProtocol.PCL_6_XL,
        DriverProtocol.IPP_USB_PWG_RASTER -> true
        else -> false
    }

    suspend fun streamPdfJob(
        transport: UsbByteTransport,
        contentResolver: ContentResolver,
        pdfUri: Uri,
        pdfPageIndices: List<Int>,
        jobName: String,
        protocol: DriverProtocol,
        effectiveMonochrome: Boolean,
        paperSize: PaperSize,
        orientation: PageOrientation,
        scaleMode: ScaleMode,
        quality: PrintQuality,
        copies: Int,
        tuning: PrintTuningOptions,
        canUseRawPdfPassthrough: Boolean,
        onProgress: (PrintJobProgress) -> Unit
    ) {
        if (protocol == DriverProtocol.UNSUPPORTED) {
            error("Unsupported printer language. Choose a manual protocol override first.")
        }
        if (canUseRawPdfPassthrough && (protocol == DriverProtocol.IPP_USB_PDF || protocol == DriverProtocol.PDF_DIRECT)) {
            onProgress(
                PrintJobProgress(
                    isPrinting = true,
                    currentPage = 1,
                    totalPages = pdfPageIndices.size.coerceAtLeast(1),
                    currentCopy = 1,
                    totalCopies = copies,
                    phaseLabel = "Streaming PDF-Direct..."
                )
            )
            contentResolver.openInputStream(pdfUri)?.use { input ->
                if (protocol == DriverProtocol.IPP_USB_PDF) {
                    IppUsbEncoder.streamPdfJob(transport, jobName, copies, paperSize.pwgMediaName, input)
                } else {
                    PdfAndPostScriptEncoder.streamPdfDirect(transport, jobName, copies, input)
                }
            } ?: error("Unable to open PDF stream")
            return
        }

        val pfd = contentResolver.openFileDescriptor(pdfUri, "r")
            ?: error("Unable to open PDF file descriptor")

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val validPages = pdfPageIndices.filter { it in 0 until renderer.pageCount }
                if (validPages.isEmpty()) error("No PDF pages selected")

                val lut = HalftoneDitherEngine.buildBrightnessContrastLut(tuning.brightness, tuning.contrast)
                streamPagedRasterJob(
                    transport = transport,
                    jobName = jobName,
                    protocol = protocol,
                    effectiveMonochrome = effectiveMonochrome,
                    paperSize = paperSize,
                    dpiX = quality.dpiX,
                    dpiY = quality.dpiY,
                    copies = copies,
                    pageCount = validPages.size,
                    ditherMode = tuning.ditherMode,
                    lut = lut,
                    safeMarginMm = tuning.safeMarginMm,
                    pageGeometry = { idx ->
                        renderer.openPage(validPages[idx]).use { page ->
                            BandCompositor.resolvePdfPaperDimensionsMm(
                                paperSize = paperSize,
                                orientation = orientation,
                                pdfPageWidthPt = page.width,
                                pdfPageHeightPt = page.height
                            )
                        }
                    },
                    bandProvider = { idx, pageW, pageH, mmW, mmH, bandTopY, bandH, effectiveMarginMm ->
                        renderer.openPage(validPages[idx]).use { page ->
                            BandCompositor.renderPdfBandArgb(
                                pdfPage = page,
                                pageWidthPx = pageW,
                                pageHeightPx = pageH,
                                bandTopY = bandTopY,
                                bandHeightPx = bandH,
                                scaleMode = scaleMode,
                                tuning = tuning,
                                paperWidthMm = mmW,
                                paperHeightMm = mmH,
                                safeMarginMm = effectiveMarginMm
                            )
                        }
                    },
                    onProgress = onProgress
                )
            }
        }
    }

    suspend fun streamPhotoBatchJob(
        transport: UsbByteTransport,
        photos: List<Bitmap>,
        sheetPlan: PhotoSheetPlan,
        jobName: String,
        protocol: DriverProtocol,
        effectiveMonochrome: Boolean,
        paperSize: PaperSize,
        quality: PrintQuality,
        copies: Int,
        cutGuideStyle: CutGuideStyle,
        autoRotateToCell: Boolean,
        tuning: PrintTuningOptions,
        onProgress: (PrintJobProgress) -> Unit
    ) {
        if (protocol == DriverProtocol.UNSUPPORTED) {
            error("Unsupported printer language. Choose a manual protocol override first.")
        }
        val lut = HalftoneDitherEngine.buildBrightnessContrastLut(tuning.brightness, tuning.contrast)
        val totalPages = sheetPlan.pages.size.coerceAtLeast(1)

        streamPagedRasterJob(
            transport = transport,
            jobName = jobName,
            protocol = protocol,
            effectiveMonochrome = effectiveMonochrome,
            paperSize = paperSize,
            dpiX = quality.dpiX,
            dpiY = quality.dpiY,
            copies = copies,
            pageCount = totalPages,
            ditherMode = tuning.ditherMode,
            lut = lut,
            safeMarginMm = tuning.safeMarginMm,
            pageGeometry = { sheetPlan.paperWidthMm to sheetPlan.paperHeightMm },
            bandProvider = { idx, pageW, pageH, _, _, bandTopY, bandH, effectiveMarginMm ->
                BandCompositor.renderPhotoSheetBandArgb(
                    sheetPlan = sheetPlan,
                    pageAssignment = sheetPlan.pages[idx],
                    photos = photos,
                    pageWidthPx = pageW,
                    pageHeightPx = pageH,
                    dpiX = quality.dpiX,
                    dpiY = quality.dpiY,
                    bandTopY = bandTopY,
                    bandHeightPx = bandH,
                    cutGuideStyle = cutGuideStyle,
                    tuning = tuning,
                    autoRotateToCell = autoRotateToCell,
                    safeMarginMm = effectiveMarginMm
                )
            },
            onProgress = onProgress
        )
    }

    suspend fun streamDiagnosticSheetJob(
        transport: UsbByteTransport,
        metadata: DiagnosticMetadata,
        protocol: DriverProtocol,
        effectiveMonochrome: Boolean,
        paperSize: PaperSize,
        quality: PrintQuality,
        safeMarginMm: Float = 5f,
        onProgress: (PrintJobProgress) -> Unit
    ) {
        if (protocol == DriverProtocol.UNSUPPORTED) {
            error("Unsupported printer language. Choose a manual protocol override first.")
        }
        val lut = HalftoneDitherEngine.buildBrightnessContrastLut(0f, 1f)
        streamPagedRasterJob(
            transport = transport,
            jobName = "Diagnostic Sheet",
            protocol = protocol,
            effectiveMonochrome = effectiveMonochrome,
            paperSize = paperSize,
            dpiX = quality.dpiX,
            dpiY = quality.dpiY,
            copies = 1,
            pageCount = 1,
            ditherMode = DitherMode.HALFTONE_FLOYD_STEINBERG,
            lut = lut,
            safeMarginMm = safeMarginMm,
            pageGeometry = { paperSize.widthMm to paperSize.heightMm },
            bandProvider = { _, pageW, pageH, mmW, mmH, bandTopY, bandH, effectiveMarginMm ->
                BandCompositor.renderDiagnosticBandArgb(
                    metadata = metadata,
                    pageWidthPx = pageW,
                    pageHeightPx = pageH,
                    bandTopY = bandTopY,
                    bandHeightPx = bandH,
                    paperWidthMm = mmW,
                    paperHeightMm = mmH,
                    safeMarginMm = effectiveMarginMm
                )
            },
            onProgress = onProgress
        )
    }

    /**
     * Sends a tiny 1-inch tall raster strip (300 px at 300 DPI) in the selected protocol so users
     * can verify an unverified printer without wasting a full sheet of paper or streaming garbage.
     */
    suspend fun streamTestPageJob(
        transport: UsbByteTransport,
        printerName: String,
        protocol: DriverProtocol,
        effectiveMonochrome: Boolean,
        paperSize: PaperSize,
        safeMarginMm: Float = 5f,
        onProgress: (PrintJobProgress) -> Unit
    ) {
        if (protocol == DriverProtocol.UNSUPPORTED) {
            error("Unsupported printer language. Choose a manual protocol override first.")
        }
        val lut = HalftoneDitherEngine.buildBrightnessContrastLut(0f, 1f)
        val probeDpi = TEST_PAGE_DPI
        val effectiveProto = when (protocol) {
            DriverProtocol.IPP_USB_PDF -> DriverProtocol.IPP_USB_PWG_RASTER
            DriverProtocol.PDF_DIRECT -> if (effectiveMonochrome) DriverProtocol.PCL_5E_MONO else DriverProtocol.PCL_5C_COLOR
            DriverProtocol.AUTO_DETECTED -> DriverProtocol.PCL_5E_MONO
            else -> protocol
        }
        val effectiveMarginMm = if (usesSafeMargin(effectiveProto)) safeMarginMm.coerceIn(0f, 15f) else 0f
        val (pageW, _) = BandCompositor.alignedPageDimensionsPx(paperSize.widthMm, paperSize.heightMm, probeDpi, probeDpi)
        val testHeightPx = TEST_PAGE_HEIGHT_PX // Exact 1 inch (300 px at 300 DPI)
        val (insetX, _) = BandCompositor.computeSafeMarginInsetPx(
            paperWidthMm = paperSize.widthMm,
            paperHeightMm = paperSize.heightMm,
            pageWidthPx = pageW,
            pageHeightPx = testHeightPx,
            safeMarginMm = effectiveMarginMm
        )
        val insetY = (effectiveMarginMm * (probeDpi / 25.4f)).coerceIn(0f, 80f)

        val fullBitmap = Bitmap.createBitmap(pageW, testHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fullBitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 32f
            isFakeBoldText = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val subPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 24f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val borderPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        val left = (insetX + 16f).coerceAtMost(pageW - 40f)
        val right = (pageW - insetX - 16f).coerceAtLeast(left + 20f)
        val top = (insetY + 12f).coerceAtMost(testHeightPx - 40f)
        val bottom = (testHeightPx - insetY - 12f).coerceAtLeast(top + 20f)
        canvas.drawRect(left, top, right, bottom, borderPaint)
        canvas.drawText(
            "1-INCH USB TEST PAGE | ${effectiveProto.shortCode}",
            left + 24f,
            (top + 60f).coerceAtMost(bottom - 60f),
            titlePaint
        )
        canvas.drawText(
            "$printerName | ${probeDpi} DPI | ${if (effectiveMonochrome) "Mono" else "Color"} | Margin ${effectiveMarginMm.toInt()}mm",
            left + 24f,
            (top + 115f).coerceAtMost(bottom - 20f),
            subPaint
        )
        val fullArgb = IntArray(pageW * testHeightPx)
        fullBitmap.getPixels(fullArgb, 0, pageW, 0, 0, pageW, testHeightPx)
        fullBitmap.recycle()

        val totalBands = (testHeightPx + BandCompositor.BAND_HEIGHT - 1) / BandCompositor.BAND_HEIGHT
        val ditherState = HalftoneDitherEngine.BandDitherState(pageW)

        val ippSession = if (effectiveProto == DriverProtocol.IPP_USB_PWG_RASTER) {
            IppUsbEncoder.PwgStreamSession(transport, "1-Inch Test Page", 1, paperSize.pwgMediaName)
        } else null
        val pcl5Session = if (effectiveProto == DriverProtocol.PCL_5C_COLOR || effectiveProto == DriverProtocol.PCL_5E_MONO) {
            PclEncoder.Pcl5Session(
                transport = transport,
                colorMode = !effectiveMonochrome && effectiveProto == DriverProtocol.PCL_5C_COLOR,
                dpiX = probeDpi,
                dpiY = probeDpi,
                paperSize = paperSize,
                copies = 1,
                jobName = "1-Inch Test Page"
            )
        } else null
        val pcl6Session = if (effectiveProto == DriverProtocol.PCL_6_XL) {
            PclEncoder.Pcl6XlSession(
                transport = transport,
                colorMode = !effectiveMonochrome,
                dpiX = probeDpi,
                dpiY = probeDpi,
                paperSize = paperSize,
                copies = 1,
                jobName = "1-Inch Test Page"
            )
        } else null
        val psSession = if (effectiveProto == DriverProtocol.POSTSCRIPT) {
            PdfAndPostScriptEncoder.PostScriptSession(
                transport = transport,
                colorMode = !effectiveMonochrome,
                copies = 1,
                jobName = "1-Inch Test Page"
            )
        } else null
        val qpdlSession = if (effectiveProto == DriverProtocol.SAMSUNG_QPDL_0X11 ||
            effectiveProto == DriverProtocol.SAMSUNG_QPDL_0X13_JBIG1 ||
            effectiveProto == DriverProtocol.SAMSUNG_QPDL_0X15_COLOR
        ) {
            QpdlEncoder.QpdlSession(transport, effectiveProto, probeDpi, probeDpi, paperSize, 1)
        } else null

        val ippColorMode = if (effectiveMonochrome) IppUsbEncoder.PwgColorMode.BLACK_1 else IppUsbEncoder.PwgColorMode.SRGB_24
        val testHeightMm = (testHeightPx.toFloat() / probeDpi.toFloat()) * 25.4f

        ippSession?.beginPage(pageW, testHeightPx, probeDpi, probeDpi, ippColorMode, paperSize, 1)
        pcl5Session?.beginPage(pageW, testHeightPx)
        pcl6Session?.beginPage(pageW, testHeightPx)
        psSession?.beginPage(0, paperSize.widthMm, testHeightMm, pageW, testHeightPx)
        qpdlSession?.beginPage(pageW, testHeightPx)

        for (bandIdx in 0 until totalBands) {
            coroutineContext.ensureActive()
            val startY = bandIdx * BandCompositor.BAND_HEIGHT
            val bandH = minOf(BandCompositor.BAND_HEIGHT, testHeightPx - startY)
            val bandArgb = fullArgb.copyOfRange(startY * pageW, (startY + bandH) * pageW)

            ippSession?.writeBand(bandArgb, pageW, bandH, startY, ippColorMode, DitherMode.CRISP_TEXT, lut, ditherState)
            pcl5Session?.writeBand(bandArgb, pageW, bandH, startY, DitherMode.CRISP_TEXT, lut, ditherState)
            pcl6Session?.writeBand(bandArgb, pageW, bandH, startY, lut)
            psSession?.writeBand(bandArgb, pageW, bandH, startY, DitherMode.CRISP_TEXT, lut, ditherState)
            qpdlSession?.writeBand(bandIdx, totalBands, bandArgb, pageW, bandH, startY, testHeightPx, DitherMode.CRISP_TEXT, lut, ditherState)

            onProgress(
                PrintJobProgress(
                    isPrinting = true,
                    currentPage = 1,
                    totalPages = 1,
                    currentCopy = 1,
                    totalCopies = 1,
                    currentBand = bandIdx + 1,
                    totalBands = totalBands,
                    phaseLabel = "Sending 1-inch test page (${bandIdx + 1}/$totalBands bands)..."
                )
            )
        }

        pcl5Session?.endPage()
        pcl6Session?.endPage()
        psSession?.endPage()
        qpdlSession?.endPage()

        ippSession?.finish()
        pcl5Session?.finish()
        pcl6Session?.finish()
        psSession?.finish()
        qpdlSession?.finish()
    }

    private suspend fun streamPagedRasterJob(
        transport: UsbByteTransport,
        jobName: String,
        protocol: DriverProtocol,
        effectiveMonochrome: Boolean,
        paperSize: PaperSize,
        dpiX: Int,
        dpiY: Int,
        copies: Int,
        pageCount: Int,
        ditherMode: DitherMode,
        lut: IntArray,
        safeMarginMm: Float,
        pageGeometry: (Int) -> Pair<Float, Float>,
        bandProvider: (pageIdx: Int, pageW: Int, pageH: Int, mmW: Float, mmH: Float, bandTopY: Int, bandH: Int, effectiveMarginMm: Float) -> IntArray,
        onProgress: (PrintJobProgress) -> Unit
    ) {
        val effectiveProto = when (protocol) {
            DriverProtocol.IPP_USB_PDF -> DriverProtocol.IPP_USB_PWG_RASTER
            DriverProtocol.PDF_DIRECT -> if (effectiveMonochrome) DriverProtocol.PCL_5E_MONO else DriverProtocol.PCL_5C_COLOR
            DriverProtocol.AUTO_DETECTED -> DriverProtocol.PCL_5E_MONO
            DriverProtocol.UNSUPPORTED -> error("Unsupported printer language")
            else -> protocol
        }

        // Apply safe printable margin ONLY for PCL 5, PCL-XL, and PWG Raster jobs.
        // Samsung QPDL, PostScript, and PDF-Direct remain untouched.
        val effectiveSafeMarginMm = if (usesSafeMargin(effectiveProto)) {
            safeMarginMm.coerceIn(0f, 25f)
        } else {
            0f
        }

        // PCL 5 raster mode only supports up to 600x600 DPI (300 or 600)
        val isPcl5 = effectiveProto == DriverProtocol.PCL_5C_COLOR || effectiveProto == DriverProtocol.PCL_5E_MONO
        val effectiveDpiX = if (isPcl5) PclEncoder.normalizePcl5Resolution(dpiX) else dpiX
        val effectiveDpiY = if (isPcl5) PclEncoder.normalizePcl5Resolution(dpiY) else dpiY

        val ippColorMode = if (effectiveMonochrome) {
            IppUsbEncoder.PwgColorMode.BLACK_1
        } else {
            IppUsbEncoder.PwgColorMode.SRGB_24
        }

        val ippSession = if (effectiveProto == DriverProtocol.IPP_USB_PWG_RASTER) {
            IppUsbEncoder.PwgStreamSession(transport, jobName, copies, paperSize.pwgMediaName)
        } else null

        val pcl5Session = if (isPcl5) {
            PclEncoder.Pcl5Session(
                transport = transport,
                colorMode = !effectiveMonochrome && effectiveProto == DriverProtocol.PCL_5C_COLOR,
                dpiX = effectiveDpiX,
                dpiY = effectiveDpiY,
                paperSize = paperSize,
                copies = copies,
                jobName = jobName
            )
        } else null

        val pcl6Session = if (effectiveProto == DriverProtocol.PCL_6_XL) {
            PclEncoder.Pcl6XlSession(
                transport = transport,
                colorMode = !effectiveMonochrome,
                dpiX = effectiveDpiX,
                dpiY = effectiveDpiY,
                paperSize = paperSize,
                copies = copies,
                jobName = jobName
            )
        } else null

        val psSession = if (effectiveProto == DriverProtocol.POSTSCRIPT) {
            PdfAndPostScriptEncoder.PostScriptSession(
                transport = transport,
                colorMode = !effectiveMonochrome,
                copies = copies,
                jobName = jobName
            )
        } else null

        val qpdlSession = if (effectiveProto == DriverProtocol.SAMSUNG_QPDL_0X11 ||
            effectiveProto == DriverProtocol.SAMSUNG_QPDL_0X13_JBIG1 ||
            effectiveProto == DriverProtocol.SAMSUNG_QPDL_0X15_COLOR
        ) {
            QpdlEncoder.QpdlSession(transport, effectiveProto, effectiveDpiX, effectiveDpiY, paperSize, copies)
        } else null

        try {
            for (pageIdx in 0 until pageCount) {
                coroutineContext.ensureActive()
                val (mmW, mmH) = pageGeometry(pageIdx)
                val (pageW, pageH) = BandCompositor.alignedPageDimensionsPx(mmW, mmH, effectiveDpiX, effectiveDpiY)
                val totalBands = (pageH + BandCompositor.BAND_HEIGHT - 1) / BandCompositor.BAND_HEIGHT
                val ditherState = HalftoneDitherEngine.BandDitherState(pageW)

                ippSession?.beginPage(pageW, pageH, effectiveDpiX, effectiveDpiY, ippColorMode, paperSize, pageCount)
                pcl5Session?.beginPage(pageW, pageH)
                pcl6Session?.beginPage(pageW, pageH)
                psSession?.beginPage(pageIdx, mmW, mmH, pageW, pageH)
                qpdlSession?.beginPage(pageW, pageH)

                for (bandIdx in 0 until totalBands) {
                    coroutineContext.ensureActive()
                    val startY = bandIdx * BandCompositor.BAND_HEIGHT
                    val bandH = minOf(BandCompositor.BAND_HEIGHT, pageH - startY)
                    val argb = bandProvider(pageIdx, pageW, pageH, mmW, mmH, startY, bandH, effectiveSafeMarginMm)

                    ippSession?.writeBand(argb, pageW, bandH, startY, ippColorMode, ditherMode, lut, ditherState)
                    pcl5Session?.writeBand(argb, pageW, bandH, startY, ditherMode, lut, ditherState)
                    pcl6Session?.writeBand(argb, pageW, bandH, startY, lut)
                    psSession?.writeBand(argb, pageW, bandH, startY, ditherMode, lut, ditherState)
                    qpdlSession?.writeBand(bandIdx, totalBands, argb, pageW, bandH, startY, pageH, ditherMode, lut, ditherState)

                    onProgress(
                        PrintJobProgress(
                            isPrinting = true,
                            currentPage = pageIdx + 1,
                            totalPages = pageCount,
                            currentCopy = 1,
                            totalCopies = copies,
                            currentBand = bandIdx + 1,
                            totalBands = totalBands,
                            phaseLabel = "Streaming band ${bandIdx + 1}/$totalBands (Page ${pageIdx + 1}/$pageCount)"
                        )
                    )
                }

                pcl5Session?.endPage()
                pcl6Session?.endPage()
                psSession?.endPage()
                qpdlSession?.endPage()
            }
        } finally {
            // Best-effort job trailer when not aborted mid-IO
        }

        ippSession?.finish()
        pcl5Session?.finish()
        pcl6Session?.finish()
        psSession?.finish()
        qpdlSession?.finish()
    }
}
