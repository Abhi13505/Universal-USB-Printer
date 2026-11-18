package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.document.PhotoLayoutGeometry
import com.example.model.AnchorPosition
import com.example.model.CutGuideStyle
import com.example.model.GapPreset
import com.example.model.ImagesPerPageOption
import com.example.model.MarginPreset
import com.example.model.PageOrientation
import com.example.model.PaperSize
import com.example.model.PhotoLayoutOptions
import com.example.model.PhysicalPhotoSizePreset
import com.example.model.PrintQuality
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MonoTextStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoLayoutStudioDialog(
    photoCount: Int,
    photoAspectRatios: List<Float>,
    initialOptions: PhotoLayoutOptions,
    onDismiss: () -> Unit,
    onApply: (PhotoLayoutOptions) -> Unit
) {
    val studio = LocalStudioColors.current
    var draft by remember(initialOptions) { mutableStateOf(initialOptions) }
    val livePlan = remember(draft, photoCount, photoAspectRatios) {
        PhotoLayoutGeometry.computeSheetPlan(
            photoCount = photoCount.coerceAtLeast(1),
            options = draft,
            photoAspectRatios = photoAspectRatios
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(onBack = onDismiss)
        Scaffold(
            containerColor = studio.paperBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Photo Size & Layout Studio", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "${livePlan.columns}×${livePlan.rows} grid • ${livePlan.pages.size} sheet(s) • ${draft.paperSize.displayName}",
                                style = MonoTextStyle,
                                color = studio.mutedGraphite
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("photo_layout_close_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close layout studio")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = studio.cardSurface,
                        titleContentColor = studio.primaryInk
                    )
                )
            },
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(studio.cardSurface)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { onApply(draft) },
                        colors = ButtonDefaults.buttonColors(containerColor = studio.activeAccent),
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("photo_layout_apply_button")
                    ) {
                        Text("Apply Sheet Layout")
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Live Sheet Blueprint Diagram
                    StudioSectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Live Sheet Blueprint", style = MaterialTheme.typography.titleMedium)
                            val firstCell = livePlan.cells.firstOrNull()
                            if (firstCell != null) {
                                Text(
                                    text = "Cell ${firstCell.widthMm.toInt()}×${firstCell.heightMm.toInt()} mm",
                                    style = MonoTextStyle,
                                    color = studio.activeAccent
                                )
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
                            val paperAspect = (livePlan.paperWidthMm / livePlan.paperHeightMm).coerceIn(0.5f, 1.8f)
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .aspectRatio(paperAspect)
                                    .background(Color.White)
                                    .border(1.dp, studio.hairlineBorder)
                            ) {
                                val scaleX = size.width / livePlan.paperWidthMm
                                val scaleY = size.height / livePlan.paperHeightMm

                                // Printable margin guide
                                val m = livePlan.marginMm
                                if (m > 0f) {
                                    drawRect(
                                        color = studio.hairlineBorder,
                                        topLeft = Offset(m * scaleX, m * scaleY),
                                        size = Size(
                                            (livePlan.paperWidthMm - 2f * m) * scaleX,
                                            (livePlan.paperHeightMm - 2f * m) * scaleY
                                        ),
                                        style = Stroke(
                                            width = 1.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                                        )
                                    )
                                }

                                // Cell slots
                                livePlan.cells.forEachIndexed { idx, cell ->
                                    val x = cell.leftMm * scaleX
                                    val y = cell.topMm * scaleY
                                    val w = cell.widthMm * scaleX
                                    val h = cell.heightMm * scaleY
                                    val filled = idx < photoCount || (photoCount == 1 && draft.repeatSinglePhoto)

                                    drawRect(
                                        color = if (filled) studio.activeAccentSoft else studio.secondaryContainer,
                                        topLeft = Offset(x, y),
                                        size = Size(w, h)
                                    )
                                    drawRect(
                                        color = if (filled) studio.activeAccent else studio.mutedGraphite.copy(alpha = 0.4f),
                                        topLeft = Offset(x, y),
                                        size = Size(w, h),
                                        style = Stroke(width = if (draft.cutGuideStyle == CutGuideStyle.THIN_FRAME) 1.8.dp.toPx() else 1.dp.toPx())
                                    )

                                    if (draft.cutGuideStyle == CutGuideStyle.CUT_MARKS) {
                                        val tick = 5.dp.toPx()
                                        val c = studio.primaryInk
                                        drawLine(c, Offset(x, y), Offset(x + tick, y), 1.5.dp.toPx())
                                        drawLine(c, Offset(x, y), Offset(x, y + tick), 1.5.dp.toPx())
                                        drawLine(c, Offset(x + w, y), Offset(x + w - tick, y), 1.5.dp.toPx())
                                        drawLine(c, Offset(x + w, y), Offset(x + w, y + tick), 1.5.dp.toPx())
                                        drawLine(c, Offset(x, y + h), Offset(x + tick, y + h), 1.5.dp.toPx())
                                        drawLine(c, Offset(x, y + h), Offset(x, y + h - tick), 1.5.dp.toPx())
                                        drawLine(c, Offset(x + w, y + h), Offset(x + w - tick, y + h), 1.5.dp.toPx())
                                        drawLine(c, Offset(x + w, y + h), Offset(x + w, y + h - tick), 1.5.dp.toPx())
                                    }
                                }
                            }
                        }
                    }

                    // Images Per Page + Toggles
                    StudioSectionCard {
                        Text("Images per Page", style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImagesPerPageOption.entries.forEach { opt ->
                                StudioChoiceChip(
                                    label = opt.displayName,
                                    selected = draft.imagesPerPage == opt,
                                    onClick = { draft = draft.copy(imagesPerPage = opt) },
                                    testTag = "layout_per_page_${opt.name.lowercase()}"
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Repeat single photo to fill slots",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = draft.repeatSinglePhoto,
                                onCheckedChange = { draft = draft.copy(repeatSinglePhoto = it) },
                                modifier = Modifier.testTag("layout_repeat_single_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Auto-rotate photo to cell",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = draft.autoRotateToCell,
                                onCheckedChange = { draft = draft.copy(autoRotateToCell = it) },
                                modifier = Modifier.testTag("layout_auto_rotate_switch")
                            )
                        }
                    }

                    // Physical Photo Size Presets
                    StudioSectionCard {
                        Text("Physical Photo Size", style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PhysicalPhotoSizePreset.entries.forEach { preset ->
                                StudioChoiceChip(
                                    label = preset.displayName,
                                    selected = draft.physicalSizePreset == preset,
                                    onClick = { draft = draft.copy(physicalSizePreset = preset) },
                                    testTag = "layout_size_${preset.name.lowercase()}"
                                )
                            }
                        }
                    }

                    // Gaps, Margins, Cut Guides, and 3x3 Position Pad
                    StudioSectionCard {
                        Text("Gaps & Margins", style = MaterialTheme.typography.titleMedium)
                        Text("Gaps", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GapPreset.entries.forEach { gap ->
                                StudioChoiceChip(
                                    label = gap.displayName,
                                    selected = draft.gapPreset == gap,
                                    onClick = { draft = draft.copy(gapPreset = gap) },
                                    testTag = "layout_gap_${gap.name.lowercase()}"
                                )
                            }
                        }

                        Text("Margins", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MarginPreset.entries.forEach { margin ->
                                StudioChoiceChip(
                                    label = margin.displayName,
                                    selected = draft.marginPreset == margin,
                                    onClick = { draft = draft.copy(marginPreset = margin) },
                                    testTag = "layout_margin_${margin.name.lowercase()}"
                                )
                            }
                        }

                        Text("Cut Guides", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CutGuideStyle.entries.forEach { guide ->
                                StudioChoiceChip(
                                    label = guide.displayName,
                                    selected = draft.cutGuideStyle == guide,
                                    onClick = { draft = draft.copy(cutGuideStyle = guide) },
                                    testTag = "layout_cut_${guide.name.lowercase()}"
                                )
                            }
                        }

                        Text("3×3 Sheet Position Pad", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (r in 0..2) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (c in 0..2) {
                                        val anchor = AnchorPosition.entries.first { it.row == r && it.col == c }
                                        val selected = draft.anchorPosition == anchor
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .background(if (selected) studio.activeAccent else studio.secondaryContainer)
                                                .border(
                                                    1.dp,
                                                    if (selected) studio.activeAccent else studio.hairlineBorder,
                                                    MaterialTheme.shapes.small
                                                )
                                                .clickable { draft = draft.copy(anchorPosition = anchor) }
                                                .testTag("layout_anchor_${anchor.name.lowercase()}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(MaterialTheme.shapes.extraSmall)
                                                    .background(if (selected) Color.White else studio.mutedGraphite)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Paper, Orientation, Quality, Copies
                    StudioSectionCard {
                        Text("Sheet & Output Settings", style = MaterialTheme.typography.titleMedium)
                        Text("Paper Size", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaperSize.entries.forEach { paper ->
                                StudioChoiceChip(
                                    label = paper.displayName,
                                    selected = draft.paperSize == paper,
                                    onClick = { draft = draft.copy(paperSize = paper) },
                                    testTag = "layout_paper_${paper.name.lowercase()}"
                                )
                            }
                        }
                        Text("Orientation", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PageOrientation.entries.forEach { orient ->
                                StudioChoiceChip(
                                    label = orient.displayName,
                                    selected = draft.orientation == orient,
                                    onClick = { draft = draft.copy(orientation = orient) },
                                    testTag = "layout_orient_${orient.name.lowercase()}"
                                )
                            }
                        }
                        Text("Quality", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrintQuality.entries.forEach { qual ->
                                StudioChoiceChip(
                                    label = qual.displayName,
                                    selected = draft.quality == qual,
                                    onClick = { draft = draft.copy(quality = qual) },
                                    testTag = "layout_quality_${qual.name.lowercase()}"
                                )
                            }
                        }
                        CopiesStepperRow(
                            copies = draft.copies,
                            onCopiesChange = { draft = draft.copy(copies = it) }
                        )
                    }
                }
            }
        }
    }
}
