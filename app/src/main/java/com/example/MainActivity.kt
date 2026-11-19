package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.PrintStudioScreen
import com.example.ui.PrinterHistoryScreen
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.UniversalUsbPrinterTheme
import com.example.viewmodel.AppTab
import com.example.viewmodel.PrintStudioViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PrintStudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            viewModel.handleIncomingIntent(intent)
        }
        setContent {
            UniversalUsbPrinterTheme {
                UniversalUsbPrinterApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalUsbPrinterApp(viewModel: PrintStudioViewModel) {
    val studio = LocalStudioColors.current
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val usbState by viewModel.usbState.collectAsStateWithLifecycle()
    val document by viewModel.document.collectAsStateWithLifecycle()
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()
    val pdfOptions by viewModel.pdfOptions.collectAsStateWithLifecycle()
    val photoLayout by viewModel.photoLayout.collectAsStateWithLifecycle()
    val photoSheetPlan by viewModel.photoSheetPlan.collectAsStateWithLifecycle()
    val previewBitmap by viewModel.previewBitmap.collectAsStateWithLifecycle()
    val currentPreviewPage by viewModel.currentPreviewPage.collectAsStateWithLifecycle()
    val printProgress by viewModel.printProgress.collectAsStateWithLifecycle()
    val history by viewModel.printHistory.collectAsStateWithLifecycle()
    val deviceOverride by viewModel.deviceOverride.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val lastPrintError by viewModel.lastPrintError.collectAsStateWithLifecycle()
    val showFallbackConfirmDialog by viewModel.showFallbackConfirmDialog.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    // Back button returns from Printer & History tab to Print Studio
    BackHandler(enabled = activeTab != AppTab.PRINT_STUDIO) {
        viewModel.selectTab(AppTab.PRINT_STUDIO)
    }

    val effectiveMono = remember(usbState, tuning.outputMode) {
        viewModel.isEffectiveMonochrome()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = studio.paperBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = studio.primaryInk
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = studio.paperBackground,
                        titleContentColor = studio.primaryInk
                    )
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = studio.cardSurface,
                        tonalElevation = 0.dp
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            val icon = when (tab) {
                                AppTab.PRINT_STUDIO -> if (selected) Icons.Filled.Print else Icons.Outlined.Print
                                AppTab.PRINTER_HISTORY -> if (selected) Icons.Filled.Build else Icons.Outlined.Build
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                icon = { Icon(icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = studio.activeAccent,
                                    selectedTextColor = studio.activeAccent,
                                    indicatorColor = studio.activeAccentSoft,
                                    unselectedIconColor = studio.mutedGraphite,
                                    unselectedTextColor = studio.mutedGraphite
                                ),
                                modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = studio.cardSurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            val icon = when (tab) {
                                AppTab.PRINT_STUDIO -> if (selected) Icons.Filled.Print else Icons.Outlined.Print
                                AppTab.PRINTER_HISTORY -> if (selected) Icons.Filled.Build else Icons.Outlined.Build
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                icon = { Icon(icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = studio.activeAccent,
                                    selectedTextColor = studio.activeAccent,
                                    indicatorColor = studio.activeAccentSoft,
                                    unselectedIconColor = studio.mutedGraphite,
                                    unselectedTextColor = studio.mutedGraphite
                                ),
                                modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val contentModifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize()

                    when (activeTab) {
                        AppTab.PRINT_STUDIO -> {
                            PrintStudioScreen(
                                usbState = usbState,
                                document = document,
                                tuning = tuning,
                                pdfOptions = pdfOptions,
                                photoLayout = photoLayout,
                                photoSheetPlan = photoSheetPlan,
                                previewBitmap = previewBitmap,
                                currentPreviewPage = currentPreviewPage,
                                effectiveMonochrome = effectiveMono,
                                printProgress = printProgress,
                                primaryCropBitmap = viewModel.getPrimarySourceBitmapForCrop(),
                                testPageVerified = deviceOverride.testPageVerified,
                                lastPrintError = lastPrintError,
                                onDismissPrintError = viewModel::dismissPrintError,
                                onScanUsb = viewModel::scanUsb,
                                onGrantPermission = viewModel::requestUsbPermission,
                                onPhotosPicked = viewModel::loadPhotos,
                                onPdfPicked = viewModel::loadPdf,
                                onLoadDiagnosticSheet = viewModel::loadDiagnosticSheet,
                                onStepPage = viewModel::stepPreviewPage,
                                onUpdateTuning = viewModel::updateTuning,
                                onRotate90 = viewModel::rotate90,
                                onUpdateCrop = viewModel::updateCropRect,
                                onUpdatePdfOptions = viewModel::updatePdfOptions,
                                onUpdatePhotoLayout = viewModel::updatePhotoLayout,
                                onStartPrint = viewModel::startPrint,
                                onPrintTestPage = viewModel::printTestPage,
                                onCancelPrint = viewModel::cancelPrint,
                                showFallbackConfirmDialog = showFallbackConfirmDialog,
                                onDismissFallbackDialog = viewModel::dismissFallbackConfirmDialog,
                                onPrintTinyProbe = viewModel::printTinyProbeFromFallback,
                                onOpenPrinterSettings = viewModel::openPrinterSettingsFromFallback,
                                modifier = contentModifier
                            )
                        }

                        AppTab.PRINTER_HISTORY -> {
                            PrinterHistoryScreen(
                                usbState = usbState,
                                override = deviceOverride,
                                history = history,
                                isPrinting = printProgress.isPrinting,
                                onScanUsb = viewModel::scanUsb,
                                onGrantPermission = viewModel::requestUsbPermission,
                                onSoftReset = viewModel::softResetPrinter,
                                onPrintTestPage = viewModel::printTestPage,
                                onPrintDiagnosticSheet = viewModel::printDiagnosticSheetNow,
                                onSelectProtocolOverride = viewModel::setProtocolOverride,
                                onSelectColorOverride = viewModel::setColorOverride,
                                onClearHistory = viewModel::clearHistory,
                                modifier = contentModifier
                            )
                        }
                    }
                }
            }
        }
    }
}
