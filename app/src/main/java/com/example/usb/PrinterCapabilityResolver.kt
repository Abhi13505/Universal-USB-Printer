package com.example.usb

import com.example.data.PrinterDeviceOverride
import com.example.model.ColorOverrideMode
import com.example.model.DriverProtocol
import com.example.model.Ieee1284Info
import com.example.model.IppCapabilities
import com.example.model.ResolvedPrinterProfile
import com.example.model.UsbDeviceInfo

object PrinterCapabilityResolver {

    const val VID_SAMSUNG = 0x04E8
    const val VID_HP = 0x03F0
    const val VID_BROTHER = 0x04F9
    const val VID_LEXMARK = 0x043D
    const val VID_KYOCERA = 0x0482
    const val VID_CANON = 0x04A9
    const val VID_EPSON = 0x04B8

    fun resolve(
        deviceInfo: UsbDeviceInfo,
        ieee1284: Ieee1284Info,
        ipp: IppCapabilities?,
        override: PrinterDeviceOverride = PrinterDeviceOverride()
    ): ResolvedPrinterProfile {
        val makeModel = buildDisplayName(deviceInfo, ieee1284, ipp)
        val cmdTokens = ieee1284.commandSetTokens.map { it.uppercase() }

        val (autoProtocol, autoColor, isFallback, reason) = resolveAuto(
            deviceInfo = deviceInfo,
            cmdTokens = cmdTokens,
            modelHint = makeModel.uppercase(),
            ipp = ipp
        )

        val effectiveProtocol = if (override.protocolOverride != DriverProtocol.AUTO_DETECTED) {
            override.protocolOverride
        } else {
            autoProtocol
        }

        val effectiveColor = when (override.colorOverride) {
            ColorOverrideMode.AUTO -> {
                if (override.protocolOverride != DriverProtocol.AUTO_DETECTED) {
                    effectiveProtocol.supportsColor && (ipp?.colorSupported ?: true)
                } else {
                    autoColor
                }
            }
            ColorOverrideMode.COLOR -> true
            ColorOverrideMode.MONOCHROME -> false
        }

        val overrideActive = override.protocolOverride != DriverProtocol.AUTO_DETECTED ||
            override.colorOverride != ColorOverrideMode.AUTO

        val interfaceLabel = when {
            deviceInfo.hasIppInterface && (effectiveProtocol == DriverProtocol.IPP_USB_PWG_RASTER ||
                effectiveProtocol == DriverProtocol.IPP_USB_PDF) -> "IPP-USB (7/1/4)"
            deviceInfo.hasBulkInterface -> "Bulk Printer (7/1/${deviceInfo.activeInterfaceProtocol})"
            else -> "USB Printer (${deviceInfo.activeInterfaceProtocol})"
        }

        return ResolvedPrinterProfile(
            makeAndModel = makeModel,
            effectiveProtocol = effectiveProtocol,
            effectiveColorSupported = effectiveColor,
            isBestEffortFallback = if (override.protocolOverride != DriverProtocol.AUTO_DETECTED) false else isFallback,
            overrideActive = overrideActive,
            activeInterfaceLabel = interfaceLabel,
            resolutionReason = if (overrideActive) "Manual override active" else reason
        )
    }

    private data class AutoResolution(
        val protocol: DriverProtocol,
        val colorSupported: Boolean,
        val isFallback: Boolean,
        val reason: String
    )

