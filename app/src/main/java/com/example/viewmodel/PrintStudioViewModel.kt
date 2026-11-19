package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PrintHistoryEntity
import com.example.data.PrintHistoryRepository
import com.example.data.PrinterDeviceOverride
import com.example.data.PrinterPreferencesStore
import com.example.document.BandCompositor
import com.example.document.DiagnosticMetadata
import com.example.document.DiagnosticSheetRenderer
import com.example.document.PageRangeParser
import com.example.document.PhotoLayoutGeometry
import com.example.document.PhotoSheetPlan
import com.example.document.ResilientPhotoDecoder
import com.example.encoder.PrintJobStreamer
import com.example.model.ColorOverrideMode
import com.example.model.DriverProtocol
import com.example.model.NormalizedCropRect
import com.example.model.OutputMode
import com.example.model.PaperSize
import com.example.model.PdfPrintOptions
import com.example.model.PhotoLayoutOptions
import com.example.model.PrintJobProgress
import com.example.model.PrintJobStatus
import com.example.model.PrintQuality
import com.example.model.PrintTuningOptions
import com.example.model.ScaleMode
import com.example.model.UsbDeviceInfo
import com.example.model.UsbPrinterState
import com.example.usb.PrinterCapabilityResolver
import com.example.usb.UsbPrinterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class AppTab(val label: String) {
    PRINT_STUDIO("Print Studio"),
    PRINTER_HISTORY("Printer & History")
}

data class LoadedPhotoItem(
    val uri: Uri,
    val bitmap: Bitmap,
    val label: String
)

sealed interface StudioDocument {
    data object Empty : StudioDocument
    data class PdfDoc(
        val uri: Uri,
        val title: String,
        val totalPages: Int
    ) : StudioDocument
    data class PhotoBatch(
        val items: List<LoadedPhotoItem>
    ) : StudioDocument
    data object DiagnosticSheet : StudioDocument
}

class PrintStudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferencesStore = PrinterPreferencesStore(appContext)
    private val historyRepo = PrintHistoryRepository(AppDatabase.getInstance(appContext).printHistoryDao())
    val usbPrinterManager = UsbPrinterManager(appContext, preferencesStore)

    val usbState: StateFlow<UsbPrinterState> = usbPrinterManager.printerState
    val printHistory: StateFlow<List<PrintHistoryEntity>> = historyRepo.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeTab = MutableStateFlow(AppTab.PRINT_STUDIO)
    val activeTab: StateFlow<AppTab> = _activeTab.asStateFlow()

    private val _document = MutableStateFlow<StudioDocument>(StudioDocument.Empty)
    val document: StateFlow<StudioDocument> = _document.asStateFlow()

    private val _tuning = MutableStateFlow(PrintTuningOptions())
    val tuning: StateFlow<PrintTuningOptions> = _tuning.asStateFlow()

    private val _pdfOptions = MutableStateFlow(PdfPrintOptions())
    val pdfOptions: StateFlow<PdfPrintOptions> = _pdfOptions.asStateFlow()

    private val _photoLayout = MutableStateFlow(PhotoLayoutOptions())
    val photoLayout: StateFlow<PhotoLayoutOptions> = _photoLayout.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _currentPreviewPage = MutableStateFlow(0)
    val currentPreviewPage: StateFlow<Int> = _currentPreviewPage.asStateFlow()

    private val _photoSheetPlan = MutableStateFlow<PhotoSheetPlan?>(null)
    val photoSheetPlan: StateFlow<PhotoSheetPlan?> = _photoSheetPlan.asStateFlow()

    private val _deviceOverride = MutableStateFlow(PrinterDeviceOverride())
    val deviceOverride: StateFlow<PrinterDeviceOverride> = _deviceOverride.asStateFlow()

    private val _printProgress = MutableStateFlow(PrintJobProgress())
    val printProgress: StateFlow<PrintJobProgress> = _printProgress.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _lastPrintError = MutableStateFlow<String?>(null)
    val lastPrintError: StateFlow<String?> = _lastPrintError.asStateFlow()

    private val _showFallbackConfirmDialog = MutableStateFlow(false)
    val showFallbackConfirmDialog: StateFlow<Boolean> = _showFallbackConfirmDialog.asStateFlow()

    private var previewJob: Job? = null
    private var printJob: Job? = null
    private var overrideObserverJob: Job? = null

    init {
        usbPrinterManager.startListening()
        viewModelScope.launch {
            usbState.collectLatest { state ->
                val vidPid = when (state) {
                    is UsbPrinterState.Ready -> state.deviceInfo.vidPidKey
                    is UsbPrinterState.PermissionRequired -> state.deviceInfo.vidPidKey
                    is UsbPrinterState.Probing -> state.deviceInfo.vidPidKey
                    is UsbPrinterState.Error -> state.deviceInfo?.vidPidKey
                    UsbPrinterState.Disconnected -> null
                }
                overrideObserverJob?.cancel()
                if (vidPid != null) {
                    overrideObserverJob = launch {
                        preferencesStore.observeOverride(vidPid).collectLatest {
                            _deviceOverride.value = it
                        }
                    }
                } else {
                    _deviceOverride.value = PrinterDeviceOverride()
                }
                refreshPreview()
            }
        }
    }

    override fun onCleared() {
        usbPrinterManager.stopListening()
        super.onCleared()
    }

    fun selectTab(tab: AppTab) {
        _activeTab.value = tab
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    fun dismissPrintError() {
        _lastPrintError.value = null
    }

    fun scanUsb() {
        usbPrinterManager.scanAttachedPrinters()
    }

    fun requestUsbPermission() {
        usbPrinterManager.requestUsbPermission()
    }

    fun softResetPrinter() {
        viewModelScope.launch {
            val res = usbPrinterManager.performSoftReset()
            _snackbarMessage.value = res.fold(
                onSuccess = { "Soft reset sent to USB printer." },
                onFailure = { it.message ?: "Soft reset failed." }
            )
        }
    }

    fun setProtocolOverride(protocol: DriverProtocol) {
        val vidPid = activeVidPid() ?: return
        viewModelScope.launch {
            preferencesStore.setProtocolOverride(vidPid, protocol)
        }
    }

    fun setColorOverride(mode: ColorOverrideMode) {
        val vidPid = activeVidPid() ?: return
        viewModelScope.launch {
            preferencesStore.setColorOverride(vidPid, mode)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepo.clearHistory()
            _snackbarMessage.value = "Print history cleared."
        }
    }

    fun loadPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val limited = uris.take(20)
            val decoded = limited.mapIndexedNotNull { idx, uri ->
                val result = ResilientPhotoDecoder.decode(appContext.contentResolver, uri, maxDimensionPx = 2200)
                result?.let {
                    LoadedPhotoItem(
                        uri = uri,
                        bitmap = it.bitmap,
                        label = resolveDisplayName(uri) ?: "Photo ${idx + 1}"
                    )
                }
            }
            if (decoded.isEmpty()) {
                _snackbarMessage.value = "Unable to decode selected image(s)."
                return@launch
            }
            _currentPreviewPage.value = 0
            _tuning.value = PrintTuningOptions()
            _document.value = StudioDocument.PhotoBatch(decoded)
            _activeTab.value = AppTab.PRINT_STUDIO
            refreshPreview()
        }
    }

    fun loadPdf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                try {
                    appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }

                val pageCount = appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { it.pageCount }
                } ?: 0

                if (pageCount <= 0) {
                    _snackbarMessage.value = "Selected PDF has no printable pages."
                    return@launch
                }

                val name = resolveDisplayName(uri) ?: "Document.pdf"
                _currentPreviewPage.value = 0
                _pdfOptions.value = _pdfOptions.value.copy(currentPageIndex = 0)
                _tuning.value = PrintTuningOptions()
                _document.value = StudioDocument.PdfDoc(uri, name, pageCount)
                _activeTab.value = AppTab.PRINT_STUDIO
                refreshPreview()
            } catch (e: Exception) {
                _snackbarMessage.value = "Could not open PDF: ${e.message}"
            }
        }
    }

    fun loadDiagnosticSheet() {
        _currentPreviewPage.value = 0
        _document.value = StudioDocument.DiagnosticSheet
        _activeTab.value = AppTab.PRINT_STUDIO
        refreshPreview()
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val type = intent.type.orEmpty()

        when (action) {
            Intent.ACTION_SEND -> {
                val uri = getParcelableUri(intent, Intent.EXTRA_STREAM) ?: return
                if (type.contains("pdf")) loadPdf(uri) else if (type.startsWith("image/")) loadPhotos(listOf(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getParcelableUriList(intent, Intent.EXTRA_STREAM)
                if (uris.isNotEmpty()) loadPhotos(uris)
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val mime = appContext.contentResolver.getType(uri) ?: type
                if (mime.contains("pdf") || uri.toString().lowercase().endsWith(".pdf")) {
                    loadPdf(uri)
                } else {
                    loadPhotos(listOf(uri))
                }
            }
        }
    }

    fun updateTuning(transform: (PrintTuningOptions) -> PrintTuningOptions) {
        _tuning.value = transform(_tuning.value)
        refreshPreview()
    }

    fun rotate90() {
        _tuning.value = _tuning.value.copy(
            rotationDegrees = (_tuning.value.rotationDegrees + 90) % 360
        )
        refreshPreview()
    }

    fun updateCropRect(crop: NormalizedCropRect) {
        _tuning.value = _tuning.value.copy(cropRect = crop.normalized())
        refreshPreview()
    }

    fun updatePdfOptions(transform: (PdfPrintOptions) -> PdfPrintOptions) {
        _pdfOptions.value = transform(_pdfOptions.value)
        _currentPreviewPage.value = _pdfOptions.value.currentPageIndex
        refreshPreview()
    }

    fun stepPreviewPage(delta: Int) {
        when (val doc = _document.value) {
            is StudioDocument.PdfDoc -> {
                val next = (_pdfOptions.value.currentPageIndex + delta).coerceIn(0, doc.totalPages - 1)
                _pdfOptions.value = _pdfOptions.value.copy(currentPageIndex = next)
                _currentPreviewPage.value = next
                refreshPreview()
            }
            is StudioDocument.PhotoBatch -> {
                val total = (_photoSheetPlan.value?.pages?.size ?: 1).coerceAtLeast(1)
                val next = (_currentPreviewPage.value + delta).coerceIn(0, total - 1)
                _currentPreviewPage.value = next
                refreshPreview()
            }
            else -> Unit
        }
    }

    fun updatePhotoLayout(newOptions: PhotoLayoutOptions) {
        _photoLayout.value = newOptions
        _currentPreviewPage.value = 0
        refreshPreview()
    }

    fun isEffectiveMonochrome(): Boolean {
        val printerColor = (usbState.value as? UsbPrinterState.Ready)?.profile?.effectiveColorSupported ?: true
        return when (_tuning.value.outputMode) {
            OutputMode.AUTO -> !printerColor
            OutputMode.COLOR -> false
            OutputMode.MONO -> true
        }
    }

    fun getPrimarySourceBitmapForCrop(): Bitmap? {
        return when (val doc = _document.value) {
            is StudioDocument.PhotoBatch -> doc.items.firstOrNull()?.bitmap
            else -> _previewBitmap.value
        }
    }

    fun refreshPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            val doc = _document.value
            val tuningVal = _tuning.value
            val effectiveMono = isEffectiveMonochrome()
            val activeProtocol = (usbState.value as? UsbPrinterState.Ready)?.profile?.effectiveProtocol
            val previewMarginMm = if (activeProtocol == null || PrintJobStreamer.usesSafeMargin(activeProtocol)) {
                tuningVal.safeMarginMm
            } else {
                0f
            }

            when (doc) {
                StudioDocument.Empty -> {
                    _photoSheetPlan.value = null
                    _previewBitmap.value = null
                }

                is StudioDocument.PdfDoc -> {
                    _photoSheetPlan.value = null
                    val opts = _pdfOptions.value
                    val pageIdx = opts.currentPageIndex.coerceIn(0, doc.totalPages - 1)
                    val pfd = appContext.contentResolver.openFileDescriptor(doc.uri, "r") ?: return@launch
                    pfd.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            renderer.openPage(pageIdx).use { page ->
                                val (mmW, mmH) = BandCompositor.resolvePdfPaperDimensionsMm(
                                    paperSize = opts.paperSize,
                                    orientation = opts.orientation,
                                    pdfPageWidthPt = page.width,
                                    pdfPageHeightPt = page.height
                                )
                                val preview = BandCompositor.renderPreviewBitmap(
                                    sourcePixelsRenderer = { w, h ->
                                        BandCompositor.renderPdfBandArgb(
                                            pdfPage = page,
                                            pageWidthPx = w,
                                            pageHeightPx = h,
                                            bandTopY = 0,
                                            bandHeightPx = h,
                                            scaleMode = opts.scaleMode,
                                            tuning = tuningVal,
                                            paperWidthMm = mmW,
                                            paperHeightMm = mmH,
                                            safeMarginMm = previewMarginMm
                                        )
                                    },
                                    paperWidthMm = mmW,
                                    paperHeightMm = mmH,
                                    previewMaxWidthPx = 680,
                                    tuning = tuningVal,
                                    effectiveMonochrome = effectiveMono
                                )
                                _previewBitmap.value = preview
                            }
                        }
                    }
                }

                is StudioDocument.PhotoBatch -> {
                    val opts = _photoLayout.value
                    val aspects = doc.items.map {
                        it.bitmap.width.toFloat() / it.bitmap.height.coerceAtLeast(1).toFloat()
                    }
                    val plan = PhotoLayoutGeometry.computeSheetPlan(
                        photoCount = doc.items.size,
                        options = opts,
                        photoAspectRatios = aspects
                    )
                    _photoSheetPlan.value = plan
                    val safePageIdx = _currentPreviewPage.value.coerceIn(0, (plan.pages.size - 1).coerceAtLeast(0))
                    _currentPreviewPage.value = safePageIdx
                    val assignment = plan.pages.getOrNull(safePageIdx) ?: return@launch
                    val bitmaps = doc.items.map { it.bitmap }
                    val previewDpi = ((680f / plan.paperWidthMm) * 25.4f).toInt().coerceAtLeast(72)
                    val preview = BandCompositor.renderPreviewBitmap(
                        sourcePixelsRenderer = { w, h ->
                            BandCompositor.renderPhotoSheetBandArgb(
                                sheetPlan = plan,
                                pageAssignment = assignment,
                                photos = bitmaps,
                                pageWidthPx = w,
                                pageHeightPx = h,
                                dpiX = previewDpi,
                                dpiY = previewDpi,
                                bandTopY = 0,
                                bandHeightPx = h,
                                cutGuideStyle = opts.cutGuideStyle,
                                tuning = tuningVal,
                                autoRotateToCell = opts.autoRotateToCell,
                                safeMarginMm = previewMarginMm
                            )
                        },
                        paperWidthMm = plan.paperWidthMm,
                        paperHeightMm = plan.paperHeightMm,
                        previewMaxWidthPx = 680,
                        tuning = tuningVal,
                        effectiveMonochrome = effectiveMono
                    )
                    _previewBitmap.value = preview
                }

                StudioDocument.DiagnosticSheet -> {
                    _photoSheetPlan.value = null
                    val meta = buildDiagnosticMetadata(PaperSize.A4, PrintQuality.STANDARD_600)
                    val preview = BandCompositor.renderPreviewBitmap(
                        sourcePixelsRenderer = { w, h ->
                            BandCompositor.renderDiagnosticBandArgb(
                                metadata = meta,
                                pageWidthPx = w,
                                pageHeightPx = h,
                                bandTopY = 0,
                                bandHeightPx = h,
                                paperWidthMm = PaperSize.A4.widthMm,
                                paperHeightMm = PaperSize.A4.heightMm,
                                safeMarginMm = previewMarginMm
                            )
                        },
                        paperWidthMm = PaperSize.A4.widthMm,
                        paperHeightMm = PaperSize.A4.heightMm,
                        previewMaxWidthPx = 680,
                        tuning = tuningVal,
                        effectiveMonochrome = effectiveMono
                    )
                    _previewBitmap.value = preview
                }
            }
        }
    }

    fun startPrint() {
        val state = usbState.value
        if (state is UsbPrinterState.PermissionRequired) {
            usbPrinterManager.requestUsbPermission()
            return
        }
        if (state !is UsbPrinterState.Ready) {
            _snackbarMessage.value = "Connect a USB OTG printer before printing."
            return
        }
        if (state.profile.effectiveProtocol == DriverProtocol.UNSUPPORTED) {
            val msg = state.profile.unverifiedLanguageWarning ?: PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE
            _lastPrintError.value = msg
            _snackbarMessage.value = msg
            return
        }
        if (_document.value is StudioDocument.Empty) {
            _snackbarMessage.value = "Select photos, open a PDF, or load a Diagnostic Sheet first."
            return
        }
        // Require a 1-inch test page verification before the first full job on any printer other
        // than the hardware-verified Samsung SCX-3200 (0x04E8:0x3441).
        if (requiresTestPageBeforeFullJob(state.deviceInfo, _deviceOverride.value.testPageVerified)) {
            _showFallbackConfirmDialog.value = true
            return
        }
        executePrintDocument(_document.value, state)
    }

    fun printTestPage() {
        val state = usbState.value
        if (state is UsbPrinterState.PermissionRequired) {
            usbPrinterManager.requestUsbPermission()
            return
        }
        if (state !is UsbPrinterState.Ready) {
            _snackbarMessage.value = "Connect a USB OTG printer to print the 1-inch test page."
            return
        }
        if (state.profile.effectiveProtocol == DriverProtocol.UNSUPPORTED) {
            val msg = state.profile.unverifiedLanguageWarning ?: PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE
            _lastPrintError.value = msg
            _snackbarMessage.value = msg
            return
        }
        _showFallbackConfirmDialog.value = false
        executeTestPageJob(state)
    }

    fun dismissFallbackConfirmDialog() {
        _showFallbackConfirmDialog.value = false
    }

    fun printTinyProbeFromFallback() {
        printTestPage()
    }

    fun openPrinterSettingsFromFallback() {
        _showFallbackConfirmDialog.value = false
        _activeTab.value = AppTab.PRINTER_HISTORY
    }

    fun printDiagnosticSheetNow() {
        val state = usbState.value
        if (state is UsbPrinterState.PermissionRequired) {
            usbPrinterManager.requestUsbPermission()
            return
        }
        if (state !is UsbPrinterState.Ready) {
            _snackbarMessage.value = "Connect a USB OTG printer to print the diagnostic sheet."
            return
        }
        if (state.profile.effectiveProtocol == DriverProtocol.UNSUPPORTED) {
            val msg = state.profile.unverifiedLanguageWarning ?: PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE
            _lastPrintError.value = msg
            _snackbarMessage.value = msg
            return
        }
        if (requiresTestPageBeforeFullJob(state.deviceInfo, _deviceOverride.value.testPageVerified)) {
            _document.value = StudioDocument.DiagnosticSheet
            _showFallbackConfirmDialog.value = true
            return
        }
        executePrintDocument(StudioDocument.DiagnosticSheet, state)
    }

    fun cancelPrint() {
        printJob?.cancel()
    }

    private fun executeTestPageJob(state: UsbPrinterState.Ready) {
        if (_printProgress.value.isPrinting) return
        _lastPrintError.value = null
        val bytesCounter = AtomicLong(0L)
        val protocol = state.profile.effectiveProtocol
        val opened = usbPrinterManager.openPrintTransport(protocol) { written ->
            val curr = bytesCounter.addAndGet(written.toLong())
            _printProgress.value = _printProgress.value.copy(bytesSent = curr)
        }
        if (opened == null) {
            val err = "Failed to claim USB printer interface."
            _lastPrintError.value = err
            _snackbarMessage.value = err
            return
        }
        val (transport, cleanup) = opened
        val effectiveMono = isEffectiveMonochrome()
        val safeMarginMm = _tuning.value.safeMarginMm

        printJob = viewModelScope.launch(Dispatchers.IO) {
            var status = PrintJobStatus.COMPLETED
            try {
                PrintJobStreamer.streamTestPageJob(
                    transport = transport,
                    printerName = state.profile.makeAndModel,
                    protocol = protocol,
                    effectiveMonochrome = effectiveMono,
                    paperSize = PaperSize.A4,
                    safeMarginMm = safeMarginMm,
                    onProgress = { prog ->
                        _printProgress.value = prog.copy(bytesSent = bytesCounter.get())
                    }
                )
                preferencesStore.setTestPageVerified(state.deviceInfo.vidPidKey, true)
                _snackbarMessage.value =
                    "1-inch test page verified (${formatBytes(bytesCounter.get())}). Full print jobs are now unlocked for ${state.profile.makeAndModel}."
            } catch (ce: kotlinx.coroutines.CancellationException) {
                status = PrintJobStatus.CANCELLED
                _snackbarMessage.value = "Test page cancelled."
            } catch (e: Exception) {
                status = PrintJobStatus.FAILED
                val errMsg = e.message ?: "Test page failed."
                _lastPrintError.value = errMsg
                _snackbarMessage.value = errMsg
            } finally {
                cleanup()
                historyRepo.recordJob(
                    PrintHistoryEntity(
                        documentTitle = "1-Inch Test Page",
                        pageCount = 1,
                        copies = 1,
                        paperSize = PaperSize.A4.displayName,
                        resolvedProtocol = "${protocol.displayName} (Test Page)",
                        colorMode = if (effectiveMono) "Monochrome" else "Color",
                        bytesSent = bytesCounter.get(),
                        status = status.displayName
                    )
                )
                _printProgress.value = PrintJobProgress()
            }
        }
    }

    private fun executePrintDocument(doc: StudioDocument, state: UsbPrinterState.Ready) {
        if (_printProgress.value.isPrinting) return
        _lastPrintError.value = null
        val bytesCounter = AtomicLong(0L)
        val protocol = state.profile.effectiveProtocol
        val opened = usbPrinterManager.openPrintTransport(protocol) { written ->
            val curr = bytesCounter.addAndGet(written.toLong())
            _printProgress.value = _printProgress.value.copy(bytesSent = curr)
        }
        if (opened == null) {
            val err = "Failed to claim USB printer interface."
            _lastPrintError.value = err
            _snackbarMessage.value = err
            return
        }
        val (transport, cleanup) = opened

        val tuningVal = _tuning.value
        val effectiveMono = isEffectiveMonochrome()

        printJob = viewModelScope.launch(Dispatchers.IO) {
            var title = "Print Job"
            var pageCount = 1
            var copies = 1
            var paperName = PaperSize.A4.displayName
            var status = PrintJobStatus.COMPLETED

            try {
                _printProgress.value = PrintJobProgress(isPrinting = true, phaseLabel = "Preparing USB stream...")
                when (doc) {
                    is StudioDocument.PdfDoc -> {
                        val opts = _pdfOptions.value
                        val pages = PageRangeParser.resolvePages(
                            totalPages = doc.totalPages,
                            mode = opts.pageRangeMode,
                            currentPageIndex = opts.currentPageIndex,
                            customText = opts.customRangeText
                        )
                        if (pages.isEmpty()) error("No pages match selected PDF range.")
                        title = doc.title
                        pageCount = pages.size
                        copies = opts.copies
                        paperName = opts.paperSize.displayName

                        val isUntunedFullPdf = pages.size == doc.totalPages &&
                            tuningVal.cropRect.isFull &&
                            tuningVal.rotationDegrees == 0 &&
                            tuningVal.brightness == 0f &&
                            tuningVal.contrast == 1f &&
                            !effectiveMono &&
                            (protocol == DriverProtocol.IPP_USB_PDF || protocol == DriverProtocol.PDF_DIRECT)

                        PrintJobStreamer.streamPdfJob(
                            transport = transport,
                            contentResolver = appContext.contentResolver,
                            pdfUri = doc.uri,
                            pdfPageIndices = pages,
                            jobName = title,
                            protocol = protocol,
                            effectiveMonochrome = effectiveMono,
                            paperSize = opts.paperSize,
                            orientation = opts.orientation,
                            scaleMode = opts.scaleMode,
                            quality = opts.quality,
                            copies = copies,
                            tuning = tuningVal,
                            canUseRawPdfPassthrough = isUntunedFullPdf,
                            onProgress = { prog ->
                                _printProgress.value = prog.copy(bytesSent = bytesCounter.get())
                            }
                        )
                    }

                    is StudioDocument.PhotoBatch -> {
                        val opts = _photoLayout.value
                        val plan = _photoSheetPlan.value ?: PhotoLayoutGeometry.computeSheetPlan(
                            photoCount = doc.items.size,
                            options = opts
                        )
                        title = if (doc.items.size == 1) doc.items[0].label else "${doc.items.size} Photos Sheet"
                        pageCount = plan.pages.size.coerceAtLeast(1)
                        copies = opts.copies
                        paperName = opts.paperSize.displayName

                        PrintJobStreamer.streamPhotoBatchJob(
                            transport = transport,
                            photos = doc.items.map { it.bitmap },
                            sheetPlan = plan,
                            jobName = title,
                            protocol = protocol,
                            effectiveMonochrome = effectiveMono,
                            paperSize = opts.paperSize,
                            quality = opts.quality,
                            copies = copies,
                            cutGuideStyle = opts.cutGuideStyle,
                            autoRotateToCell = opts.autoRotateToCell,
                            tuning = tuningVal,
                            onProgress = { prog ->
                                _printProgress.value = prog.copy(bytesSent = bytesCounter.get())
                            }
                        )
                    }

                    StudioDocument.DiagnosticSheet -> {
                        title = "Diagnostic Test Sheet"
                        pageCount = 1
                        copies = 1
                        paperName = PaperSize.A4.displayName
                        val meta = buildDiagnosticMetadata(PaperSize.A4, PrintQuality.STANDARD_600)
                        PrintJobStreamer.streamDiagnosticSheetJob(
                            transport = transport,
                            metadata = meta,
                            protocol = protocol,
                            effectiveMonochrome = effectiveMono,
                            paperSize = PaperSize.A4,
                            quality = PrintQuality.STANDARD_600,
                            onProgress = { prog ->
                                _printProgress.value = prog.copy(bytesSent = bytesCounter.get())
                            }
                        )
                    }

                    StudioDocument.Empty -> Unit
                }
                _snackbarMessage.value = "Print job sent ($pageCount page(s), ${formatBytes(bytesCounter.get())})."
            } catch (ce: kotlinx.coroutines.CancellationException) {
                status = PrintJobStatus.CANCELLED
                _snackbarMessage.value = "Print job cancelled."
            } catch (e: Exception) {
                status = PrintJobStatus.FAILED
                val errMsg = e.message ?: "Print failed"
                _lastPrintError.value = errMsg
                _snackbarMessage.value = errMsg
            } finally {
                cleanup()
                historyRepo.recordJob(
                    PrintHistoryEntity(
                        documentTitle = title,
                        pageCount = pageCount,
                        copies = copies,
                        paperSize = paperName,
                        resolvedProtocol = protocol.displayName,
                        colorMode = if (effectiveMono) "Monochrome" else "Color",
                        bytesSent = bytesCounter.get(),
                        status = status.displayName
                    )
                )
                _printProgress.value = PrintJobProgress()
            }
        }
    }

    private fun buildDiagnosticMetadata(paperSize: PaperSize, quality: PrintQuality): DiagnosticMetadata {
        val state = usbState.value
        return if (state is UsbPrinterState.Ready) {
            DiagnosticMetadata(
                printerName = state.profile.makeAndModel,
                vidPid = state.deviceInfo.vidPidKey,
                activeInterface = state.profile.activeInterfaceLabel,
                resolvedProtocol = state.profile.effectiveProtocol.displayName,
                colorSupported = !isEffectiveMonochrome(),
                ieee1284Cmd = state.ieee1284.commandSetTokens.joinToString(", "),
                paperLabel = paperSize.displayName,
                dpiLabel = "${quality.dpiX}×${quality.dpiY} DPI"
            )
        } else {
            DiagnosticMetadata(
                printerName = "Offline Preview (No Printer Attached)",
                vidPid = "----:----",
                activeInterface = "Unplugged",
                resolvedProtocol = "Auto-Detected",
                colorSupported = !isEffectiveMonochrome(),
                ieee1284Cmd = "",
                paperLabel = paperSize.displayName,
                dpiLabel = "${quality.dpiX}×${quality.dpiY} DPI"
            )
        }
    }

    private fun activeVidPid(): String? {
        return when (val s = usbState.value) {
            is UsbPrinterState.Ready -> s.deviceInfo.vidPidKey
            is UsbPrinterState.PermissionRequired -> s.deviceInfo.vidPidKey
            is UsbPrinterState.Probing -> s.deviceInfo.vidPidKey
            is UsbPrinterState.Error -> s.deviceInfo?.vidPidKey
            UsbPrinterState.Disconnected -> null
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun getParcelableUri(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }

    private fun getParcelableUriList(intent: Intent, key: String): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
    }

    companion object {
        fun requiresTestPageBeforeFullJob(deviceInfo: UsbDeviceInfo, testPageVerified: Boolean): Boolean {
            return !PrinterCapabilityResolver.isHardwareVerifiedPrinter(deviceInfo) && !testPageVerified
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
                bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}
