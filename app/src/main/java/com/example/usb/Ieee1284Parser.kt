package com.example.usb

import com.example.model.Ieee1284Info
import com.example.model.PortStatusFlags
import java.nio.charset.StandardCharsets

object Ieee1284Parser {

    /**
     * Parses a raw IEEE 1284 GET_DEVICE_ID byte buffer.
     * Standard format begins with a 2-byte Big-Endian total length prefix followed by
     * semicolon-delimited KEY:VALUE pairs (MFG, MANUFACTURER, MDL, MODEL, CMD, COMMAND SET, CLS).
     */
    fun parseDeviceId(rawBytes: ByteArray): Ieee1284Info {
        if (rawBytes.size < 3) return Ieee1284Info()
        val declaredLength = ((rawBytes[0].toInt() and 0xFF) shl 8) or (rawBytes[1].toInt() and 0xFF)
        val payloadEnd = if (declaredLength in 3..rawBytes.size) {
            declaredLength
        } else {
            rawBytes.size
        }
        val text = String(rawBytes, 2, payloadEnd - 2, StandardCharsets.US_ASCII)
            .trimEnd('\u0000', ' ', '\r', '\n')

        if (text.isBlank()) return Ieee1284Info()

        var mfg = ""
        var mdl = ""
        var cls = ""
        val cmdTokens = mutableListOf<String>()

        text.split(';').forEach { segment ->
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) return@forEach
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) return@forEach
            val key = trimmed.substring(0, colonIdx).trim().uppercase()
            val value = trimmed.substring(colonIdx + 1).trim()
            when (key) {
                "MFG", "MANUFACTURER" -> if (mfg.isEmpty()) mfg = value
                "MDL", "MODEL" -> if (mdl.isEmpty()) mdl = value
                "CLS", "CLASS" -> if (cls.isEmpty()) cls = value
                "CMD", "COMMAND SET" -> {
                    value.split(',')
                        .map { it.trim().uppercase() }
                        .filter { it.isNotEmpty() }
                        .forEach { token ->
                            if (!cmdTokens.contains(token)) cmdTokens.add(token)
                        }
                }
            }
        }

        return Ieee1284Info(
            rawDeviceId = text,
            manufacturer = mfg,
            model = mdl,
            commandSetTokens = cmdTokens,
            deviceClass = cls
        )
    }

    /**
     * Parses the single-byte IEEE 1284 GET_PORT_STATUS response:
     * Bit 5 (0x20): Paper Empty (1 = paper empty)
     * Bit 4 (0x10): Select (1 = printer selected/online)
     * Bit 3 (0x08): Not Error (1 = no error, 0 = fault)
     */
    fun parsePortStatus(statusByte: Int): PortStatusFlags {
        val masked = statusByte and 0xFF
        return PortStatusFlags(
            rawByte = masked,
            paperEmpty = (masked and 0x20) != 0,
            selected = (masked and 0x10) != 0,
            noError = (masked and 0x08) != 0
        )
    }

    /**
     * Excludes dedicated ESC/POS thermal receipt printers when no standard office/home PDL is present.
     */
    fun isThermalReceiptOnly(info: Ieee1284Info): Boolean {
        if (info.commandSetTokens.isEmpty()) return false
        val receiptTokens = setOf("ESCPOS", "ESC/POS", "STAR", "TSPL", "ZPL", "EPL", "CPCL")
        val officeTokens = setOf("QPDL", "SPL", "PCL", "PCL3", "PCL5", "PCL5C", "PCL5E", "PCL6", "PCLXL", "XL", "PDF", "PS", "POSTSCRIPT", "BR-SCRIPT", "PWG", "URF")
        val hasReceipt = info.commandSetTokens.any { token ->
            receiptTokens.any { token.contains(it) }
        }
        val hasOffice = info.commandSetTokens.any { token ->
            officeTokens.any { token.contains(it) }
        }
        return hasReceipt && !hasOffice
    }
}
