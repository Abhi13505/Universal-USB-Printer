package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.document.PhotoSheetPlan
import com.example.model.DitherMode
import com.example.model.NormalizedCropRect
import com.example.model.OutputMode
import com.example.model.PageOrientation
import com.example.model.PageRangeMode
import com.example.model.PaperSize
import com.example.model.PdfPrintOptions
import com.example.model.PhotoLayoutOptions
import com.example.model.PrintJobProgress
import com.example.model.PrintQuality
import com.example.model.PrintTuningOptions
import com.example.model.ScaleMode
import com.example.model.UsbPrinterState
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MonoTextStyle
import com.example.viewmodel.PrintStudioViewModel
import com.example.viewmodel.StudioDocument

@OptIn(ExperimentalLayoutApi::class)
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
    onCancelPrint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    var showCropDialog by remember { mutableStateOf(false) }
    var showPhotoLayoutDialog by remember { mutableStateOf(false) }
    var tuningExpanded by remember { mutableStateOf(true) }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Clean Printer Status Header (Zero driver jargon on main screen)
        PrinterStatusHeaderCard(
            usbState = usbState,
            effectiveMonochrome = effectiveMonochrome,
            onScanUsb = onScanUsb,
            onGrantPermission = onGrantPermission
        )

        // 2. Document Source Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("pick_photos_button")
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Photos", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            FilledTonalButton(
                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("open_pdf_button")
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("PDF", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            OutlinedButton(
                onClick = onLoadDiagnosticSheet,
                border = BorderStroke(1.dp, studio.hairlineBorder),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("load_diagnostic_button")
            ) {
                Icon(Icons.Filled.Print, contentDescription = "Load diagnostic test sheet", modifier = Modifier.size(18.dp))
            }
        }

        // 3. Live Paper Canvas or Empty State
        if (document is StudioDocument.Empty) {
            StudioSectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(studio.activeAccentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Print,
                            contentDescription = null,
                            tint = studio.activeAccent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.empty_studio_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = studio.primaryInk,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.empty_studio_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = studio.mutedGraphite,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Live Paper Canvas Card
            StudioSectionCard {
                val (docTitle, totalPreviewPages) = when (document) {
                    is StudioDocument.PdfDoc -> document.title to document.totalPages
                    is StudioDocument.PhotoBatch -> {
                        val sheets = photoSheetPlan?.pages?.size ?: 1
                        val label = if (document.items.size == 1) document.items[0].label else "${document.items.size} Photos"
                        label to sheets
                    }
                    StudioDocument.DiagnosticSheet -> "Diagnostic Test Sheet" to 1
                    StudioDocument.Empty -> "" to 1
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = docTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (effectiveMonochrome) {
                                "Preview: 1-bit ${tuning.ditherMode.displayName}"
                            } else {
                                "Preview: Full Color"
                            },
                            style = MonoTextStyle,
                            color = studio.mutedGraphite
                        )
                    }

                    if (totalPreviewPages > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onStepPage(-1) },
                                enabled = currentPreviewPage > 0,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("preview_prev_page")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                            }
                            Text(
                                text = "${currentPreviewPage + 1}/$totalPreviewPages",
                                style = MonoTextStyle
                            )
                            IconButton(
                                onClick = { onStepPage(1) },
                                enabled = currentPreviewPage < totalPreviewPages - 1,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("preview_next_page")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(studio.elevatedSurface)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = previewBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        val aspect = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat())
                            .coerceIn(0.55f, 1.8f)
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Live paper print preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.76f)
                                .aspectRatio(aspect)
                                .shadow(4.dp, RoundedCornerShapeMinimal)
                                .background(Color.White)
                                .border(1.dp, studio.hairlineBorder)
                                .testTag("live_paper_preview")
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .aspectRatio(0.707f)
                                .background(Color.White)
                                .border(1.dp, studio.hairlineBorder),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Rendering sheet...", style = MaterialTheme.typography.bodySmall, color = studio.mutedGraphite)
                        }
                    }
                }
            }

            // 4. Pre-Print Tuning Panel
            StudioSectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable { tuningExpanded = !tuningExpanded }
                        .testTag("tuning_header_toggle"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = studio.primaryInk, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pre-Print Tuning", style = MaterialTheme.typography.titleMedium)
                    }
                    Icon(
                        imageVector = if (tuningExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (tuningExpanded) "Collapse tuning" else "Expand tuning"
                    )
                }

                AnimatedVisibility(
                    visible = tuningExpanded,
                    enter = expandVertically(spring()) + fadeIn(),
                    exit = shrinkVertically(spring()) + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Output Mode", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutputMode.entries.forEach { mode ->
                                StudioChoiceChip(
                                    label = mode.displayName,
                                    selected = tuning.outputMode == mode,
                                    onClick = { onUpdateTuning { it.copy(outputMode = mode) } },
                                    testTag = "output_mode_${mode.name.lowercase()}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (effectiveMonochrome) {
                            Text("Monochrome Dither Mode", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DitherMode.entries.forEach { dither ->
                                    StudioChoiceChip(
                                        label = dither.displayName,
                                        selected = tuning.ditherMode == dither,
                                        onClick = { onUpdateTuning { it.copy(ditherMode = dither) } },
                                        testTag = "dither_mode_${dither.name.lowercase()}"
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { showCropDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("open_crop_button")
                            ) {
                                Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (tuning.cropRect.isFull) "Crop Area" else "Cropped")
                            }
                            OutlinedButton(
                                onClick = onRotate90,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("rotate_90_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rotate (${tuning.rotationDegrees}°)")
                            }
                        }

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Brightness", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                                Text(
                                    text = "%+.0f%%".format(tuning.brightness * 100f),
                                    style = MonoTextStyle,
                                    color = studio.primaryInk
                                )
                            }
                            Slider(
                                value = tuning.brightness,
                                onValueChange = { v -> onUpdateTuning { it.copy(brightness = v) } },
                                valueRange = -0.45f..0.45f,
                                colors = SliderDefaults.colors(
                                    thumbColor = studio.activeAccent,
                                    activeTrackColor = studio.activeAccent
                                ),
                                modifier = Modifier.testTag("brightness_slider")
                            )
                        }

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Contrast", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                                Text(
                                    text = "%.2f×".format(tuning.contrast),
                                    style = MonoTextStyle,
                                    color = studio.primaryInk
                                )
                            }
                            Slider(
                                value = tuning.contrast,
                                onValueChange = { v -> onUpdateTuning { it.copy(contrast = v) } },
                                valueRange = 0.5f..2.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = studio.activeAccent,
                                    activeTrackColor = studio.activeAccent
                                ),
                                modifier = Modifier.testTag("contrast_slider")
                            )
                        }
                    }
                }
            }

            // 5. Document-Specific Options (PDF Options OR Photo Layout Summary)
            when (document) {
                is StudioDocument.PdfDoc -> {
                    StudioSectionCard {
                        Text("PDF Print Options", style = MaterialTheme.typography.titleMedium)

                        Text("Page Range", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PageRangeMode.entries.forEach { mode ->
                                StudioChoiceChip(
                                    label = mode.displayName,
                                    selected = pdfOptions.pageRangeMode == mode,
                                    onClick = { onUpdatePdfOptions { it.copy(pageRangeMode = mode) } },
                                    testTag = "pdf_range_${mode.name.lowercase()}"
                                )
                            }
                        }

                        if (pdfOptions.pageRangeMode == PageRangeMode.CUSTOM) {
                            OutlinedTextField(
                                value = pdfOptions.customRangeText,
                                onValueChange = { txt -> onUpdatePdfOptions { it.copy(customRangeText = txt) } },
                                label = { Text("Custom pages (e.g. 1-3, 5)") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pdf_custom_range_input")
                            )
                        }

                        CopiesStepperRow(
                            copies = pdfOptions.copies,
                            onCopiesChange = { c -> onUpdatePdfOptions { it.copy(copies = c) } }
                        )

                        Text("Paper Size", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PaperSize.entries.forEach { paper ->
                                StudioChoiceChip(
                                    label = paper.displayName,
                                    selected = pdfOptions.paperSize == paper,
                                    onClick = { onUpdatePdfOptions { it.copy(paperSize = paper) } },
                                    testTag = "pdf_paper_${paper.name.lowercase()}"
                                )
                            }
                        }

                        Text("Orientation", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PageOrientation.entries.forEach { orient ->
                                StudioChoiceChip(
                                    label = orient.displayName,
                                    selected = pdfOptions.orientation == orient,
                                    onClick = { onUpdatePdfOptions { it.copy(orientation = orient) } },
                                    testTag = "pdf_orient_${orient.name.lowercase()}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text("Scale", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScaleMode.entries.forEach { scale ->
                                StudioChoiceChip(
                                    label = scale.displayName,
                                    selected = pdfOptions.scaleMode == scale,
                                    onClick = { onUpdatePdfOptions { it.copy(scaleMode = scale) } },
                                    testTag = "pdf_scale_${scale.name.lowercase()}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text("Quality", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrintQuality.entries.forEach { qual ->
                                StudioChoiceChip(
                                    label = qual.displayName,
                                    selected = pdfOptions.quality == qual,
                                    onClick = { onUpdatePdfOptions { it.copy(quality = qual) } },
                                    testTag = "pdf_quality_${qual.name.lowercase()}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                is StudioDocument.PhotoBatch -> {
                    StudioSectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Photo Size & Sheet Layout", style = MaterialTheme.typography.titleMedium)
                                val planSummary = buildString {
                                    append(photoLayout.imagesPerPage.displayName)
                                    append(" • ")
                                    append(photoLayout.physicalSizePreset.displayName)
                                    append(" • ")
                                    append(photoLayout.paperSize.displayName)
                                    photoSheetPlan?.let { append(" (${it.pages.size} sheet(s))") }
                                }
                                Text(
                                    text = planSummary,
                                    style = MonoTextStyle,
                                    color = studio.mutedGraphite
                                )
                            }
                        }

                        Button(
                            onClick = { showPhotoLayoutDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = studio.activeAccentSoft,
                                contentColor = studio.activeAccent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("open_photo_layout_studio_button")
                        ) {
                            Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_photo_layout))
                        }

                        CopiesStepperRow(
                            copies = photoLayout.copies,
                            onCopiesChange = { c -> onUpdatePhotoLayout(photoLayout.copy(copies = c)) }
                        )
                    }
                }

                else -> Unit
            }

            // 6. Primary Print Action / Live Band Streaming Progress
            StudioSectionCard {
                if (printProgress.isPrinting) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = printProgress.phaseLabel.ifBlank { "Streaming USB job..." },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = PrintStudioViewModel.formatBytes(printProgress.bytesSent),
                                style = MonoTextStyle,
                                color = studio.activeAccent
                            )
                        }
                        val progressFraction = if (printProgress.totalBands > 0) {
                            (printProgress.currentBand.toFloat() / printProgress.totalBands.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            color = studio.activeAccent,
                            trackColor = studio.secondaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                        )
                        OutlinedButton(
                            onClick = onCancelPrint,
                            border = BorderStroke(1.dp, studio.crimson),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("cancel_print_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = studio.crimson)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_cancel_print), color = studio.crimson)
                        }
                    }
                } else {
                    Button(
                        onClick = onStartPrint,
                        colors = ButtonDefaults.buttonColors(containerColor = studio.primaryInk),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("print_now_button")
                    ) {
                        Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (usbState) {
                                is UsbPrinterState.PermissionRequired -> stringResource(R.string.action_grant_usb)
                                else -> stringResource(R.string.action_print_now)
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private val RoundedCornerShapeMinimal = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
