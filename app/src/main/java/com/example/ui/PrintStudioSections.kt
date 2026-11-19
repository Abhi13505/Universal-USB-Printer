package com.example.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.example.model.OutputMode
import com.example.model.PageOrientation
import com.example.model.PageRangeMode
import com.example.model.PaperSize
import com.example.model.PdfPrintOptions
import com.example.model.PhotoLayoutOptions
import com.example.model.PhysicalPhotoSizePreset
import com.example.model.PrintJobProgress
import com.example.model.PrintQuality
import com.example.model.PrintTuningOptions
import com.example.model.ScaleMode
import com.example.model.UsbPrinterState
import com.example.ui.theme.LocalStudioColors
import com.example.viewmodel.PrintStudioViewModel
import com.example.viewmodel.StudioDocument
import kotlin.math.abs

private val PreviewPaperShape = RoundedCornerShape(4.dp)
private val ControlCornerShape = RoundedCornerShape(12.dp)
private val CardCornerShape = RoundedCornerShape(16.dp)

@Composable
internal fun DocumentSourceRow(
    onPickPhotos: () -> Unit,
    onPickPdf: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = onPickPhotos,
            shape = ControlCornerShape,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("pick_photos_button")
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Photos", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        FilledTonalButton(
            onClick = onPickPdf,
            shape = ControlCornerShape,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("open_pdf_button")
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("PDF", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun EmptyStudioPreviewCard(modifier: Modifier = Modifier) {
    val studio = LocalStudioColors.current
    StudioSectionCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CardCornerShape)
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
            Spacer(modifier = Modifier.height(8.dp))
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
}

@Composable
internal fun LivePaperPreviewCard(
    document: StudioDocument,
    tuning: PrintTuningOptions,
    photoSheetPlan: PhotoSheetPlan?,
    previewBitmap: Bitmap?,
    currentPreviewPage: Int,
    effectiveMonochrome: Boolean,
    onStepPage: (Int) -> Unit,
    isWidePane: Boolean = false,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    val (docTitle, totalPreviewPages) = when (document) {
        is StudioDocument.PdfDoc -> document.title to document.totalPages
        is StudioDocument.PhotoBatch -> {
            val sheets = photoSheetPlan?.pages?.size ?: 1
            val label = if (document.items.size == 1) document.items[0].label else "${document.items.size} Photos"
            label to sheets
        }
        StudioDocument.DiagnosticSheet -> "Test Sheet" to 1
        StudioDocument.Empty -> "" to 1
    }

    StudioSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = docTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = studio.primaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (effectiveMonochrome) {
                        "Monochrome · ${tuning.ditherMode.displayName}"
                    } else {
                        "Full color"
                    },
                    style = MaterialTheme.typography.bodySmall,
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
                        text = "${currentPreviewPage + 1} / $totalPreviewPages",
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.primaryInk
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
                .clip(ControlCornerShape)
                .background(studio.elevatedSurface)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val bmp = previewBitmap
            val previewWidthFraction = if (isWidePane) 0.68f else 0.76f
            if (bmp != null && !bmp.isRecycled) {
                val aspect = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat())
                    .coerceIn(0.55f, 1.8f)
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Live paper print preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(previewWidthFraction)
                        .heightIn(max = if (isWidePane) 420.dp else 360.dp)
                        .aspectRatio(aspect)
                        .shadow(4.dp, PreviewPaperShape)
                        .background(Color.White)
                        .border(1.dp, studio.hairlineBorder)
                        .testTag("live_paper_preview")
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .heightIn(max = if (isWidePane) 420.dp else 360.dp)
                        .aspectRatio(0.707f)
                        .background(Color.White)
                        .border(1.dp, studio.hairlineBorder),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Rendering sheet...",
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.mutedGraphite
                    )
                }
            }
        }
    }
}

