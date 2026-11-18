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

        val ippColorMode = if (effectiveMonochrome) {
            IppUsbEncoder.PwgColorMode.BLACK_1
        } else {
            IppUsbEncoder.PwgColorMode.SRGB_24
        }

        val ippSession = if (effectiveProto == DriverProtocol.IPP_USB_PWG_RASTER) {
            IppUsbEncoder.PwgStreamSession(transport, jobName, copies, paperSize.pwgMediaName)
        } else null

        val pcl5Session = if (effectiveProto == DriverProtocol.PCL_5C_COLOR || effectiveProto == DriverProtocol.PCL_5E_MONO) {
            PclEncoder.Pcl5Session(
                transport = transport,
                colorMode = !effectiveMonochrome && effectiveProto == DriverProtocol.PCL_5C_COLOR,
                dpiX = dpiX,
                dpiY = dpiY,
                paperSize = paperSize,
                copies = copies,
                jobName = jobName
            )
        } else null

        val pcl6Session = if (effectiveProto == DriverProtocol.PCL_6_XL) {
            PclEncoder.Pcl6XlSession(
                transport = transport,
                colorMode = !effectiveMonochrome,
                dpiX = dpiX,
                dpiY = dpiY,
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
            QpdlEncoder.QpdlSession(transport, effectiveProto, dpiX, dpiY, paperSize, copies)
        } else null

        try {
            for (pageIdx in 0 until pageCount) {
                coroutineContext.ensureActive()
                val (mmW, mmH) = pageGeometry(pageIdx)
                val (pageW, pageH) = BandCompositor.alignedPageDimensionsPx(mmW, mmH, dpiX, dpiY)
                val totalBands = (pageH + BandCompositor.BAND_HEIGHT - 1) / BandCompositor.BAND_HEIGHT
                val ditherState = HalftoneDitherEngine.BandDitherState(pageW)

                ippSession?.beginPage(pageW, pageH, dpiX, dpiY, ippColorMode, paperSize, pageCount)
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
