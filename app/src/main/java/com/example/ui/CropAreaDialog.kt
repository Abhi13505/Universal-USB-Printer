package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.NormalizedCropRect
import com.example.ui.theme.LocalStudioColors
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropAreaDialog(
    sourceBitmap: Bitmap?,
    initialCrop: NormalizedCropRect,
    onDismiss: () -> Unit,
    onApply: (NormalizedCropRect) -> Unit
) {
    val studio = LocalStudioColors.current
    var crop by remember(initialCrop) { mutableStateOf(initialCrop.normalized()) }

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
                        title = { Text("Crop Area", style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("crop_close_button")
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Close crop editor")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = studio.cardSurface,
                            titleContentColor = studio.primaryInk
                        )
                    )
                    androidx.compose.material3.HorizontalDivider(color = studio.hairlineBorder)
                }
            },
            bottomBar = {
                Column(modifier = Modifier.background(studio.cardSurface)) {
                    androidx.compose.material3.HorizontalDivider(color = studio.hairlineBorder)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { crop = NormalizedCropRect() },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("crop_reset_button")
                        ) {
                            Text("Reset")
                        }
                        Button(
                            onClick = { onApply(crop.normalized()) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = studio.activeAccent),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("crop_apply_button")
                        ) {
                            Text("Apply Crop")
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Drag any corner handle to adjust print crop area",
                    style = MaterialTheme.typography.bodyMedium,
                    color = studio.mutedGraphite
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .background(studio.elevatedSurface, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                        val imgRatio = sourceBitmap.width.toFloat() / sourceBitmap.height.coerceAtLeast(1).toFloat()
                        val boxRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                        val fitModifier = if (imgRatio > boxRatio) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.fillMaxSize()
                        }

                        Box(
                            modifier = fitModifier.padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = sourceBitmap.asImageBitmap(),
                                contentDescription = "Crop preview image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )

                            var activeCorner by remember { mutableStateOf(-1) }
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                val w = size.width.toFloat().coerceAtLeast(1f)
                                                val h = size.height.toFloat().coerceAtLeast(1f)
                                                val corners = listOf(
                                                    Offset(crop.left * w, crop.top * h),
                                                    Offset(crop.right * w, crop.top * h),
                                                    Offset(crop.left * w, crop.bottom * h),
                                                    Offset(crop.right * w, crop.bottom * h)
                                                )
                                                activeCorner = corners.indices.minByOrNull { idx ->
                                                    hypot(offset.x - corners[idx].x, offset.y - corners[idx].y)
                                                } ?: -1
                                            },
                                            onDragEnd = { activeCorner = -1 },
                                            onDragCancel = { activeCorner = -1 },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val w = size.width.toFloat().coerceAtLeast(1f)
                                                val h = size.height.toFloat().coerceAtLeast(1f)
                                                val dx = dragAmount.x / w
                                                val dy = dragAmount.y / h
                                                crop = when (activeCorner) {
                                                    0 -> crop.copy(
                                                        left = (crop.left + dx).coerceIn(0f, crop.right - 0.1f),
                                                        top = (crop.top + dy).coerceIn(0f, crop.bottom - 0.1f)
                                                    )
                                                    1 -> crop.copy(
                                                        right = (crop.right + dx).coerceIn(crop.left + 0.1f, 1f),
                                                        top = (crop.top + dy).coerceIn(0f, crop.bottom - 0.1f)
                                                    )
                                                    2 -> crop.copy(
                                                        left = (crop.left + dx).coerceIn(0f, crop.right - 0.1f),
                                                        bottom = (crop.bottom + dy).coerceIn(crop.top + 0.1f, 1f)
                                                    )
                                                    3 -> crop.copy(
                                                        right = (crop.right + dx).coerceIn(crop.left + 0.1f, 1f),
                                                        bottom = (crop.bottom + dy).coerceIn(crop.top + 0.1f, 1f)
                                                    )
                                                    else -> crop
                                                }
                                            }
                                        )
                                    }
                            ) {
                                val l = crop.left * size.width
                                val t = crop.top * size.height
                                val r = crop.right * size.width
                                val b = crop.bottom * size.height

                                // Darken excluded regions
                                val shade = Color.Black.copy(alpha = 0.45f)
                                drawRect(shade, Offset.Zero, Size(size.width, t))
                                drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                                drawRect(shade, Offset(0f, t), Size(l, b - t))
                                drawRect(shade, Offset(r, t), Size(size.width - r, b - t))

                                // Crop border
                                drawRect(
                                    color = studio.activeAccent,
                                    topLeft = Offset(l, t),
                                    size = Size(r - l, b - t),
                                    style = Stroke(width = 2.5.dp.toPx())
                                )

                                // Rule-of-thirds lines
                                val thirdW = (r - l) / 3f
                                val thirdH = (b - t) / 3f
                                val guide = Color.White.copy(alpha = 0.6f)
                                drawLine(guide, Offset(l + thirdW, t), Offset(l + thirdW, b), 1.dp.toPx())
                                drawLine(guide, Offset(l + 2 * thirdW, t), Offset(l + 2 * thirdW, b), 1.dp.toPx())
                                drawLine(guide, Offset(l, t + thirdH), Offset(r, t + thirdH), 1.dp.toPx())
                                drawLine(guide, Offset(l, t + 2 * thirdH), Offset(r, t + 2 * thirdH), 1.dp.toPx())

                                // 4 Corner handles
                                val handleRadius = 9.dp.toPx()
                                listOf(
                                    Offset(l, t),
                                    Offset(r, t),
                                    Offset(l, b),
                                    Offset(r, b)
                                ).forEach { center ->
                                    drawCircle(Color.White, radius = handleRadius, center = center)
                                    drawCircle(studio.activeAccent, radius = handleRadius - 2.dp.toPx(), center = center)
                                }
                            }
                        }
                    }
                }

                val pctW = ((crop.right - crop.left) * 100).toInt()
                val pctH = ((crop.bottom - crop.top) * 100).toInt()
                Text(
                    text = "Selection: $pctW% × $pctH%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = studio.primaryInk
                )
            }
        }
    }
}
