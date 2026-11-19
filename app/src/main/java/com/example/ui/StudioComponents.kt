package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.DriverProtocol
import com.example.model.UsbPrinterState
import com.example.ui.theme.LocalStudioColors
import com.example.usb.PrinterCapabilityResolver

private val CardCornerShape = RoundedCornerShape(16.dp)
private val ChipCornerShape = RoundedCornerShape(12.dp)

@Composable
fun StudioSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val studio = LocalStudioColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardCornerShape,
        color = studio.cardSurface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
fun StatusBadgePill(
    label: String,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StudioChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    val bg = if (selected) studio.activeAccentSoft else studio.secondaryContainer
    val border = if (selected) studio.activeAccent else studio.hairlineBorder
    val borderWidth = if (selected) 1.5.dp else 1.dp
    val txt = if (selected) studio.activeAccent else studio.primaryInk

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(ChipCornerShape)
            .background(bg)
            .border(borderWidth, border, ChipCornerShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = txt,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun <T> SegmentedChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    testTag: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.size in 2..4) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                StudioChoiceChip(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    testTag = testTag(option),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                StudioChoiceChip(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    testTag = testTag(option)
                )
            }
        }
    }
}

@Composable
fun PrinterStatusHeaderCard(
    usbState: UsbPrinterState,
    effectiveMonochrome: Boolean,
    testPageVerified: Boolean = false,
    onScanUsb: () -> Unit,
    onGrantPermission: () -> Unit
) {
    val studio = LocalStudioColors.current
    val isUnsupported = usbState is UsbPrinterState.Ready &&
        usbState.profile.effectiveProtocol == DriverProtocol.UNSUPPORTED

    val dotColor = when (usbState) {
        is UsbPrinterState.Ready -> {
            val isHardwareVerified = PrinterCapabilityResolver.isHardwareVerifiedPrinter(usbState.deviceInfo)
            when {
                isUnsupported -> studio.crimson
                !isHardwareVerified && !testPageVerified -> studio.amber
                usbState.profile.isBestEffortFallback -> studio.amber
                else -> studio.emerald
            }
        }
        is UsbPrinterState.PermissionRequired, is UsbPrinterState.Probing -> studio.amber
        is UsbPrinterState.Error -> studio.crimson
        UsbPrinterState.Disconnected -> studio.mutedGraphite.copy(alpha = 0.4f)
    }

    val title = when (usbState) {
        is UsbPrinterState.Ready -> usbState.profile.makeAndModel
        is UsbPrinterState.PermissionRequired ->
            usbState.deviceInfo.productName ?: "USB Printer (${usbState.deviceInfo.vidPidKey})"
        is UsbPrinterState.Probing ->
            usbState.deviceInfo.productName ?: "Checking printer..."
        is UsbPrinterState.Error ->
            usbState.deviceInfo?.productName ?: "Printer error"
        UsbPrinterState.Disconnected -> "No printer connected"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardCornerShape,
        color = studio.cardSurface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (usbState is UsbPrinterState.Disconnected) studio.mutedGraphite else studio.primaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (usbState is UsbPrinterState.PermissionRequired) {
                    Button(
                        onClick = onGrantPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = studio.activeAccent),
                        shape = ChipCornerShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("grant_usb_permission_button")
                    ) {
                        Text("Allow USB", style = MaterialTheme.typography.labelMedium)
                    }
                }

                IconButton(
                    onClick = onScanUsb,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("scan_usb_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Scan USB printers",
                        tint = studio.mutedGraphite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (usbState is UsbPrinterState.Error) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ChipCornerShape)
                        .background(studio.crimsonSoft)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Error details",
                        tint = studio.crimson,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = usbState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.crimson
                    )
                }
            }
        }
    }
}

@Composable
fun CopiesStepperRow(
    copies: Int,
    onCopiesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Copies",
            style = MaterialTheme.typography.titleSmall,
            color = studio.primaryInk
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { onCopiesChange((copies - 1).coerceIn(1, 99)) },
                enabled = copies > 1,
                modifier = Modifier
                    .size(48.dp)
                    .clip(ChipCornerShape)
                    .background(studio.secondaryContainer)
                    .testTag("copies_decrement_button")
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease copies")
            }
            Text(
                text = copies.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = studio.primaryInk,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(
                onClick = { onCopiesChange((copies + 1).coerceIn(1, 99)) },
                enabled = copies < 99,
                modifier = Modifier
                    .size(48.dp)
                    .clip(ChipCornerShape)
                    .background(studio.secondaryContainer)
                    .testTag("copies_increment_button")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase copies")
            }
        }
    }
}
