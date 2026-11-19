package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.document.PhotoSheetPlan
import com.example.model.DriverProtocol
import com.example.model.NormalizedCropRect
import com.example.model.PdfPrintOptions
import com.example.model.PhotoLayoutOptions
import com.example.model.PrintJobProgress
import com.example.model.PrintTuningOptions
import com.example.model.UsbPrinterState
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.LocalWindowWidthSizeClass
import com.example.usb.PrinterCapabilityResolver
import com.example.viewmodel.StudioDocument

@Suppress("UNUSED_PARAMETER")
@Composable
fun PrintStudioScreen(
    usbState: UsbPrinterState,
    document: StudioDocument,
    tuning: PrintTuningOptions,
    pdfOptions: PdfPrintOptions,
    photoLayout: PhotoLayoutOptions,
    photoSheetPlan: PhotoSheetPlan?,
    previewBitmap: Bitmap?,
    currentPreviewPage: Int,
    effectiveMonochrome: Boolean,
    printProgress: PrintJobProgress,
    primaryCropBitmap: Bitmap?,
    testPageVerified: Boolean = false,
    lastPrintError: String? = null,
    onDismissPrintError: () -> Unit = {},
    onScanUsb: () -> Unit,
    onGrantPermission: () -> Unit,
    onPhotosPicked: (List<android.net.Uri>) -> Unit,
    onPdfPicked: (android.net.Uri) -> Unit,
    onLoadDiagnosticSheet: () -> Unit,
    onStepPage: (Int) -> Unit,
    onUpdateTuning: ((PrintTuningOptions) -> PrintTuningOptions) -> Unit,
    onRotate90: () -> Unit,
    onUpdateCrop: (NormalizedCropRect) -> Unit,
    onUpdatePdfOptions: ((PdfPrintOptions) -> PdfPrintOptions) -> Unit,
    onUpdatePhotoLayout: (PhotoLayoutOptions) -> Unit,
    onStartPrint: () -> Unit,
    onPrintTestPage: () -> Unit = {},
    onCancelPrint: () -> Unit,
    showFallbackConfirmDialog: Boolean = false,
    onDismissFallbackDialog: () -> Unit = {},
    onPrintTinyProbe: () -> Unit = {},
    onOpenPrinterSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    val isWideScreen = LocalWindowWidthSizeClass.current != WindowWidthSizeClass.Compact
    var showCropDialog by remember { mutableStateOf(false) }
    var showPhotoLayoutDialog by remember { mutableStateOf(false) }
    var showPdfOptionsSheet by remember { mutableStateOf(false) }
    var tuningExpanded by remember { mutableStateOf(false) }
    val isUnsupportedPrinter = usbState is UsbPrinterState.Ready &&
        usbState.profile.effectiveProtocol == DriverProtocol.UNSUPPORTED

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) onPhotosPicked(uris)
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPdfPicked(uri)
    }

    if (showCropDialog) {
        CropAreaDialog(
            sourceBitmap = primaryCropBitmap,
            initialCrop = tuning.cropRect,
            onDismiss = { showCropDialog = false },
            onApply = {
                onUpdateCrop(it)
                showCropDialog = false
            }
        )
    }

    if (showPhotoLayoutDialog && document is StudioDocument.PhotoBatch) {
        val aspects = remember(document.items) {
            document.items.map { it.bitmap.width.toFloat() / it.bitmap.height.coerceAtLeast(1).toFloat() }
        }
        PhotoLayoutStudioDialog(
            photoCount = document.items.size,
            photoAspectRatios = aspects,
            initialOptions = photoLayout,
            onDismiss = { showPhotoLayoutDialog = false },
            onApply = {
                onUpdatePhotoLayout(it)
                showPhotoLayoutDialog = false
            }
        )
    }

    if (showPdfOptionsSheet && document is StudioDocument.PdfDoc) {
        PdfOptionsBottomSheet(
            pdfOptions = pdfOptions,
            onUpdatePdfOptions = onUpdatePdfOptions,
            onDismiss = { showPdfOptionsSheet = false }
        )
    }

    if (showFallbackConfirmDialog && usbState is UsbPrinterState.Ready) {
        val profile = usbState.profile
        AlertDialog(
            onDismissRequest = onDismissFallbackDialog,
            containerColor = studio.cardSurface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "1-Inch Test Page Required",
                    style = MaterialTheme.typography.titleLarge,
                    color = studio.primaryInk
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = profile.unverifiedLanguageWarning ?: profile.resolutionReason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = studio.primaryInk
                    )
                    Text(
                        text = "Before sending the first full job on this printer, print the 1-inch (300 px @ 300 DPI) test strip using ${profile.effectiveProtocol.displayName} so you can verify output cleanly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.mutedGraphite
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPrintTinyProbe,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = studio.activeAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("fallback_probe_button")
                    ) {
                        Text(stringResource(R.string.action_print_test_page))
                    }
                    OutlinedButton(
                        onClick = onOpenPrinterSettings,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("fallback_open_settings_button")
                    ) {
                        Text("Choose Language in Printer Tab")
                    }
                    TextButton(
                        onClick = onDismissFallbackDialog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("fallback_cancel_button")
                    ) {
                        Text("Cancel", color = studio.mutedGraphite)
                    }
                }
            }
        )
    }

    val launchPhotos = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    val launchPdf = {
        pdfPickerLauncher.launch(arrayOf("application/pdf"))
    }

    if (isWideScreen) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Left pane (~45%): scrollable controls
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PrinterControlsHeaderSection(
                    usbState = usbState,
                    effectiveMonochrome = effectiveMonochrome,
                    testPageVerified = testPageVerified,
                    isUnsupportedPrinter = isUnsupportedPrinter,
                    lastPrintError = lastPrintError,
                    isPrinting = printProgress.isPrinting,
                    onScanUsb = onScanUsb,
                    onGrantPermission = onGrantPermission,
                    onOpenPrinterSettings = onOpenPrinterSettings,
                    onDismissPrintError = onDismissPrintError,
                    onPrintTestPage = onPrintTestPage
                )

                DocumentSourceRow(
                    onPickPhotos = launchPhotos,
                    onPickPdf = launchPdf
                )

                if (document !is StudioDocument.Empty) {
                    PrePrintTuningCard(
                        tuning = tuning,
                        effectiveMonochrome = effectiveMonochrome,
                        expanded = tuningExpanded,
                        onToggleExpanded = { tuningExpanded = !tuningExpanded },
                        onUpdateTuning = onUpdateTuning,
                        onOpenCrop = { showCropDialog = true },
                        onRotate90 = onRotate90
                    )

                    DocumentOptionsSection(
                        document = document,
                        pdfOptions = pdfOptions,
                        photoLayout = photoLayout,
                        photoSheetPlan = photoSheetPlan,
                        onOpenPdfOptionsSheet = { showPdfOptionsSheet = true },
                        onOpenPhotoLayoutStudio = { showPhotoLayoutDialog = true },
                        onUpdatePhotoLayout = onUpdatePhotoLayout
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Right pane (~55%): vertically centered preview with Print button pinned below
            Column(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (document is StudioDocument.Empty) {
                        EmptyStudioPreviewCard()
                    } else {
                        LivePaperPreviewCard(
                            document = document,
                            tuning = tuning,
                            photoSheetPlan = photoSheetPlan,
                            previewBitmap = previewBitmap,
                            currentPreviewPage = currentPreviewPage,
                            effectiveMonochrome = effectiveMonochrome,
                            onStepPage = onStepPage,
                            isWidePane = true
                        )
                    }
                }

                if (document !is StudioDocument.Empty) {
                    PrintActionContent(
                        usbState = usbState,
                        isUnsupportedPrinter = isUnsupportedPrinter,
                        printProgress = printProgress,
                        onStartPrint = onStartPrint,
                        onCancelPrint = onCancelPrint
                    )
                }
            }
        }
    } else {
        // Phone: single scrollable column with pinned Print Now bottom bar respecting system insets
        Column(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PrinterControlsHeaderSection(
                    usbState = usbState,
                    effectiveMonochrome = effectiveMonochrome,
                    testPageVerified = testPageVerified,
                    isUnsupportedPrinter = isUnsupportedPrinter,
                    lastPrintError = lastPrintError,
                    isPrinting = printProgress.isPrinting,
                    onScanUsb = onScanUsb,
                    onGrantPermission = onGrantPermission,
                    onOpenPrinterSettings = onOpenPrinterSettings,
                    onDismissPrintError = onDismissPrintError,
                    onPrintTestPage = onPrintTestPage
                )

                DocumentSourceRow(
                    onPickPhotos = launchPhotos,
                    onPickPdf = launchPdf
                )

                if (document is StudioDocument.Empty) {
                    EmptyStudioPreviewCard()
                } else {
                    LivePaperPreviewCard(
                        document = document,
                        tuning = tuning,
                        photoSheetPlan = photoSheetPlan,
                        previewBitmap = previewBitmap,
                        currentPreviewPage = currentPreviewPage,
                        effectiveMonochrome = effectiveMonochrome,
                        onStepPage = onStepPage,
                        isWidePane = false
                    )

                    PrePrintTuningCard(
                        tuning = tuning,
                        effectiveMonochrome = effectiveMonochrome,
                        expanded = tuningExpanded,
                        onToggleExpanded = { tuningExpanded = !tuningExpanded },
                        onUpdateTuning = onUpdateTuning,
                        onOpenCrop = { showCropDialog = true },
                        onRotate90 = onRotate90
                    )

                    DocumentOptionsSection(
                        document = document,
                        pdfOptions = pdfOptions,
                        photoLayout = photoLayout,
                        photoSheetPlan = photoSheetPlan,
                        onOpenPdfOptionsSheet = { showPdfOptionsSheet = true },
                        onOpenPhotoLayoutStudio = { showPhotoLayoutDialog = true },
                        onUpdatePhotoLayout = onUpdatePhotoLayout
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (document !is StudioDocument.Empty) {
                Surface(
                    color = studio.cardSurface,
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = studio.hairlineBorder)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            PrintActionContent(
                                usbState = usbState,
                                isUnsupportedPrinter = isUnsupportedPrinter,
                                printProgress = printProgress,
                                onStartPrint = onStartPrint,
                                onCancelPrint = onCancelPrint
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrinterControlsHeaderSection(
    usbState: UsbPrinterState,
    effectiveMonochrome: Boolean,
    testPageVerified: Boolean,
    isUnsupportedPrinter: Boolean,
    lastPrintError: String?,
    isPrinting: Boolean,
    onScanUsb: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenPrinterSettings: () -> Unit,
    onDismissPrintError: () -> Unit,
    onPrintTestPage: () -> Unit
) {
    val studio = LocalStudioColors.current

    PrinterStatusHeaderCard(
        usbState = usbState,
        effectiveMonochrome = effectiveMonochrome,
        testPageVerified = testPageVerified,
        onScanUsb = onScanUsb,
        onGrantPermission = onGrantPermission
    )

    if (isUnsupportedPrinter && usbState is UsbPrinterState.Ready) {
        StudioSectionCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(studio.crimsonSoft)
                    .padding(16.dp)
                    .testTag("unsupported_printer_banner"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Unsupported Printer Language",
                    style = MaterialTheme.typography.titleSmall,
                    color = studio.crimson,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = usbState.profile.unverifiedLanguageWarning
                        ?: PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE,
                    style = MaterialTheme.typography.bodySmall,
                    color = studio.crimson
                )
                OutlinedButton(
                    onClick = onOpenPrinterSettings,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, studio.crimson),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("unsupported_open_settings_button")
                ) {
                    Text("Select Manual Protocol Override", color = studio.crimson)
                }
            }
        }
    }

    if (!lastPrintError.isNullOrBlank()) {
        StudioSectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(studio.crimsonSoft)
                    .padding(12.dp)
                    .testTag("print_error_banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = lastPrintError,
                    style = MaterialTheme.typography.bodySmall,
                    color = studio.crimson,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismissPrintError,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_print_error_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss print error",
                        tint = studio.crimson
                    )
                }
            }
        }
    }

    if (usbState is UsbPrinterState.Ready && !isUnsupportedPrinter) {
        OutlinedButton(
            onClick = onPrintTestPage,
            enabled = !isPrinting,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, studio.activeAccent),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("print_test_page_button")
        ) {
            Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_print_test_page))
        }
    }
}

@Composable
private fun DocumentOptionsSection(
    document: StudioDocument,
    pdfOptions: PdfPrintOptions,
    photoLayout: PhotoLayoutOptions,
    photoSheetPlan: PhotoSheetPlan?,
    onOpenPdfOptionsSheet: () -> Unit,
    onOpenPhotoLayoutStudio: () -> Unit,
    onUpdatePhotoLayout: (PhotoLayoutOptions) -> Unit
) {
    when (document) {
        is StudioDocument.PdfDoc -> {
            PdfOptionsSummaryCard(
                pdfOptions = pdfOptions,
                onOpenSheet = onOpenPdfOptionsSheet
            )
        }

        is StudioDocument.PhotoBatch -> {
            PhotoLayoutSummaryCard(
                photoCount = document.items.size,
                photoLayout = photoLayout,
                photoSheetPlan = photoSheetPlan,
                onOpenLayoutStudio = onOpenPhotoLayoutStudio,
                onCopiesChange = { c -> onUpdatePhotoLayout(photoLayout.copy(copies = c)) }
            )
        }

        else -> Unit
    }
}
