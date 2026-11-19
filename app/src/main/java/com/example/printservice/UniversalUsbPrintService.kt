package com.example.printservice

import android.net.Uri
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.example.data.AppDatabase
import com.example.data.PrintHistoryEntity
import com.example.data.PrintHistoryRepository
import com.example.data.PrinterPreferencesStore
import com.example.document.PageRangeParser
import com.example.encoder.PrintJobStreamer
import com.example.model.DriverProtocol
import com.example.model.PageOrientation
import com.example.model.PageRangeMode
import com.example.model.PaperSize
import com.example.model.PrintJobStatus
import com.example.model.PrintQuality
import com.example.model.PrintTuningOptions
import com.example.model.ScaleMode
import com.example.model.UsbPrinterState
import com.example.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class UniversalUsbPrintService : PrintService() {

    private lateinit var usbManager: UsbPrinterManager
    private lateinit var historyRepo: PrintHistoryRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        val prefs = PrinterPreferencesStore(applicationContext)
        usbManager = UsbPrinterManager(applicationContext, prefs)
        historyRepo = PrintHistoryRepository(AppDatabase.getInstance(applicationContext).printHistoryDao())
        usbManager.startListening()
    }

    override fun onDestroy() {
        usbManager.stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return UsbDiscoverySession()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val id = printJob.id.toString()
        activeJobs[id]?.cancel()
        if (printJob.isStarted || printJob.isQueued) {
            printJob.cancel()
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val jobId = printJob.id.toString()
        val state = usbManager.printerState.value
        if (state !is UsbPrinterState.Ready) {
            val reason = when (state) {
                is UsbPrinterState.PermissionRequired -> "USB permission not granted. Open Universal USB Printer to grant access."
                is UsbPrinterState.Error -> state.message
                else -> "No USB OTG printer connected."
            }
            printJob.fail(reason)
            return
        }
        if (state.profile.isBestEffortFallback && !state.profile.overrideActive) {
            printJob.fail(
                "Printer language (${state.profile.effectiveProtocol.shortCode}) is an unverified fallback. Open Universal USB Printer once to run a 1-band probe or confirm the language so paper is not wasted."
            )
            return
        }

        val spooledFd = printJob.document.data
        if (spooledFd == null) {
            printJob.fail("Empty document stream from Android Print Spooler.")
            return
        }

        val info = printJob.info
        val attrs = info.attributes
        val tempPdf = File(cacheDir, "spool_${System.currentTimeMillis()}.pdf")
        try {
            FileInputStream(spooledFd.fileDescriptor).use { input ->
                FileOutputStream(tempPdf).use { output ->
                    input.copyTo(output)
                }
            }
            spooledFd.close()
        } catch (e: Exception) {
            printJob.fail("Failed to read spooled PDF: ${e.message}")
            tempPdf.delete()
            return
        }

        printJob.start()

        val bytesCounter = AtomicLong(0L)
        val protocol = state.profile.effectiveProtocol
        val opened = usbManager.openPrintTransport(protocol) { written ->
            bytesCounter.addAndGet(written.toLong())
        }
        if (opened == null) {
            tempPdf.delete()
            printJob.fail("Unable to claim USB printer interface.")
            return
        }
        val (transport, cleanup) = opened

        val paperSize = mapMediaSize(attrs.mediaSize)
        val quality = mapResolution(attrs.resolution)
        val effectiveMono = attrs.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME ||
            !state.profile.effectiveColorSupported
        val copies = info.copies.coerceIn(1, 99)
        val jobTitle = info.label.takeIf { it.isNotBlank() } ?: "System Print Job"

        val job = serviceScope.launch {
            var finalStatus = PrintJobStatus.COMPLETED
            var printedPagesCount = 1
            try {
                val totalPages = android.os.ParcelFileDescriptor.open(
                    tempPdf,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                ).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
                }
                val pages = mapPrintRanges(info.pages, totalPages)
                printedPagesCount = pages.size.coerceAtLeast(1)
                val canRawPdf = pages.size == totalPages &&
                    (protocol == DriverProtocol.IPP_USB_PDF || protocol == DriverProtocol.PDF_DIRECT) &&
                    !effectiveMono

                PrintJobStreamer.streamPdfJob(
                    transport = transport,
                    contentResolver = contentResolver,
                    pdfUri = Uri.fromFile(tempPdf),
                    pdfPageIndices = pages,
                    jobName = jobTitle,
                    protocol = protocol,
                    effectiveMonochrome = effectiveMono,
                    paperSize = paperSize,
                    orientation = PageOrientation.AUTO,
                    scaleMode = ScaleMode.SCALE_TO_FIT,
                    quality = quality,
                    copies = copies,
                    tuning = PrintTuningOptions(),
                    canUseRawPdfPassthrough = canRawPdf,
                    onProgress = {}
                )
                printJob.complete()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                finalStatus = PrintJobStatus.CANCELLED
                if (!printJob.isCancelled) printJob.cancel()
            } catch (e: Exception) {
                finalStatus = PrintJobStatus.FAILED
                printJob.fail(e.message ?: "USB transmission failed")
            } finally {
                cleanup()
                tempPdf.delete()
                activeJobs.remove(jobId)
                historyRepo.recordJob(
                    PrintHistoryEntity(
                        documentTitle = "$jobTitle (System)",
                        pageCount = printedPagesCount,
                        copies = copies,
                        paperSize = paperSize.displayName,
                        resolvedProtocol = protocol.displayName,
                        colorMode = if (effectiveMono) "Monochrome" else "Color",
                        bytesSent = bytesCounter.get(),
                        status = finalStatus.displayName
                    )
                )
            }
        }
        activeJobs[jobId] = job
    }

    private fun mapMediaSize(media: PrintAttributes.MediaSize?): PaperSize {
        return when (media?.id) {
            PrintAttributes.MediaSize.NA_LETTER.id -> PaperSize.LETTER
            PrintAttributes.MediaSize.NA_LEGAL.id -> PaperSize.LEGAL
            PrintAttributes.MediaSize.ISO_A5.id -> PaperSize.A5
            else -> PaperSize.A4
        }
    }

    private fun mapResolution(res: PrintAttributes.Resolution?): PrintQuality {
        val dpi = res?.horizontalDpi ?: 600
        return when {
            dpi <= 300 -> PrintQuality.TONER_SAVE
            dpi >= 1200 -> PrintQuality.HIGH_1200X600
            else -> PrintQuality.STANDARD_600
        }
    }

    private fun mapPrintRanges(ranges: Array<android.print.PageRange>?, totalPages: Int): List<Int> {
        if (ranges == null || ranges.isEmpty()) return (0 until totalPages).toList()
        val result = linkedSetOf<Int>()
        for (r in ranges) {
            if (r == android.print.PageRange.ALL_PAGES) {
                return (0 until totalPages).toList()
            }
            val start = r.start.coerceIn(0, totalPages - 1)
            val end = r.end.coerceIn(start, totalPages - 1)
            for (p in start..end) result.add(p)
        }
        return if (result.isEmpty()) PageRangeParser.resolvePages(totalPages, PageRangeMode.ALL, 0, "") else result.toList()
    }

    private inner class UsbDiscoverySession : PrinterDiscoverySession() {
        private var watchJob: Job? = null
        private val printerId: PrinterId = generatePrinterId("universal_usb_otg_printer")

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            usbManager.scanAttachedPrinters()
            watchJob?.cancel()
            watchJob = serviceScope.launch(Dispatchers.Main) {
                usbManager.printerState.collectLatest { state ->
                    publishState(state)
                }
            }
        }

        override fun onStopPrinterDiscovery() {
            watchJob?.cancel()
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            usbManager.scanAttachedPrinters()
            publishState(usbManager.printerState.value)
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            publishState(usbManager.printerState.value)
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

        override fun onDestroy() {
            watchJob?.cancel()
        }

        private fun publishState(state: UsbPrinterState) {
            when (state) {
                is UsbPrinterState.Ready -> {
                    val colorMask = if (state.profile.effectiveColorSupported) {
                        PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME
                    } else {
                        PrintAttributes.COLOR_MODE_MONOCHROME
                    }
                    val caps = PrinterCapabilitiesInfo.Builder(printerId)
                        .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                        .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                        .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
                        .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                        .addResolution(PrintAttributes.Resolution("300", "300 DPI (Toner Save)", 300, 300), false)
                        .addResolution(PrintAttributes.Resolution("600", "600 DPI", 600, 600), true)
                        .addResolution(PrintAttributes.Resolution("1200", "1200×600 DPI", 1200, 600), false)
                        .setColorModes(colorMask, if (state.profile.effectiveColorSupported) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()

                    val info = PrinterInfo.Builder(printerId, state.profile.makeAndModel, PrinterInfo.STATUS_IDLE)
                        .setDescription("USB OTG (${state.profile.effectiveProtocol.shortCode})")
                        .setCapabilities(caps)
                        .build()
                    addPrinters(listOf(info))
                }
                is UsbPrinterState.PermissionRequired -> {
                    val info = PrinterInfo.Builder(
                        printerId,
                        state.deviceInfo.productName ?: "USB Printer (${state.deviceInfo.vidPidKey})",
                        PrinterInfo.STATUS_UNAVAILABLE
                    )
                        .setDescription("Open Universal USB Printer to grant USB permission")
                        .build()
                    addPrinters(listOf(info))
                }
                else -> {
                    removePrinters(listOf(printerId))
                }
            }
        }
    }
}