    private fun resolveAuto(
        deviceInfo: UsbDeviceInfo,
        cmdTokens: List<String>,
        modelHint: String,
        ipp: IppCapabilities?
    ): AutoResolution {
        val hasQpdl = cmdTokens.any { it.contains("QPDL") || it.contains("SPL") }
        val hasIppFormats = deviceInfo.hasIppInterface && (
            ipp == null ||
                ipp.documentFormatsSupported.isEmpty() ||
                ipp.documentFormatsSupported.any {
                    it.contains("pwg-raster") || it.contains("pdf") || it.contains("urf")
                }
            )
        val hasPdfCmd = cmdTokens.any { it == "PDF" || it.contains("PDF") }
        val hasPcl6 = cmdTokens.any { it.contains("PCL6") || it.contains("PCLXL") || it == "XL" }
        val hasPcl5c = cmdTokens.any { it.contains("PCL5C") }
        val hasPcl5e = cmdTokens.any { it.contains("PCL5E") }
        val hasPclGeneric = cmdTokens.any { it.contains("PCL5") || it.contains("PCL3") || it == "PCL" }
        val hasPostScript = cmdTokens.any {
            it.contains("POSTSCRIPT") || it == "PS" || it.contains("PS2") || it.contains("PS3") || it.contains("BR-SCRIPT")
        }

        // 1. QPDL / SPL or Samsung VID
        if (hasQpdl || deviceInfo.vendorId == VID_SAMSUNG) {
            val isColor = ipp?.colorSupported == true ||
                modelHint.contains("CLP-") ||
                modelHint.contains("CLX-") ||
                cmdTokens.any { it.contains("SPLC") }
            val protocol = when {
                isColor -> DriverProtocol.SAMSUNG_QPDL_0X15_COLOR
                cmdTokens.any { it.contains("JBIG") } -> DriverProtocol.SAMSUNG_QPDL_0X13_JBIG1
                else -> DriverProtocol.SAMSUNG_QPDL_0X11
            }
            val reason = if (hasQpdl) {
                "IEEE 1284 CMD contains QPDL/SPL"
            } else {
                "Samsung vendor ID (0x04E8) matched QPDL"
            }
            return AutoResolution(protocol, isColor, isFallback = !hasQpdl, reason = reason)
        }

        // 2. IPP-USB (7/1/4) present with PWG Raster / PDF
        if (deviceInfo.hasIppInterface && hasIppFormats) {
            val supportsColor = ipp?.colorSupported ?: true
            val supportsPwg = ipp?.documentFormatsSupported?.any { it.contains("pwg-raster") } ?: true
            val protocol = if (supportsPwg) {
                DriverProtocol.IPP_USB_PWG_RASTER
            } else {
                DriverProtocol.IPP_USB_PDF
            }
            return AutoResolution(
                protocol = protocol,
                colorSupported = supportsColor,
                isFallback = false,
                reason = "IPP-over-USB (7/1/4) capability probed"
            )
        }

        // 3. PDF in CMD on raw bulk (7/1/2)
        if (hasPdfCmd) {
            return AutoResolution(
                protocol = DriverProtocol.PDF_DIRECT,
                colorSupported = ipp?.colorSupported ?: true,
                isFallback = false,
                reason = "IEEE 1284 CMD supports PDF-Direct"
            )
        }

        // 4. PCL 6 / PCL-XL
        if (hasPcl6) {
            val color = ipp?.colorSupported ?: inferColorFromModel(modelHint, default = true)
            return AutoResolution(
                protocol = DriverProtocol.PCL_6_XL,
                colorSupported = color,
                isFallback = false,
                reason = "IEEE 1284 CMD supports PCL 6 / PCL-XL"
            )
        }

        // 5. PCL 5c / PCL 5e / PCL 3 / PCL
        if (hasPcl5c || hasPcl5e || hasPclGeneric) {
            val isColor = when {
                hasPcl5c -> true
                hasPcl5e -> false
                else -> ipp?.colorSupported ?: inferColorFromModel(modelHint, default = true)
            }
            val protocol = if (isColor) DriverProtocol.PCL_5C_COLOR else DriverProtocol.PCL_5E_MONO
            return AutoResolution(
                protocol = protocol,
                colorSupported = isColor,
                isFallback = false,
                reason = "IEEE 1284 CMD supports PCL raster"
            )
        }

        // 6. PostScript / BR-Script
        if (hasPostScript) {
            val isColor = ipp?.colorSupported ?: inferColorFromModel(modelHint, default = true)
            return AutoResolution(
                protocol = DriverProtocol.POSTSCRIPT,
                colorSupported = isColor,
                isFallback = false,
                reason = "IEEE 1284 CMD supports PostScript"
            )
        }

        // 7. Best-Effort Vendor ID Fallback when CMD is missing or unrecognized
        return when (deviceInfo.vendorId) {
            VID_HP, VID_BROTHER, VID_LEXMARK, VID_KYOCERA -> {
                val color = inferColorFromModel(modelHint, default = false)
                AutoResolution(
                    protocol = if (color) DriverProtocol.PCL_5C_COLOR else DriverProtocol.PCL_5E_MONO,
                    colorSupported = color,
                    isFallback = true,
                    reason = "Best-effort fallback for vendor 0x%04X".format(deviceInfo.vendorId)
                )
            }
            VID_CANON, VID_EPSON -> {
                if (deviceInfo.hasIppInterface) {
                    AutoResolution(
                        protocol = DriverProtocol.IPP_USB_PWG_RASTER,
                        colorSupported = true,
                        isFallback = true,
                        reason = "Best-effort IPP-USB fallback for vendor 0x%04X".format(deviceInfo.vendorId)
                    )
                } else {
                    AutoResolution(
                        protocol = DriverProtocol.PCL_5C_COLOR,
                        colorSupported = true,
                        isFallback = true,
                        reason = "Best-effort PCL 5c fallback for vendor 0x%04X".format(deviceInfo.vendorId)
                    )
                }
            }
            else -> {
                if (deviceInfo.hasIppInterface) {
                    AutoResolution(
                        protocol = DriverProtocol.IPP_USB_PWG_RASTER,
                        colorSupported = ipp?.colorSupported ?: true,
                        isFallback = true,
                        reason = "Best-effort IPP-USB PWG fallback"
                    )
                } else {
                    AutoResolution(
                        protocol = DriverProtocol.PCL_5E_MONO,
                        colorSupported = false,
                        isFallback = true,
                        reason = "Best-effort monochrome PCL 5e fallback"
                    )
                }
            }
        }
    }

    private fun inferColorFromModel(modelUpper: String, default: Boolean): Boolean {
        val monoHints = listOf("MONO", "ML-", "SCX-", "M202", "M207", "HL-L23", "HL-11", "LJ 1", "LASERJET P1", "LASERJET M1")
        if (monoHints.any { modelUpper.contains(it) }) return false
        val colorHints = listOf("COLOR", "INK", "PIXMA", "ECOTANK", "OFFICEJET", "DESKJET", "ENVY", "CLC", "CLP", "CLX")
        if (colorHints.any { modelUpper.contains(it) }) return true
        return default
    }

    private fun buildDisplayName(
        deviceInfo: UsbDeviceInfo,
        ieee1284: Ieee1284Info,
        ipp: IppCapabilities?
    ): String {
        val ippModel = ipp?.makeAndModel
        if (!ippModel.isNullOrBlank()) return ippModel
        val mfg = ieee1284.manufacturer.ifBlank { deviceInfo.manufacturerName.orEmpty() }.trim()
        val mdl = ieee1284.model.ifBlank { deviceInfo.productName.orEmpty() }.trim()
        val combined = listOf(mfg, mdl)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        return combined.ifBlank { "USB Printer (${deviceInfo.vidPidKey})" }
    }
}
