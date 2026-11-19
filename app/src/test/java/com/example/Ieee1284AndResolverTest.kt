package com.example

import com.example.data.PrinterDeviceOverride
import com.example.model.ColorOverrideMode
import com.example.model.DriverProtocol
import com.example.model.Ieee1284Info
import com.example.model.IppCapabilities
import com.example.model.UsbDeviceInfo
import com.example.usb.Ieee1284Parser
import com.example.usb.PrinterCapabilityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class Ieee1284AndResolverTest {

    @Test
    fun `parseDeviceId extracts MFG, MDL, CMD tokens and CLS`() {
        val idString = "MFG:Samsung;CMD:QPDL,SPL;MDL:SCX-3200 Series;CLS:PRINTER;"
        val body = idString.toByteArray(StandardCharsets.US_ASCII)
        val totalLen = body.size + 2
        val raw = byteArrayOf(((totalLen ushr 8) and 0xFF).toByte(), (totalLen and 0xFF).toByte()) + body

        val parsed = Ieee1284Parser.parseDeviceId(raw)
        assertEquals("Samsung", parsed.manufacturer)
        assertEquals("SCX-3200 Series", parsed.model)
        assertEquals(listOf("QPDL", "SPL"), parsed.commandSetTokens)
        assertEquals("PRINTER", parsed.deviceClass)
        assertFalse(Ieee1284Parser.isThermalReceiptOnly(parsed))
    }

    @Test
    fun `parsePortStatus decodes Paper Empty, Selected, and No Error bits`() {
        val ready = Ieee1284Parser.parsePortStatus(0x18) // 0x10 Selected | 0x08 No Error
        assertFalse(ready.paperEmpty)
        assertTrue(ready.selected)
        assertTrue(ready.noError)

        val outOfPaper = Ieee1284Parser.parsePortStatus(0x30) // 0x20 Paper Empty | 0x10 Selected | !0x08 Error
        assertTrue(outOfPaper.paperEmpty)
        assertTrue(outOfPaper.selected)
        assertFalse(outOfPaper.noError)
    }

    @Test
    fun `isThermalReceiptOnly excludes ESC POS printers`() {
        val receipt = Ieee1284Info(commandSetTokens = listOf("ESCPOS"))
        assertTrue(Ieee1284Parser.isThermalReceiptOnly(receipt))
    }

    @Test
    fun `resolver selects QPDL 0x11 for Samsung SCX-3200`() {
        val device = UsbDeviceInfo(1, 0x04E8, 0x3441, "/dev/bus/usb/001/002", "SCX-3200", "Samsung", false, true, 2)
        val ieee = Ieee1284Info(manufacturer = "Samsung", model = "SCX-3200", commandSetTokens = listOf("QPDL"))
        val profile = PrinterCapabilityResolver.resolve(device, ieee, null)
        assertEquals(DriverProtocol.SAMSUNG_QPDL_0X11, profile.effectiveProtocol)
        assertFalse(profile.effectiveColorSupported)
        assertFalse(profile.isBestEffortFallback)
        assertNull(profile.unverifiedLanguageWarning)
    }

    @Test
    fun `resolver uses PCL or PostScript on newer Samsung models that report standard PDLs`() {
        val samsungPcl6 = UsbDeviceInfo(10, 0x04E8, 0x3999, "/dev/usb", "ProXpress M4020", "Samsung", false, true, 2)
        val ieeePcl6 = Ieee1284Info(manufacturer = "Samsung", model = "ProXpress M4020", commandSetTokens = listOf("SPL", "PCL6", "PS3"))
        val profilePcl6 = PrinterCapabilityResolver.resolve(samsungPcl6, ieeePcl6, null)
        assertEquals(DriverProtocol.PCL_6_XL, profilePcl6.effectiveProtocol)
        assertFalse(profilePcl6.isBestEffortFallback)

        val samsungPs = UsbDeviceInfo(11, 0x04E8, 0x3998, "/dev/usb", "Laser M3320", "Samsung", false, true, 2)
        val ieeePs = Ieee1284Info(manufacturer = "Samsung", model = "Laser M3320", commandSetTokens = listOf("POSTSCRIPT"))
        val profilePs = PrinterCapabilityResolver.resolve(samsungPs, ieeePs, null)
        assertEquals(DriverProtocol.POSTSCRIPT, profilePs.effectiveProtocol)
        assertFalse(profilePs.isBestEffortFallback)
    }

    @Test
    fun `resolver marks PCL 3 and proprietary host-based command sets UNSUPPORTED unless overridden`() {
        val unsupportedCmdSets = listOf(
            listOf("PCL3GUI", "PJL"),
            listOf("PCL3"),
            listOf("PCL 3"),
            listOf("ZJS"),
            listOf("ZJSTREAM"),
            listOf("CAPT"),
            listOf("UFR"),
            listOf("UFRII"),
            listOf("GDI"),
            listOf("ESCPR"),
            listOf("ESC/P"),
            listOf("LIDIL"),
            listOf("HBP")
        )
        for (tokens in unsupportedCmdSets) {
            val device = UsbDeviceInfo(12, 0x03F0, 0x1111, "/dev/usb", "Host Printer", "Vendor", false, true, 2)
            val ieee = Ieee1284Info(manufacturer = "Vendor", model = "Host Printer", commandSetTokens = tokens)
            val profile = PrinterCapabilityResolver.resolve(device, ieee, null)
            assertEquals("Expected UNSUPPORTED for CMD $tokens", DriverProtocol.UNSUPPORTED, profile.effectiveProtocol)
            assertNotNull(profile.unverifiedLanguageWarning)
            assertTrue(
                profile.unverifiedLanguageWarning!!.contains(PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE)
            )
        }

        // Manual override unblocks an UNSUPPORTED printer
        val overridden = PrinterCapabilityResolver.resolve(
            deviceInfo = UsbDeviceInfo(13, 0x03F0, 0x1111, "/dev/usb", "Host Printer", "HP", false, true, 2),
            ieee1284 = Ieee1284Info(commandSetTokens = listOf("LIDIL")),
            ipp = null,
            override = PrinterDeviceOverride(protocolOverride = DriverProtocol.PCL_5E_MONO)
        )
        assertEquals(DriverProtocol.PCL_5E_MONO, overridden.effectiveProtocol)
        assertFalse(DriverProtocol.manualSelectableEntries.contains(DriverProtocol.UNSUPPORTED))
    }

    @Test
    fun `resolver marks URF-only or image-only IPP-USB printers UNSUPPORTED instead of IPP_USB_PDF`() {
        val urfDevice = UsbDeviceInfo(14, 0x04A9, 0x2222, "/dev/usb", "AirPrintOnly", "Canon", true, true, 4)
        val urfOnlyIpp = IppCapabilities("Canon URF", true, listOf("image/urf", "image/jpeg"), 600)
        val profile = PrinterCapabilityResolver.resolve(urfDevice, Ieee1284Info(), urfOnlyIpp)
        assertEquals(DriverProtocol.UNSUPPORTED, profile.effectiveProtocol)
        assertTrue(profile.resolutionReason.contains(PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE))
        assertTrue(profile.unverifiedLanguageWarning!!.contains(PrinterCapabilityResolver.UNSUPPORTED_DRIVER_MESSAGE))
    }

    @Test
    fun `test page verification is waived only for Samsung SCX-3200 unless verified in DataStore`() {
        val scx3200 = UsbDeviceInfo(1, 0x04E8, 0x3441, "/dev/usb", "SCX-3200", "Samsung", false, true, 2)
        val otherPrinter = UsbDeviceInfo(2, 0x03F0, 0x1234, "/dev/usb", "LaserJet", "HP", false, true, 2)

        assertTrue(PrinterCapabilityResolver.isHardwareVerifiedPrinter(scx3200))
        assertFalse(PrinterCapabilityResolver.isHardwareVerifiedPrinter(otherPrinter))
        assertFalse(com.example.viewmodel.PrintStudioViewModel.requiresTestPageBeforeFullJob(scx3200, testPageVerified = false))
        assertTrue(com.example.viewmodel.PrintStudioViewModel.requiresTestPageBeforeFullJob(otherPrinter, testPageVerified = false))
        assertFalse(com.example.viewmodel.PrintStudioViewModel.requiresTestPageBeforeFullJob(otherPrinter, testPageVerified = true))
    }

    @Test
    fun `resolver prioritizes IPP-USB PWG Raster when 7-1-4 present`() {
        val device = UsbDeviceInfo(2, 0x03F0, 0x1234, "/dev/bus/usb/001/003", "OfficeJet", "HP", true, true, 4)
        val ieee = Ieee1284Info(manufacturer = "HP", model = "OfficeJet", commandSetTokens = listOf("PCL5C"))
        val ipp = IppCapabilities("HP OfficeJet Pro", true, listOf("image/pwg-raster", "application/pdf"), 600)
        val profile = PrinterCapabilityResolver.resolve(device, ieee, ipp)
        assertEquals(DriverProtocol.IPP_USB_PWG_RASTER, profile.effectiveProtocol)
        assertTrue(profile.effectiveColorSupported)
    }

    @Test
    fun `resolver applies best-effort vendor fallback with warning when CMD is missing`() {
        val hpDevice = UsbDeviceInfo(3, PrinterCapabilityResolver.VID_HP, 0x9999, "/dev/usb", "LaserJet P1102", "HP", false, true, 2)
        val profile = PrinterCapabilityResolver.resolve(hpDevice, Ieee1284Info(), null)
        assertTrue(profile.isBestEffortFallback)
        assertEquals(DriverProtocol.PCL_5E_MONO, profile.effectiveProtocol)
        assertNotNull(profile.unverifiedLanguageWarning)
    }

    @Test
    fun `resolver honors manual protocol and color overrides`() {
        val device = UsbDeviceInfo(4, 0x04F9, 0x0042, "/dev/usb", "HL-L2350DW", "Brother", false, true, 2)
        val override = PrinterDeviceOverride(
            protocolOverride = DriverProtocol.POSTSCRIPT,
            colorOverride = ColorOverrideMode.COLOR
        )
        val profile = PrinterCapabilityResolver.resolve(device, Ieee1284Info(), null, override)
        assertEquals(DriverProtocol.POSTSCRIPT, profile.effectiveProtocol)
        assertTrue(profile.effectiveColorSupported)
        assertTrue(profile.overrideActive)
        assertFalse(profile.isBestEffortFallback)
        assertNull(profile.unverifiedLanguageWarning)
        assertNotNull(profile.resolutionReason)
    }
}
