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
    const val PID_SAMSUNG_SCX3200 = 0x3441
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
        val cmdTokens = ieee1284.commandSetTokens.map { it.trim().uppercase() }.filter { it.isNotEmpty() }

        val autoRes = resolveAuto(
            deviceInfo = deviceInfo,
            cmdTokens = cmdTokens,
            modelHint = makeModel.uppercase(),
            ipp = ipp
        )

        val hasManualProtocol = override.protocolOverride != DriverProtocol.AUTO_DETECTED
        val effectiveProtocol = if (hasManualProtocol) {
            override.protocolOverride
        } else {
            autoRes.protocol
        }

        val effectiveColor = when (override.colorOverride) {
            ColorOverrideMode.AUTO -> {
                if (hasManualProtocol) {
                    effectiveProtocol.supportsColor && (ipp?.colorSupported ?: true)
                } else {
                    autoRes.colorSupported
                }
            }
            ColorOverrideMode.COLOR -> true
            ColorOverrideMode.MONOCHROME -> false
        }

        val overrideActive = hasManualProtocol || override.colorOverride != ColorOverrideMode.AUTO

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
            isBestEffortFallback = if (hasManualProtocol) false else autoRes.isFallback,
            overrideActive = overrideActive,
            activeInterfaceLabel = interfaceLabel,
            resolutionReason = if (overrideActive) "Manual override active" else autoRes.reason,
            unverifiedLanguageWarning = if (hasManualProtocol) null else autoRes.warning
        )
    }

    private data class AutoResolution(
        val protocol: DriverProtocol,
        val colorSupported: Boolean,
        val isFallback: Boolean,
        val reason: String,
        val warning: String? = null
    )

    private fun resolveAuto(
        deviceInfo: UsbDeviceInfo,
        cmdTokens: List<String>,
        modelHint: String,
        ipp: IppCapabilities?
    ): AutoResolution {
        val hasQpdl = cmdTokens.any { it.contains("QPDL") || it == "SPL" || it.startsWith("SPL") }
        val hasIppFormats = deviceInfo.hasIppInterface && (
            ipp == null ||
                ipp.documentFormatsSupported.isEmpty() ||
                ipp.documentFormatsSupported.any {
                    it.contains("pwg-raster") || it.contains("pdf") || it.contains("urf")
                }
            )
        val hasPdfCmd = cmdTokens.any { it == "PDF" || it.contains("PDF") }
        val hasPcl6 = cmdTokens.any { it.contains("PCL6") || it.contains("PCLXL") || it.contains("PCL-XL") || it == "XL" }
        val hasPcl5c = cmdTokens.any { it.contains("PCL5C") || it.contains("PCL 5C") }
        val hasPcl5e = cmdTokens.any { it.contains("PCL5E") || it.contains("PCL 5E") }
        // Exclude PCL 3 / PCL 3 GUI — older inkjet language that is NOT compatible with PCL 5
        val hasPcl3Only = cmdTokens.any { it.startsWith("PCL3") || it.contains("PCL 3") }
        val hasPcl5Generic = cmdTokens.any {
            it == "PCL5" || it == "PCL 5" || it == "PCL"
        }
        val hasPostScript = cmdTokens.any {
            it.contains("POSTSCRIPT") || it == "PS" || it == "PS2" || it == "PS3" || it.contains("BR-SCRIPT")
        }

        // 1. IPP-USB (7/1/4) present with PWG Raster / PDF
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

        // 2. Explicit standard PDLs reported in IEEE 1284 CMD (PDF, PCL 6, PCL 5, PostScript)
        // Checked before QPDL so newer Samsung models reporting PCL, PostScript, or PDF use standard PDLs.
        if (hasPdfCmd) {
            return AutoResolution(
                protocol = DriverProtocol.PDF_DIRECT,
                colorSupported = ipp?.colorSupported ?: true,
                isFallback = false,
                reason = "IEEE 1284 CMD supports PDF-Direct"
            )
        }

        if (hasPcl6) {
            val color = ipp?.colorSupported ?: inferColorFromModel(modelHint, default = true)
            return AutoResolution(
                protocol = DriverProtocol.PCL_6_XL,
                colorSupported = color,
                isFallback = false,
                reason = "IEEE 1284 CMD supports PCL 6 / PCL-XL"
            )
        }

        if (hasPcl5c || hasPcl5e || hasPcl5Generic) {
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
                reason = "IEEE 1284 CMD supports PCL 5 raster"
            )
        }

        if (hasPostScript) {
            val isColor = ipp?.colorSupported ?: inferColorFromModel(modelHint, default = true)
            return AutoResolution(
                protocol = DriverProtocol.POSTSCRIPT,
                colorSupported = isColor,
                isFallback = false,
                reason = "IEEE 1284 CMD supports PostScript"
            )
        }

        // 3. Explicit QPDL / SPL reported in IEEE 1284 CMD
        if (hasQpdl) {
            val isColor = ipp?.colorSupported == true ||
                modelHint.contains("CLP-") ||
                modelHint.contains("CLX-") ||
                cmdTokens.any { it.contains("SPLC") }
            val protocol = when {
                isColor -> DriverProtocol.SAMSUNG_QPDL_0X15_COLOR
                cmdTokens.any { it.contains("JBIG") } -> DriverProtocol.SAMSUNG_QPDL_0X13_JBIG1
                else -> DriverProtocol.SAMSUNG_QPDL_0X11
            }
            return AutoResolution(
                protocol = protocol,
                colorSupported = isColor,
                isFallback = false,
                reason = "IEEE 1284 CMD contains QPDL/SPL"
            )
        }

        // 4. Known incompatible / host-based languages reported (e.g. PCL 3 inkjet, ZjStream, UFR II, ESC/P2)
        if (hasPcl3Only) {
            val isColor = inferColorFromModel(modelHint, default = true)
            return AutoResolution(
                protocol = if (isColor) DriverProtocol.PCL_5C_COLOR else DriverProtocol.PCL_5E_MONO,
                colorSupported = isColor,
                isFallback = true,
                reason = "Printer reported PCL 3 (${cmdTokens.joinToString()}), which is not PCL 5 compatible",
                warning = "Printer reported PCL 3 (${cmdTokens.joinToString()}), an older inkjet format incompatible with PCL 5. Printing PCL 5 may produce raw @PJL text or garbage lines."
            )
        }

        val reportedCmdSummary = if (cmdTokens.isNotEmpty()) {
            "Printer reported unrecognized CMD (${cmdTokens.joinToString()})"
        } else {
            "Printer did not report any IEEE 1284 CMD language"
        }

        // 5. Nothing standard reported -> Samsung VID fallback (SCX-3200 0x3441 is verified; others require confirmation)
        if (deviceInfo.vendorId == VID_SAMSUNG) {
            val isColor = ipp?.colorSupported == true ||
                modelHint.contains("CLP-") ||
                modelHint.contains("CLX-")
            val protocol = if (isColor) {
                DriverProtocol.SAMSUNG_QPDL_0X15_COLOR
            } else {
                DriverProtocol.SAMSUNG_QPDL_0X11
            }
            val isVerifiedScx3200 = deviceInfo.productId == PID_SAMSUNG_SCX3200
            return AutoResolution(
                protocol = protocol,
                colorSupported = isColor,
                isFallback = !isVerifiedScx3200,
                reason = if (isVerifiedScx3200) {
                    "Verified Samsung SCX-3200 Series (0x04E8:0x3441)"
                } else {
                    "$reportedCmdSummary; guessing Samsung QPDL"
                },
                warning = if (isVerifiedScx3200) null else "$reportedCmdSummary. Guessing Samsung QPDL without verification may waste paper if this model expects PCL or PostScript."
            )
        }

        // 6. Unverified Best-Effort Vendor Fallback (requires explicit user confirmation or tiny probe page before printing)
        return when (deviceInfo.vendorId) {
            VID_HP, VID_BROTHER, VID_LEXMARK, VID_KYOCERA -> {
                val color = inferColorFromModel(modelHint, default = false)
                val proto = if (color) DriverProtocol.PCL_5C_COLOR else DriverProtocol.PCL_5E_MONO
                AutoResolution(
                    protocol = proto,
                    colorSupported = color,
                    isFallback = true,
                    reason = "$reportedCmdSummary; unverified fallback for vendor 0x%04X".format(deviceInfo.vendorId),
                    warning = "$reportedCmdSummary. Many entry-level or host-based models from this brand do not support ${proto.displayName} and will print raw @PJL text or garbage. Run a 1-line probe first or confirm the language."
                )
            }
            VID_CANON, VID_EPSON -> {
                AutoResolution(
                    protocol = DriverProtocol.PCL_5C_COLOR,
                    colorSupported = true,
                    isFallback = true,
                    reason = "$reportedCmdSummary; unverified fallback for vendor 0x%04X".format(deviceInfo.vendorId),
                    warning = "$reportedCmdSummary, and no IPP-over-USB (7/1/4) interface was detected. Most Canon and Epson inkjets/host-based printers do not speak PCL 5c and will print garbage unless confirmed."
                )
            }
            else -> {
                AutoResolution(
                    protocol = DriverProtocol.PCL_5E_MONO,
                    colorSupported = false,
                    isFallback = true,
                    reason = "$reportedCmdSummary; unverified monochrome PCL 5e fallback",
                    warning = "$reportedCmdSummary. Guessing PCL 5e on an unverified printer can print raw @PJL text or garbage. Run a tiny probe page first."
                )
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
