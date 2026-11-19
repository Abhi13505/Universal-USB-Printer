package com.example.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.data.PrinterDeviceOverride
import com.example.data.PrinterPreferencesStore
import com.example.model.DriverProtocol
import com.example.model.Ieee1284Info
import com.example.model.IppCapabilities
import com.example.model.UsbDeviceInfo
import com.example.model.UsbPrinterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class UsbPrinterManager(
    private val context: Context,
    private val preferencesStore: PrinterPreferencesStore
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
        private const val USB_CLASS_PRINTER = 7
        private const val USB_SUBCLASS_PRINTER = 1
        private const val PROTOCOL_UNIDIR = 1
        private const val PROTOCOL_BIDIR = 2
        private const val PROTOCOL_IPP_USB = 4

        private const val REQ_GET_DEVICE_ID = 0x00
        private const val REQ_GET_PORT_STATUS = 0x01
        private const val REQ_SOFT_RESET = 0x02

        const val PRINT_BULK_WRITE_TIMEOUT_MS = AndroidUsbBulkTransport.DEFAULT_WRITE_TIMEOUT_MS
        const val PRINT_BULK_WRITE_RETRIES = AndroidUsbBulkTransport.DEFAULT_MAX_WRITE_RETRIES
        private const val PROBE_BULK_WRITE_TIMEOUT_MS = 10_000
        private const val PROBE_BULK_WRITE_RETRIES = 1

        /**
         * USB Printer Class 1.1 Section 4.2.1 (GET_DEVICE_ID):
         * wIndex high byte = interface number, low byte = alternate setting.
         */
        internal fun buildGetDeviceIdIndex(interfaceNumber: Int, alternateSetting: Int): Int {
            return ((interfaceNumber and 0xFF) shl 8) or (alternateSetting and 0xFF)
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _printerState = MutableStateFlow<UsbPrinterState>(UsbPrinterState.Disconnected)
    val printerState: StateFlow<UsbPrinterState> = _printerState.asStateFlow()

    private var activeDevice: UsbDevice? = null
    private var overrideWatchJob: Job? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && isSupportedPrinterDevice(device)) {
                        selectAndProbeDevice(device)
                    } else {
                        scanAttachedPrinters()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && activeDevice?.deviceId == device.deviceId) {
                        activeDevice = null
                        overrideWatchJob?.cancel()
                        _printerState.value = UsbPrinterState.Disconnected
                        scanAttachedPrinters()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && isSupportedPrinterDevice(device)) {
                        if (granted) {
                            probeDeviceAsync(device)
                        } else {
                            _printerState.value = UsbPrinterState.PermissionRequired(buildDeviceInfo(device))
                        }
                    }
                }
            }
        }
    }

    fun startListening() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }
        scanAttachedPrinters()
    }

    fun stopListening() {
        overrideWatchJob?.cancel()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
    }

    fun scanAttachedPrinters() {
        val manager = usbManager
        if (manager == null) {
            _printerState.value = UsbPrinterState.Disconnected
            return
        }
        val printers = manager.deviceList.values.filter { isSupportedPrinterDevice(it) }
        val chosen = printers.firstOrNull()
        if (chosen == null) {
            activeDevice = null
            _printerState.value = UsbPrinterState.Disconnected
            return
        }
        selectAndProbeDevice(chosen)
    }

    fun requestUsbPermission() {
        val manager = usbManager ?: return
        val device = activeDevice ?: manager.deviceList.values.firstOrNull { isSupportedPrinterDevice(it) } ?: return
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        manager.requestPermission(device, pendingIntent)
    }

    private fun selectAndProbeDevice(device: UsbDevice) {
        activeDevice = device
        val manager = usbManager ?: return
        if (!manager.hasPermission(device)) {
            _printerState.value = UsbPrinterState.PermissionRequired(buildDeviceInfo(device))
            return
        }
        probeDeviceAsync(device)
    }

    fun probeDeviceAsync(device: UsbDevice? = activeDevice) {
        val target = device ?: return
        val manager = usbManager ?: return
        if (!manager.hasPermission(target)) {
            _printerState.value = UsbPrinterState.PermissionRequired(buildDeviceInfo(target))
            return
        }
        val info = buildDeviceInfo(target)
        _printerState.value = UsbPrinterState.Probing(info)

        scope.launch {
            val result = probeDeviceSync(target, info)
            _printerState.value = result
            if (result is UsbPrinterState.Ready) {
                watchOverridesForDevice(result)
            }
        }
    }

    private fun watchOverridesForDevice(initialReady: UsbPrinterState.Ready) {
        overrideWatchJob?.cancel()
        overrideWatchJob = scope.launch {
            preferencesStore.observeOverride(initialReady.deviceInfo.vidPidKey).collect { override ->
                val current = _printerState.value
                if (current is UsbPrinterState.Ready && current.deviceInfo.vidPidKey == initialReady.deviceInfo.vidPidKey) {
                    val updatedProfile = PrinterCapabilityResolver.resolve(
                        deviceInfo = current.deviceInfo,
                        ieee1284 = current.ieee1284,
                        ipp = current.ipp,
                        override = override
                    )
                    _printerState.value = current.copy(profile = updatedProfile)
                }
            }
        }
    }

    suspend fun probeDeviceSync(device: UsbDevice, info: UsbDeviceInfo): UsbPrinterState =
        withContext(Dispatchers.IO) {
            val manager = usbManager ?: return@withContext UsbPrinterState.Error("USB Host unavailable", info)
            val connection = manager.openDevice(device)
                ?: return@withContext UsbPrinterState.Error("Failed to open USB device connection", info)

            try {
                // 1. Probe IPP-over-USB (Class 7, Subclass 1, Protocol 4) if present
                val ippIface = findPrinterInterface(device, PROTOCOL_IPP_USB)
                val ippCapabilities: IppCapabilities? = if (ippIface != null) {
                    probeIppOverUsb(connection, ippIface)
                } else {
                    null
                }

                // 2. Probe IEEE 1284 Control Transfers (Class 7, Subclass 1)
                val ctrlIface = findPrinterInterface(device, PROTOCOL_BIDIR)
                    ?: findPrinterInterface(device, PROTOCOL_UNIDIR)
                    ?: ippIface
                    ?: findFallbackInterface(device)

                val ieeeInfo = if (ctrlIface != null) {
                    probeIeee1284(connection, ctrlIface)
                } else {
                    Ieee1284Info()
                }

                // Exclude pure ESC/POS thermal receipt printers unless user has explicit override
                val override = preferencesStore.observeOverride(info.vidPidKey).first()
                if (Ieee1284Parser.isThermalReceiptOnly(ieeeInfo) &&
                    override.protocolOverride == DriverProtocol.AUTO_DETECTED
                ) {
                    return@withContext UsbPrinterState.Error(
                        "Thermal receipt printer (${ieeeInfo.commandSetTokens.joinToString()}) is not supported. Connect a standard office/home printer.",
                        info
                    )
                }

                val profile = PrinterCapabilityResolver.resolve(
                    deviceInfo = info,
                    ieee1284 = ieeeInfo,
                    ipp = ippCapabilities,
                    override = override
                )

                UsbPrinterState.Ready(
                    deviceInfo = info,
                    ieee1284 = ieeeInfo,
                    ipp = ippCapabilities,
                    profile = profile
                )
            } catch (e: Exception) {
                UsbPrinterState.Error(e.message ?: "USB probing error", info)
            } finally {
                connection.close()
            }
        }

    suspend fun performSoftReset(): Result<Unit> = withContext(Dispatchers.IO) {
        val device = activeDevice ?: return@withContext Result.failure(IllegalStateException("No printer attached"))
        val manager = usbManager ?: return@withContext Result.failure(IllegalStateException("USB Manager unavailable"))
        if (!manager.hasPermission(device)) {
            return@withContext Result.failure(SecurityException("USB permission required"))
        }
        val iface = findPrinterInterface(device, PROTOCOL_BIDIR)
            ?: findPrinterInterface(device, PROTOCOL_UNIDIR)
            ?: findPrinterInterface(device, PROTOCOL_IPP_USB)
            ?: findFallbackInterface(device)
            ?: return@withContext Result.failure(IllegalStateException("No printer interface found"))

        val connection = manager.openDevice(device)
            ?: return@withContext Result.failure(IllegalStateException("Unable to open USB device"))

        return@withContext try {
            connection.claimInterface(iface, true)
            // IEEE 1284 Class-specific SOFT_RESET: bmRequestType=0x21, bRequest=0x02
            val res = connection.controlTransfer(
                0x21,
                REQ_SOFT_RESET,
                0,
                iface.id,
                null,
                0,
                3000
            )
            connection.releaseInterface(iface)
            if (res >= 0) {
                probeDeviceAsync(device)
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Soft reset failed (code $res)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection.close()
        }
    }

    /**
     * Opens an exclusive claimed USB interface session for streaming a print job.
     */
    fun openPrintTransport(
        protocol: DriverProtocol,
        onBytesWritten: (Int) -> Unit
    ): Pair<UsbByteTransport, () -> Unit>? {
        val device = activeDevice ?: return null
        val manager = usbManager ?: return null
        if (!manager.hasPermission(device)) return null

        val useIpp = protocol == DriverProtocol.IPP_USB_PWG_RASTER || protocol == DriverProtocol.IPP_USB_PDF
        val iface = if (useIpp) {
            findPrinterInterface(device, PROTOCOL_IPP_USB)
                ?: findPrinterInterface(device, PROTOCOL_BIDIR)
                ?: findPrinterInterface(device, PROTOCOL_UNIDIR)
                ?: findFallbackInterface(device)
        } else {
            findPrinterInterface(device, PROTOCOL_BIDIR)
                ?: findPrinterInterface(device, PROTOCOL_UNIDIR)
                ?: findPrinterInterface(device, PROTOCOL_IPP_USB)
                ?: findFallbackInterface(device)
        } ?: return null

        val connection = manager.openDevice(device) ?: return null
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return null
        }

        val (outEp, inEp) = findBulkEndpoints(iface)
        if (outEp == null) {
            connection.releaseInterface(iface)
            connection.close()
            return null
        }

        val transport = AndroidUsbBulkTransport(
            connection = connection,
            outEndpoint = outEp,
            inEndpoint = inEp,
            writeTimeoutMs = PRINT_BULK_WRITE_TIMEOUT_MS,
            maxWriteRetriesBeforeFirstByte = PRINT_BULK_WRITE_RETRIES,
            onBytesWritten = onBytesWritten
        )
        val cleanup = {
            try {
                connection.releaseInterface(iface)
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
        return transport to cleanup
    }

    private fun probeIppOverUsb(
        connection: UsbDeviceConnection,
        iface: UsbInterface
    ): IppCapabilities? {
        if (!connection.claimInterface(iface, true)) return null
        return try {
            val (outEp, inEp) = findBulkEndpoints(iface)
            if (outEp == null || inEp == null) return null

            val transport = AndroidUsbBulkTransport(
                connection = connection,
                outEndpoint = outEp,
                inEndpoint = inEp,
                writeTimeoutMs = PROBE_BULK_WRITE_TIMEOUT_MS,
                maxWriteRetriesBeforeFirstByte = PROBE_BULK_WRITE_RETRIES
            )
            val ippBody = IppProtocolCodec.buildGetPrinterAttributesIppPayload()
            val httpHeader = IppProtocolCodec.wrapInHttpPostRequest(ippBody.size.toLong())
            transport.write(httpHeader)
            transport.write(ippBody)

            val responseBuffer = ByteArrayOutputStream()
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 3500) {
                val chunk = transport.read(16384, 800)
                if (chunk.isNotEmpty()) {
                    responseBuffer.write(chunk)
                    val accumulated = responseBuffer.toByteArray()
                    if (IppProtocolCodec.isHttpResponseComplete(accumulated)) {
                        break
                    }
                } else if (responseBuffer.size() > 0) {
                    break
                }
            }
            val parsedHttp = IppProtocolCodec.parseHttpIppResponse(responseBuffer.toByteArray())
            parsedHttp?.let { IppProtocolCodec.parseIppAttributes(it.ippBody) }
        } catch (_: Exception) {
            null
        } finally {
            try {
                connection.releaseInterface(iface)
            } catch (_: Exception) {
            }
        }
    }

    private fun probeIeee1284(
        connection: UsbDeviceConnection,
        iface: UsbInterface
    ): Ieee1284Info {
        var info = Ieee1284Info()
        val claimed = connection.claimInterface(iface, true)
        try {
            val idBuffer = ByteArray(1024)
            val deviceIdIndex = buildGetDeviceIdIndex(iface.id, iface.alternateSetting)
            val idLen = connection.controlTransfer(
                0xA1,
                REQ_GET_DEVICE_ID,
                0,
                deviceIdIndex,
                idBuffer,
                idBuffer.size,
                2500
            )
            if (idLen > 2) {
                info = Ieee1284Parser.parseDeviceId(idBuffer.copyOf(idLen))
            } else {
                // Legacy fallback for non-compliant USB bridge firmware expecting raw interface index
                val retryLen = connection.controlTransfer(
                    0xA1,
                    REQ_GET_DEVICE_ID,
                    0,
                    iface.id and 0xFF,
                    idBuffer,
                    idBuffer.size,
                    2500
                )
                if (retryLen > 2) {
                    info = Ieee1284Parser.parseDeviceId(idBuffer.copyOf(retryLen))
                }
            }

            val statusBuf = ByteArray(1)
            val statusLen = connection.controlTransfer(
                0xA1,
                REQ_GET_PORT_STATUS,
                0,
                iface.id,
                statusBuf,
                1,
                1500
            )
            if (statusLen == 1) {
                info = info.copy(portStatus = Ieee1284Parser.parsePortStatus(statusBuf[0].toInt()))
            }
        } finally {
            if (claimed) {
                try {
                    connection.releaseInterface(iface)
                } catch (_: Exception) {
                }
            }
        }
        return info
    }

    private fun isSupportedPrinterDevice(device: UsbDevice): Boolean {
        if (device.vendorId == PrinterCapabilityResolver.VID_SAMSUNG && device.productId == 0x3441) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_PRINTER && iface.interfaceSubclass == USB_SUBCLASS_PRINTER) {
                return true
            }
        }
        return false
    }

    private fun buildDeviceInfo(device: UsbDevice): UsbDeviceInfo {
        var hasIpp = false
        var hasBulk = false
        var primaryProto = 2
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_PRINTER && iface.interfaceSubclass == USB_SUBCLASS_PRINTER) {
                if (iface.interfaceProtocol == PROTOCOL_IPP_USB) hasIpp = true
                if (iface.interfaceProtocol == PROTOCOL_BIDIR || iface.interfaceProtocol == PROTOCOL_UNIDIR) {
                    hasBulk = true
                    primaryProto = iface.interfaceProtocol
                }
            }
        }
        if (hasIpp && !hasBulk) primaryProto = PROTOCOL_IPP_USB
        return UsbDeviceInfo(
            deviceId = device.deviceId,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.deviceName,
            productName = device.productName,
            manufacturerName = device.manufacturerName,
            hasIppInterface = hasIpp,
            hasBulkInterface = hasBulk,
            activeInterfaceProtocol = primaryProto
        )
    }

    private fun findPrinterInterface(device: UsbDevice, protocol: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_PRINTER &&
                iface.interfaceSubclass == USB_SUBCLASS_PRINTER &&
                iface.interfaceProtocol == protocol
            ) {
                return iface
            }
        }
        return null
    }

    private fun findFallbackInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_PRINTER) return iface
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) {
                    outEp = ep
                } else if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) {
                    inEp = ep
                }
            }
        }
        return outEp to inEp
    }
}
