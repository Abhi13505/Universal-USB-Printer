package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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

private val ControlCornerShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
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
                Column {
                    TopAppBar(
                        expandedHeight = 52.dp,
                        title = {
                            val sheets = livePlan.pages.size
                            val sheetText = if (sheets == 1) "1 sheet" else "$sheets sheets"
                            Column {
                                Text("Photo Size & Layout", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    text = "${livePlan.columns}×${livePlan.rows} grid · $sheetText · ${draft.paperSize.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
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
                    HorizontalDivider(color = studio.hairlineBorder)
                }
            },
            bottomBar = {
                Column(modifier = Modifier.background(studio.cardSurface)) {
                    HorizontalDivider(color = studio.hairlineBorder)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = { onApply(draft) },
                            shape = ControlCornerShape,
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
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = studio.activeAccent
                                )
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Images per Page", style = MaterialTheme.typography.titleMedium)
                            SegmentedChipRow(
                                options = ImagesPerPageOption.entries,
                                selected = draft.imagesPerPage,
                                label = { it.displayName },
                                testTag = { "layout_per_page_${it.name.lowercase()}" },
                                onSelect = { opt -> draft = draft.copy(imagesPerPage = opt) }
                            )
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Physical Photo Size", style = MaterialTheme.typography.titleMedium)
                            SegmentedChipRow(
                                options = PhysicalPhotoSizePreset.entries,
                                selected = draft.physicalSizePreset,
                                label = { it.displayName },
                                testTag = { "layout_size_${it.name.lowercase()}" },
                                onSelect = { preset -> draft = draft.copy(physicalSizePreset = preset) }
                            )
                        }
                    }

                    // Gaps, Margins, Cut Guides, and 3x3 Position Pad
                    StudioSectionCard {
                        Text("Gaps & Margins", style = MaterialTheme.typography.titleMedium)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Gaps", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            SegmentedChipRow(
                                options = GapPreset.entries,
                                selected = draft.gapPreset,
                                label = { it.displayName },
                                testTag = { "layout_gap_${it.name.lowercase()}" },
                                onSelect = { gap -> draft = draft.copy(gapPreset = gap) }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Margins", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            SegmentedChipRow(
                                options = MarginPreset.entries,
                                selected = draft.marginPreset,
                                label = { it.displayName },
                                testTag = { "layout_margin_${it.name.lowercase()}" },
                                onSelect = { margin -> draft = draft.copy(marginPreset = margin) }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cut Guides", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            SegmentedChipRow(
                                options = CutGuideStyle.entries,
                                selected = draft.cutGuideStyle,
                                label = { it.displayName },
                                testTag = { "layout_cut_${it.name.lowercase()}" },
                                onSelect = { guide -> draft = draft.copy(cutGuideStyle = guide) }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("3×3 Sheet Position Pad", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (r in 0..2) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (c in 0..2) {
                                            val anchor = AnchorPosition.entries.first { it.row == r && it.col == c }
                                            val selected = draft.anchorPosition == anchor
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(ControlCornerShape)
                                                    .background(if (selected) studio.activeAccent else studio.secondaryContainer)
                                                    .border(
                                                        1.dp,
                                                        if (selected) studio.activeAccent else studio.hairlineBorder,
                                                        ControlCornerShape
                                                    )
                                                    .clickable { draft = draft.copy(anchorPosition = anchor) }
                                                    .testTag("layout_anchor_${anchor.name.lowercase()}"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(if (selected) Color.White else studio.mutedGraphite)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Paper, Orientation, Quality, Copies
                    StudioSectionCard {
                        Text("Sheet & Output Settings", style = MaterialTheme.typography.titleMedium)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Paper Size", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            SegmentedChipRow(
                                options = PaperSize.entries,
                                selected = draft.paperSize,
                                label = { it.displayName },
                                testTag = { "layout_paper_${it.name.lowercase()}" },
                                onSelect = { paper -> draft = draft.copy(paperSize = paper) }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Orientation", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            SegmentedChipRow(
                                options = PageOrientation.entries,
                                selected = draft.orientation,
                                label = { it.displayName },
                                testTag = { "layout_orient_${it.name.lowercase()}" },
                                onSelect = { orient -> draft = draft.copy(orientation = orient) }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Quality", style = MaterialTheme.typography.labelMedium, color = studio.mutedGraphite)
                            SegmentedChipRow(
                                options = PrintQuality.entries,
                                selected = draft.quality,
                                label = { it.displayName },
                                testTag = { "layout_quality_${it.name.lowercase()}" },
                                onSelect = { qual -> draft = draft.copy(quality = qual) }
                            )
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
