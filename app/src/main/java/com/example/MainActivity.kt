package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.PrintStudioScreen
import com.example.ui.PrinterHistoryScreen
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.LocalWindowWidthSizeClass
import com.example.ui.theme.UniversalUsbPrinterTheme
import com.example.viewmodel.AppTab
import com.example.viewmodel.PrintStudioViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PrintStudioViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            viewModel.handleIncomingIntent(intent)
        }
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            UniversalUsbPrinterTheme {
                CompositionLocalProvider(
                    LocalWindowWidthSizeClass provides windowSizeClass.widthSizeClass
                ) {
                    UniversalUsbPrinterApp(viewModel = viewModel)
                }
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
    val widthSizeClass = LocalWindowWidthSizeClass.current
    val useNavRail = widthSizeClass != WindowWidthSizeClass.Compact

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

    var overflowMenuExpanded by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    // Back button returns from Printer tab to Print Studio
    BackHandler(enabled = activeTab != AppTab.PRINT_STUDIO) {
        viewModel.selectTab(AppTab.PRINT_STUDIO)
    }

    val effectiveMono = remember(usbState, tuning.outputMode) {
        viewModel.isEffectiveMonochrome()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = studio.paperBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    expandedHeight = 52.dp,
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = studio.primaryInk,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    },
                    actions = {
                        if (activeTab == AppTab.PRINT_STUDIO) {
                            Box(modifier = Modifier.padding(end = 8.dp)) {
                                IconButton(
                                    onClick = { overflowMenuExpanded = true }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "More options",
                                        tint = studio.primaryInk
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflowMenuExpanded,
                                    onDismissRequest = { overflowMenuExpanded = false },
                                    containerColor = studio.cardSurface
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Load Test Sheet") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.Print,
                                                contentDescription = null,
                                                tint = studio.primaryInk
                                            )
                                        },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            viewModel.loadDiagnosticSheet()
                                        },
                                        modifier = Modifier.testTag("load_diagnostic_button")
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = studio.paperBackground,
                        titleContentColor = studio.primaryInk
                    )
                )
                HorizontalDivider(color = studio.hairlineBorder)
            }
        },
        bottomBar = {
            if (!useNavRail) {
                Column {
                    HorizontalDivider(color = studio.hairlineBorder)
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
                                label = {
                                    Text(
                                        text = tab.label,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                },
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
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
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
                            label = {
                                Text(
                                    text = tab.label,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            },
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
                VerticalDivider(color = studio.hairlineBorder)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
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
                            modifier = Modifier.fillMaxSize()
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
                            modifier = Modifier
                                .widthIn(max = 720.dp)
                                .fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