@Composable
internal fun PrePrintTuningCard(
    tuning: PrintTuningOptions,
    effectiveMonochrome: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onUpdateTuning: ((PrintTuningOptions) -> PrintTuningOptions) -> Unit,
    onOpenCrop: () -> Unit,
    onRotate90: () -> Unit
) {
    val studio = LocalStudioColors.current
    val safeMarginOptions = listOf(0, 4, 5, 8, 10)
    val selectedMargin = safeMarginOptions.minByOrNull { abs(tuning.safeMarginMm - it) } ?: 5

    StudioSectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clickable(onClick = onToggleExpanded)
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
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse tuning" else "Expand tuning",
                tint = studio.mutedGraphite
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring()) + fadeIn(),
            exit = shrinkVertically(spring()) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Output Mode", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                    SegmentedChipRow(
                        options = OutputMode.entries,
                        selected = tuning.outputMode,
                        label = { it.displayName },
                        testTag = { "output_mode_${it.name.lowercase()}" },
                        onSelect = { mode -> onUpdateTuning { it.copy(outputMode = mode) } }
                    )
                }

                if (effectiveMonochrome) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Dither", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        SegmentedChipRow(
                            options = DitherMode.entries,
                            selected = tuning.ditherMode,
                            label = { it.displayName },
                            testTag = { "dither_mode_${it.name.lowercase()}" },
                            onSelect = { dither -> onUpdateTuning { it.copy(ditherMode = dither) } }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenCrop,
                        shape = ControlCornerShape,
                        border = BorderStroke(1.dp, studio.hairlineBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("open_crop_button")
                    ) {
                        Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (tuning.cropRect.isFull) "Crop Area" else "Cropped", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onRotate90,
                        shape = ControlCornerShape,
                        border = BorderStroke(1.dp, studio.hairlineBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("rotate_90_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rotate (${tuning.rotationDegrees}°)", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            style = MaterialTheme.typography.bodySmall,
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
                            style = MaterialTheme.typography.bodySmall,
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

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Safe Margin", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Text(
                            text = "${tuning.safeMarginMm.toInt()} mm",
                            style = MaterialTheme.typography.bodySmall,
                            color = studio.primaryInk
                        )
                    }
                    SegmentedChipRow(
                        options = safeMarginOptions,
                        selected = selectedMargin,
                        label = { "${it} mm" },
                        testTag = { "safe_margin_${it}mm" },
                        onSelect = { mm -> onUpdateTuning { it.copy(safeMarginMm = mm.toFloat()) } }
                    )
                }
            }
        }
    }
}

@Composable
internal fun PdfOptionsSummaryCard(
    pdfOptions: PdfPrintOptions,
    onOpenSheet: () -> Unit
) {
    val studio = LocalStudioColors.current
    val copiesText = if (pdfOptions.copies == 1) "1 copy" else "${pdfOptions.copies} copies"
    val summaryParts = buildList {
        if (pdfOptions.pageRangeMode != PageRangeMode.ALL) {
            val rangeLabel = if (pdfOptions.pageRangeMode == PageRangeMode.CUSTOM && pdfOptions.customRangeText.isNotBlank()) {
                "Pages ${pdfOptions.customRangeText.trim()}"
            } else {
                pdfOptions.pageRangeMode.displayName
            }
            add(rangeLabel)
        }
        add(pdfOptions.paperSize.displayName)
        add(pdfOptions.orientation.displayName)
        add(pdfOptions.scaleMode.displayName)
        add(pdfOptions.quality.displayName)
        add(copiesText)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardCornerShape)
            .clickable(onClick = onOpenSheet),
        shape = CardCornerShape,
        color = studio.cardSurface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "PDF Options",
                    style = MaterialTheme.typography.titleMedium,
                    color = studio.primaryInk
                )
                Text(
                    text = summaryParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = studio.mutedGraphite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Edit PDF options",
                tint = studio.mutedGraphite
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfOptionsBottomSheet(
    pdfOptions: PdfPrintOptions,
    onUpdatePdfOptions: ((PdfPrintOptions) -> PdfPrintOptions) -> Unit,
    onDismiss: () -> Unit
) {
    val studio = LocalStudioColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = studio.cardSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PDF Print Options",
                    style = MaterialTheme.typography.titleLarge,
                    color = studio.primaryInk
                )
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Page Range", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                SegmentedChipRow(
                    options = PageRangeMode.entries,
                    selected = pdfOptions.pageRangeMode,
                    label = { it.displayName },
                    testTag = { "pdf_range_${it.name.lowercase()}" },
                    onSelect = { mode -> onUpdatePdfOptions { it.copy(pageRangeMode = mode) } }
                )
            }

            if (pdfOptions.pageRangeMode == PageRangeMode.CUSTOM) {
                OutlinedTextField(
                    value = pdfOptions.customRangeText,
                    onValueChange = { txt -> onUpdatePdfOptions { it.copy(customRangeText = txt) } },
                    label = { Text("Custom pages (e.g. 1-3, 5)") },
                    singleLine = true,
                    shape = ControlCornerShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pdf_custom_range_input")
                )
            }

            CopiesStepperRow(
                copies = pdfOptions.copies,
                onCopiesChange = { c -> onUpdatePdfOptions { it.copy(copies = c) } }
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paper Size", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                SegmentedChipRow(
                    options = PaperSize.entries,
                    selected = pdfOptions.paperSize,
                    label = { it.displayName },
                    testTag = { "pdf_paper_${it.name.lowercase()}" },
                    onSelect = { paper -> onUpdatePdfOptions { it.copy(paperSize = paper) } }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Orientation", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                SegmentedChipRow(
                    options = PageOrientation.entries,
                    selected = pdfOptions.orientation,
                    label = { it.displayName },
                    testTag = { "pdf_orient_${it.name.lowercase()}" },
                    onSelect = { orient -> onUpdatePdfOptions { it.copy(orientation = orient) } }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scale", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                SegmentedChipRow(
                    options = ScaleMode.entries,
                    selected = pdfOptions.scaleMode,
                    label = { it.displayName },
                    testTag = { "pdf_scale_${it.name.lowercase()}" },
                    onSelect = { scale -> onUpdatePdfOptions { it.copy(scaleMode = scale) } }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quality", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                SegmentedChipRow(
                    options = PrintQuality.entries,
                    selected = pdfOptions.quality,
                    label = { it.displayName },
                    testTag = { "pdf_quality_${it.name.lowercase()}" },
                    onSelect = { qual -> onUpdatePdfOptions { it.copy(quality = qual) } }
                )
            }
        }
    }
}

