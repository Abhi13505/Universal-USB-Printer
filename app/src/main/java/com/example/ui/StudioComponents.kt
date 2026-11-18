package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.UsbPrinterState
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.MonoTextStyle

@Composable
fun StudioSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val studio = LocalStudioColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = studio.cardSurface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            .padding(horizontal = 10.dp, vertical = 4.dp),
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
    val txt = if (selected) studio.activeAccent else studio.primaryInk

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, border, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
fun PrinterStatusHeaderCard(
    usbState: UsbPrinterState,
    effectiveMonochrome: Boolean,
    onScanUsb: () -> Unit,
    onGrantPermission: () -> Unit
) {
    val studio = LocalStudioColors.current
    StudioSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (icon, iconBg, iconTint) = when (usbState) {
                    is UsbPrinterState.Ready -> Triple(Icons.Filled.Print, studio.emeraldSoft, studio.emerald)
                    is UsbPrinterState.PermissionRequired -> Triple(Icons.Filled.Usb, studio.amberSoft, studio.amber)
                    is UsbPrinterState.Probing -> Triple(Icons.Filled.Usb, studio.amberSoft, studio.amber)
                    is UsbPrinterState.Error -> Triple(Icons.Filled.Error, studio.crimsonSoft, studio.crimson)
                    UsbPrinterState.Disconnected -> Triple(Icons.Filled.UsbOff, studio.secondaryContainer, studio.mutedGraphite)
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "USB printer status icon",
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val title = when (usbState) {
                        is UsbPrinterState.Ready -> usbState.profile.makeAndModel
                        is UsbPrinterState.PermissionRequired ->
                            usbState.deviceInfo.productName ?: "USB Printer (${usbState.deviceInfo.vidPidKey})"
                        is UsbPrinterState.Probing ->
                            usbState.deviceInfo.productName ?: "Probing USB Printer..."
                        is UsbPrinterState.Error ->
                            usbState.deviceInfo?.productName ?: "Printer Error"
                        UsbPrinterState.Disconnected -> "No USB OTG Printer"
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = studio.primaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (usbState) {
                            is UsbPrinterState.Ready -> {
                                StatusBadgePill(
                                    label = if (usbState.profile.isBestEffortFallback) "Ready (Fallback)" else "Ready",
                                    textColor = if (usbState.profile.isBestEffortFallback) studio.amber else studio.emerald,
                                    backgroundColor = if (usbState.profile.isBestEffortFallback) studio.amberSoft else studio.emeraldSoft
                                )
                                val isColorCap = !effectiveMonochrome && usbState.profile.effectiveColorSupported
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isColorCap) studio.activeAccentSoft else studio.secondaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isColorCap) Icons.Filled.Palette else Icons.Outlined.Contrast,
                                        contentDescription = if (isColorCap) "Color capability" else "Monochrome capability",
                                        tint = if (isColorCap) studio.activeAccent else studio.mutedGraphite,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isColorCap) "Color" else "Mono",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isColorCap) studio.activeAccent else studio.mutedGraphite
                                    )
                                }
                            }
                            is UsbPrinterState.PermissionRequired -> {
                                StatusBadgePill("Permission Needed", studio.amber, studio.amberSoft)
                            }
                            is UsbPrinterState.Probing -> {
                                StatusBadgePill("Probing USB...", studio.amber, studio.amberSoft)
                            }
                            is UsbPrinterState.Error -> {
                                StatusBadgePill("Error", studio.crimson, studio.crimsonSoft)
                            }
                            UsbPrinterState.Disconnected -> {
                                Text(
                                    text = "Plug in USB OTG cable or prepare offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = studio.mutedGraphite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            when (usbState) {
                is UsbPrinterState.PermissionRequired -> {
                    Button(
                        onClick = onGrantPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = studio.activeAccent),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("grant_usb_permission_button")
                    ) {
                        Text("Allow USB")
                    }
                }
                else -> {
                    FilledTonalButton(
                        onClick = onScanUsb,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("scan_usb_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Scan USB printers",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        if (usbState is UsbPrinterState.Error) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(studio.crimsonSoft)
                    .padding(10.dp)
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
                    .clip(MaterialTheme.shapes.small)
                    .background(studio.secondaryContainer)
                    .testTag("copies_decrement_button")
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease copies")
            }
            Text(
                text = copies.toString(),
                style = MonoTextStyle.copy(fontWeight = FontWeight.SemiBold),
                color = studio.primaryInk,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(
                onClick = { onCopiesChange((copies + 1).coerceIn(1, 99)) },
                enabled = copies < 99,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(studio.secondaryContainer)
                    .testTag("copies_increment_button")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase copies")
            }
        }
    }
}
