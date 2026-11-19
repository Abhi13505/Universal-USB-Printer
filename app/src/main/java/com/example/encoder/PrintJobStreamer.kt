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
                    bandProvider = { idx, pageW, pageH, bandTopY, bandH ->
                        renderer.openPage(validPages[idx]).use { page ->
                            BandCompositor.renderPdfBandArgb(
                                pdfPage = page,
                                pageWidthPx = pageW,
                                pageHeightPx = pageH,
                                bandTopY = bandTopY,
                                bandHeightPx = bandH,
                                scaleMode = scaleMode,
                                tuning = tuning
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
            pageGeometry = { sheetPlan.paperWidthMm to sheetPlan.paperHeightMm },
            bandProvider = { idx, pageW, pageH, bandTopY, bandH ->
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
                    autoRotateToCell = autoRotateToCell
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
        onProgress: (PrintJobProgress) -> Unit
    ) {
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
            pageGeometry = { paperSize.widthMm to paperSize.heightMm },
            bandProvider = { _, pageW, pageH, bandTopY, bandH ->
                BandCompositor.renderDiagnosticBandArgb(
                    metadata = metadata,
                    pageWidthPx = pageW,
                    pageHeightPx = pageH,
                    bandTopY = bandTopY,
                    bandHeightPx = bandH
                )
            },
            onProgress = onProgress
        )
    }

    /**
     * Sends a tiny 1-band (128-line) probe strip at 300 DPI so users can test an unverified/fallback
     * printer protocol using minimal data (< 4 KB) without risking dozens of pages of raw @PJL garbage.
     */
    suspend fun streamTinyProbeJob(
        transport: UsbByteTransport,
        printerName: String,
        protocol: DriverProtocol,
        effectiveMonochrome: Boolean,
        paperSize: PaperSize,
        onProgress: (PrintJobProgress) -> Unit
    ) {
        val lut = HalftoneDitherEngine.buildBrightnessContrastLut(0f, 1f)
        val probeDpi = 300
        val effectiveProto = when (protocol) {
            DriverProtocol.IPP_USB_PDF -> DriverProtocol.IPP_USB_PWG_RASTER
            DriverProtocol.PDF_DIRECT -> if (effectiveMonochrome) DriverProtocol.PCL_5E_MONO else DriverProtocol.PCL_5C_COLOR
            DriverProtocol.AUTO_DETECTED -> DriverProtocol.PCL_5E_MONO
            else -> protocol
        }
        val (pageW, _) = BandCompositor.alignedPageDimensionsPx(paperSize.widthMm, paperSize.heightMm, probeDpi, probeDpi)
        val probeHeightPx = BandCompositor.BAND_HEIGHT // 1 single 128-line band!

        onProgress(
            PrintJobProgress(
                isPrinting = true,
                currentPage = 1,
                totalPages = 1,
                currentCopy = 1,
                totalCopies = 1,
                currentBand = 1,
                totalBands = 1,
                phaseLabel = "Sending 1-band (${probeHeightPx}px) protocol probe..."
            )
        )

        val bmp = Bitmap.createBitmap(pageW, probeHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 28f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val borderPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(24f, 12f, pageW - 24f, probeHeightPx - 12f, borderPaint)
        canvas.drawText(
            "USB PROBE OK | $printerName | ${effectiveProto.shortCode} | 300 DPI",
            48f,
            74f,
            paint
        )
        val argb = IntArray(pageW * probeHeightPx)
        bmp.getPixels(argb, 0, pageW, 0, 0, pageW, probeHeightPx)
        bmp.recycle()

        val ditherState = HalftoneDitherEngine.BandDitherState(pageW)
        when (effectiveProto) {
            DriverProtocol.IPP_USB_PWG_RASTER -> {
                val colorMode = if (effectiveMonochrome) IppUsbEncoder.PwgColorMode.BLACK_1 else IppUsbEncoder.PwgColorMode.SRGB_24
                val session = IppUsbEncoder.PwgStreamSession(transport, "Tiny Probe", 1, paperSize.pwgMediaName)
                session.beginPage(pageW, probeHeightPx, probeDpi, probeDpi, colorMode, paperSize, 1)
                session.writeBand(argb, pageW, probeHeightPx, 0, colorMode, DitherMode.CRISP_TEXT, lut, ditherState)
                session.finish()
            }
            DriverProtocol.PCL_5C_COLOR, DriverProtocol.PCL_5E_MONO -> {
                val session = PclEncoder.Pcl5Session(
                    transport = transport,
                    colorMode = !effectiveMonochrome && effectiveProto == DriverProtocol.PCL_5C_COLOR,
                    dpiX = probeDpi,
                    dpiY = probeDpi,
                    paperSize = paperSize,
                    copies = 1,
                    jobName = "Tiny Probe"
                )
                session.beginPage(pageW, probeHeightPx)
                session.writeBand(argb, pageW, probeHeightPx, 0, DitherMode.CRISP_TEXT, lut, ditherState)
                session.endPage()
                session.finish()
            }
            DriverProtocol.PCL_6_XL -> {
                val session = PclEncoder.Pcl6XlSession(
                    transport = transport,
                    colorMode = !effectiveMonochrome,
                    dpiX = probeDpi,
                    dpiY = probeDpi,
                    paperSize = paperSize,
                    copies = 1,
                    jobName = "Tiny Probe"
                )
                session.beginPage(pageW, probeHeightPx)
                session.writeBand(argb, pageW, probeHeightPx, 0, lut)
                session.endPage()
                session.finish()
            }
            DriverProtocol.POSTSCRIPT -> {
                val session = PdfAndPostScriptEncoder.PostScriptSession(
                    transport = transport,
                    colorMode = !effectiveMonochrome,
                    copies = 1,
                    jobName = "Tiny Probe"
                )
                val probeHeightMm = (probeHeightPx.toFloat() / probeDpi.toFloat()) * 25.4f
                session.beginPage(0, paperSize.widthMm, probeHeightMm, pageW, probeHeightPx)
                session.writeBand(argb, pageW, probeHeightPx, 0, DitherMode.CRISP_TEXT, lut, ditherState)
                session.endPage()
                session.finish()
            }
            DriverProtocol.SAMSUNG_QPDL_0X11,
            DriverProtocol.SAMSUNG_QPDL_0X13_JBIG1,
            DriverProtocol.SAMSUNG_QPDL_0X15_COLOR -> {
                val session = QpdlEncoder.QpdlSession(transport, effectiveProto, probeDpi, probeDpi, paperSize, 1)
                session.beginPage(pageW, probeHeightPx)
                session.writeBand(0, 1, argb, pageW, probeHeightPx, 0, probeHeightPx, DitherMode.CRISP_TEXT, lut, ditherState)
                session.endPage()
                session.finish()
            }
            else -> Unit
        }
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
        pageGeometry: (Int) -> Pair<Float, Float>,
        bandProvider: (pageIdx: Int, pageW: Int, pageH: Int, bandTopY: Int, bandH: Int) -> IntArray,
        onProgress: (PrintJobProgress) -> Unit
    ) {
        val effectiveProto = when (protocol) {
            DriverProtocol.IPP_USB_PDF -> DriverProtocol.IPP_USB_PWG_RASTER
            DriverProtocol.PDF_DIRECT -> if (effectiveMonochrome) DriverProtocol.PCL_5E_MONO else DriverProtocol.PCL_5C_COLOR
            DriverProtocol.AUTO_DETECTED -> DriverProtocol.PCL_5E_MONO
            else -> protocol
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
                    val argb = bandProvider(pageIdx, pageW, pageH, startY, bandH)

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