@Composable
internal fun PhotoLayoutSummaryCard(
    photoCount: Int,
    photoLayout: PhotoLayoutOptions,
    photoSheetPlan: PhotoSheetPlan?,
    onOpenLayoutStudio: () -> Unit,
    onCopiesChange: (Int) -> Unit
) {
    val studio = LocalStudioColors.current
    val photoLabel = if (photoCount == 1) "1 photo" else "$photoCount photos"
    val sizeLabel = if (photoLayout.physicalSizePreset == PhysicalPhotoSizePreset.AUTO_FIT_CELL) {
        "Fit to cell"
    } else {
        photoLayout.physicalSizePreset.displayName
    }
    val sheetLabel = photoSheetPlan?.let {
        val count = it.pages.size
        if (count == 1) "1 sheet" else "$count sheets"
    }
    val summary = listOfNotNull(photoLabel, sizeLabel, photoLayout.paperSize.displayName, sheetLabel)
        .joinToString(" · ")

    StudioSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Photo Size & Sheet Layout", style = MaterialTheme.typography.titleMedium, color = studio.primaryInk)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = studio.mutedGraphite
            )
        }

        Button(
            onClick = onOpenLayoutStudio,
            shape = ControlCornerShape,
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
            onCopiesChange = onCopiesChange
        )
    }
}

@Composable
internal fun PrintActionContent(
    usbState: UsbPrinterState,
    isUnsupportedPrinter: Boolean,
    printProgress: PrintJobProgress,
    onStartPrint: () -> Unit,
    onCancelPrint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    if (printProgress.isPrinting) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = printProgress.phaseLabel.ifBlank { "Streaming USB job..." },
                    style = MaterialTheme.typography.titleSmall,
                    color = studio.primaryInk
                )
                Text(
                    text = PrintStudioViewModel.formatBytes(printProgress.bytesSent),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
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
                    .clip(RoundedCornerShape(4.dp))
            )
            OutlinedButton(
                onClick = onCancelPrint,
                shape = ControlCornerShape,
                border = BorderStroke(1.dp, studio.crimson),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("cancel_print_button")
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = studio.crimson)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel_print), color = studio.crimson)
            }
        }
    } else {
        Button(
            onClick = onStartPrint,
            enabled = !isUnsupportedPrinter,
            shape = ControlCornerShape,
            colors = ButtonDefaults.buttonColors(containerColor = studio.primaryInk),
            modifier = modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("print_now_button")
        ) {
            Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isUnsupportedPrinter -> "Unsupported Language (Choose Override)"
                    usbState is UsbPrinterState.PermissionRequired -> stringResource(R.string.action_grant_usb)
                    else -> stringResource(R.string.action_print_now)
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
