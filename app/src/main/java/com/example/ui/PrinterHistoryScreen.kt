package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.PrintHistoryEntity
import com.example.data.PrinterDeviceOverride
import com.example.model.ColorOverrideMode
import com.example.model.DriverProtocol
import com.example.model.PrintJobStatus
import com.example.model.UsbPrinterState
import com.example.ui.theme.LocalStudioColors
import com.example.usb.PrinterCapabilityResolver
import com.example.viewmodel.PrintStudioViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ControlCornerShape = RoundedCornerShape(12.dp)

@Composable
fun PrinterHistoryScreen(
    usbState: UsbPrinterState,
    override: PrinterDeviceOverride,
    history: List<PrintHistoryEntity>,
    isPrinting: Boolean = false,
    onScanUsb: () -> Unit,
    onGrantPermission: () -> Unit,
    onSoftReset: () -> Unit,
    onPrintTestPage: () -> Unit = {},
    onPrintDiagnosticSheet: () -> Unit,
    onSelectProtocolOverride: (DriverProtocol) -> Unit,
    onSelectColorOverride: (ColorOverrideMode) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Technical USB Hardware Diagnostics & Override Card
        StudioSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("USB Hardware Diagnostics", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(
                    onClick = onScanUsb,
                    modifier = Modifier.testTag("diag_scan_usb_button")
                ) {
                    Text("Rescan")
                }
            }

            when (usbState) {
                UsbPrinterState.Disconnected -> {
                    Text(
                        text = "No USB OTG printer attached. Connect a standard office or home printer via USB OTG to inspect VID:PID, IEEE 1284 CMD, and IPP-USB endpoints.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = studio.mutedGraphite
                    )
                }

                is UsbPrinterState.PermissionRequired -> {
                    Text(
                        text = "Detected ${usbState.deviceInfo.productName ?: "USB Printer"} (${usbState.deviceInfo.vidPidKey}). Grant USB Host permission to probe capabilities.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = studio.mutedGraphite
                    )
                    Button(
                        onClick = onGrantPermission,
                        shape = ControlCornerShape,
                        colors = ButtonDefaults.buttonColors(containerColor = studio.activeAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("diag_grant_usb_button")
                    ) {
                        Text(stringResource(R.string.action_grant_usb))
                    }
                }

                is UsbPrinterState.Probing -> {
                    StatusBadgePill("Probing IPP-USB & IEEE 1284...", studio.amber, studio.amberSoft)
                    TelemetryLine("USB VID:PID", usbState.deviceInfo.vidPidKey)
                }

                is UsbPrinterState.Error -> {
                    StatusBadgePill("USB Error", studio.crimson, studio.crimsonSoft)
                    Text(usbState.message, style = MaterialTheme.typography.bodySmall, color = studio.crimson)
                    usbState.deviceInfo?.let {
                        TelemetryLine("USB VID:PID", it.vidPidKey)
                    }
                }

                is UsbPrinterState.Ready -> {
                    val info = usbState.deviceInfo
                    val profile = usbState.profile
                    val ieee = usbState.ieee1284
                    val isUnsupported = profile.effectiveProtocol == DriverProtocol.UNSUPPORTED
                    val isHardwareVerified = PrinterCapabilityResolver.isHardwareVerifiedPrinter(info)

                    if (isUnsupported) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ControlCornerShape)
                                .background(studio.crimsonSoft)
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = studio.crimson, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = profile.unverifiedLanguageWarning ?: PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE,
                                style = MaterialTheme.typography.bodySmall,
                                color = studio.crimson
                            )
                        }
                    } else if (profile.isBestEffortFallback) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ControlCornerShape)
                                .background(studio.amberSoft)
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = studio.amber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Best-effort fallback active (${profile.resolutionReason}). Print a 1-inch test page or pin an explicit protocol below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = studio.amber
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TelemetryLine("Printer", profile.makeAndModel)
                        TelemetryLine("USB VID:PID", info.vidPidKey)
                        TelemetryLine("Active Interface", profile.activeInterfaceLabel)
                        TelemetryLine("Resolved Driver", profile.effectiveProtocol.displayName)
                        TelemetryLine(
                            "Test Page Status",
                            when {
                                isHardwareVerified -> "Hardware-verified (SCX-3200)"
                                override.testPageVerified -> "1-inch test page verified"
                                else -> "Required before first full job"
                            }
                        )
                        TelemetryLine(
                            "IEEE 1284 CMD",
                            ieee.commandSetTokens.joinToString(", ").ifBlank { "(none)" }
                        )
                        usbState.ipp?.let { ipp ->
                            if (ipp.documentFormatsSupported.isNotEmpty()) {
                                TelemetryLine("IPP Formats", ipp.documentFormatsSupported.joinToString(", "))
                            }
                        }
                    }

                    ieee.portStatus?.let { flags ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusBadgePill(
                                label = if (flags.selected) "Selected" else "Offline",
                                textColor = if (flags.selected) studio.emerald else studio.amber,
                                backgroundColor = if (flags.selected) studio.emeraldSoft else studio.amberSoft
                            )
                            StatusBadgePill(
                                label = if (flags.paperEmpty) "Paper Empty" else "Paper OK",
                                textColor = if (flags.paperEmpty) studio.crimson else studio.emerald,
                                backgroundColor = if (flags.paperEmpty) studio.crimsonSoft else studio.emeraldSoft
                            )
                            StatusBadgePill(
                                label = if (flags.noError) "No Fault" else "Fault Flag",
                                textColor = if (flags.noError) studio.emerald else studio.crimson,
                                backgroundColor = if (flags.noError) studio.emeraldSoft else studio.crimsonSoft
                            )
                        }
                    }

                    Button(
                        onClick = onPrintTestPage,
                        enabled = !isUnsupported && !isPrinting,
                        shape = ControlCornerShape,
                        colors = ButtonDefaults.buttonColors(containerColor = studio.primaryInk),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("diag_print_test_page_button")
                    ) {
                        Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_print_test_page))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onSoftReset,
                            shape = ControlCornerShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("soft_reset_button")
                        ) {
                            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_soft_reset), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Button(
                            onClick = onPrintDiagnosticSheet,
                            enabled = !isUnsupported && !isPrinting,
                            shape = ControlCornerShape,
                            colors = ButtonDefaults.buttonColors(containerColor = studio.activeAccent),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("print_diagnostic_sheet_button")
                        ) {
                            Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Sheet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    HorizontalDivider(color = studio.hairlineBorder)

                    // Per-VID:PID Manual Protocol & Color Overrides (persisted in DataStore)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Manual Driver Override (${info.vidPidKey})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        SegmentedChipRow(
                            options = DriverProtocol.manualSelectableEntries,
                            selected = override.protocolOverride,
                            label = { it.displayName },
                            testTag = { "override_proto_${it.name.lowercase()}" },
                            onSelect = onSelectProtocolOverride
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Color Capability Override (${info.vidPidKey})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        SegmentedChipRow(
                            options = ColorOverrideMode.entries,
                            selected = override.colorOverride,
                            label = { it.displayName },
                            testTag = { "override_color_${it.name.lowercase()}" },
                            onSelect = onSelectColorOverride
                        )
                    }
                }
            }
        }

        // 2. Room Print History Card
        StudioSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print History (${history.size})", style = MaterialTheme.typography.titleMedium)
                }
                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = onClearHistory,
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ControlCornerShape)
                        .background(studio.secondaryContainer)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.empty_history_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.mutedGraphite
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.forEachIndexed { idx, entry ->
                        PrintHistoryRow(entry = entry)
                        if (idx < history.lastIndex) {
                            HorizontalDivider(color = studio.hairlineBorder)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun TelemetryLine(label: String, value: String) {
    val studio = LocalStudioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = studio.mutedGraphite,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = studio.primaryInk,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PrintHistoryRow(entry: PrintHistoryEntity) {
    val studio = LocalStudioColors.current
    val (badgeColor, badgeBg) = when (entry.status) {
        PrintJobStatus.COMPLETED.displayName -> studio.emerald to studio.emeraldSoft
        PrintJobStatus.CANCELLED.displayName -> studio.amber to studio.amberSoft
        else -> studio.crimson to studio.crimsonSoft
    }
    val formattedDate = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        .format(Date(entry.timestampMillis))
    val pageText = if (entry.pageCount == 1) "1 page" else "${entry.pageCount} pages"
    val copyText = if (entry.copies == 1) "1 copy" else "${entry.copies} copies"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.documentTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadgePill(label = entry.status, textColor = badgeColor, backgroundColor = badgeBg)
        }
        Text(
            text = "$formattedDate · $pageText · $copyText · ${entry.paperSize} · ${entry.colorMode}",
            style = MaterialTheme.typography.bodySmall,
            color = studio.mutedGraphite
        )
        Text(
            text = "${entry.resolvedProtocol} · ${PrintStudioViewModel.formatBytes(entry.bytesSent)}",
            style = MaterialTheme.typography.bodySmall,
            color = studio.mutedGraphite
        )
    }
}
