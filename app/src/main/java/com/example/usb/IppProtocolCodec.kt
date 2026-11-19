package com.example.usb

import com.example.model.IppCapabilities
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object IppProtocolCodec {

    private const val IPP_VERSION_MAJOR: Byte = 0x02
    private const val IPP_VERSION_MINOR: Byte = 0x00

    private const val OP_PRINT_JOB: Short = 0x0002
    private const val OP_GET_PRINTER_ATTRIBUTES: Short = 0x000B

    private const val TAG_OPERATION_ATTRIBUTES: Byte = 0x01
    private const val TAG_JOB_ATTRIBUTES: Byte = 0x02
    private const val TAG_END_OF_ATTRIBUTES: Byte = 0x03

    private const val TAG_INTEGER: Byte = 0x21
    private const val TAG_BOOLEAN: Byte = 0x22
    private const val TAG_RESOLUTION: Byte = 0x32
    private const val TAG_TEXT_WITHOUT_LANG: Byte = 0x41
    private const val TAG_NAME_WITHOUT_LANG: Byte = 0x42
    private const val TAG_KEYWORD: Byte = 0x44
    private const val TAG_URI: Byte = 0x45
    private const val TAG_CHARSET: Byte = 0x47
    private const val TAG_NATURAL_LANGUAGE: Byte = 0x48
    private const val TAG_MIME_MEDIA_TYPE: Byte = 0x49

    data class HttpIppResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val ippBody: ByteArray
    )

    data class IppOperationStatus(
        val httpStatusCode: Int,
        val ippStatusCode: Int,
        val statusMessage: String = ""
    ) {
        val isSuccess: Boolean
            get() = httpStatusCode in 200..299 && ippStatusCode in 0x0000..0x00FF
    }

    /**
     * Parses an HTTP + IPP response and extracts both the HTTP status code and the 2-byte big-endian
     * IPP status-code (plus any optional 'status-message' operation attribute).
     */
    fun parseIppOperationStatus(rawHttpBytes: ByteArray): IppOperationStatus? {
        val http = parseHttpIppResponse(rawHttpBytes) ?: return null
        if (http.ippBody.size < 8) {
            return IppOperationStatus(
                httpStatusCode = http.statusCode,
                ippStatusCode = if (http.statusCode in 200..299) 0x0000 else 0x0500,
                statusMessage = if (http.statusCode in 200..299) "" else "HTTP status ${http.statusCode}"
            )
        }
        val buf = ByteBuffer.wrap(http.ippBody).order(ByteOrder.BIG_ENDIAN)
        buf.get() // major
        buf.get() // minor
        val ippStatus = buf.short.toInt() and 0xFFFF
        buf.int // requestId

        var message = ""
        var currentAttrName = ""
        while (buf.hasRemaining()) {
            val tag = buf.get()
            if (tag == TAG_END_OF_ATTRIBUTES) break
            if (tag.toInt() in 0x00..0x0F) {
                currentAttrName = ""
                continue
            }
            if (buf.remaining() < 2) break
            val nameLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < nameLen + 2) break
            if (nameLen > 0) {
                val nb = ByteArray(nameLen)
                buf.get(nb)
                currentAttrName = String(nb, StandardCharsets.UTF_8)
            }
            val valLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < valLen) break
            val vb = ByteArray(valLen)
            buf.get(vb)
            if (currentAttrName == "status-message" &&
                (tag == TAG_TEXT_WITHOUT_LANG || tag == TAG_NAME_WITHOUT_LANG)
            ) {
                message = String(vb, StandardCharsets.UTF_8).trim()
            }
        }
        return IppOperationStatus(
            httpStatusCode = http.statusCode,
            ippStatusCode = ippStatus,
            statusMessage = message
        )
    }

    /**
     * Reads the HTTP/1.1 + IPP response after a Print-Job request and throws an [java.io.IOException]
     * if the printer returns an HTTP error or an IPP rejection status code (>= 0x0200).
     */
    fun readAndVerifyPrintJobResponse(
        transport: UsbByteTransport,
        maxWaitMs: Long = 6000L
    ): IppOperationStatus? {
        val responseBuffer = ByteArrayOutputStream()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val chunk = transport.read(8192, 1000)
            if (chunk.isNotEmpty()) {
                responseBuffer.write(chunk)
                val accumulated = responseBuffer.toByteArray()
                if (isHttpResponseComplete(accumulated)) {
                    break
                }
            } else if (responseBuffer.size() > 0) {
                break
            }
        }
        val raw = responseBuffer.toByteArray()
        if (raw.isEmpty()) return null

        val status = parseIppOperationStatus(raw) ?: return null
        if (!status.isSuccess) {
            val detail = status.statusMessage.ifBlank { describeIppStatusCode(status.ippStatusCode) }
            throw java.io.IOException(
                "IPP printer rejected job (HTTP ${status.httpStatusCode}, IPP 0x%04X: %s)".format(
                    status.ippStatusCode,
                    detail
                )
            )
        }
        return status
    }

    fun describeIppStatusCode(code: Int): String = when (code) {
        0x0000 -> "successful-ok"
        0x0001 -> "successful-ok-ignored-or-substituted-attributes"
        0x0002 -> "successful-ok-conflicting-attributes"
        0x0400 -> "client-error-bad-request"
        0x0401 -> "client-error-forbidden"
        0x0404 -> "client-error-not-found"
        0x0406 -> "client-error-not-possible"
        0x040A -> "client-error-document-format-not-supported"
        0x040B -> "client-error-attributes-or-values-not-supported"
        0x0413 -> "client-error-document-format-error"
        0x0500 -> "server-error-internal-error"
        0x0501 -> "server-error-operation-not-supported"
        0x0502 -> "server-error-service-unavailable"
        0x0504 -> "server-error-device-error"
        0x0507 -> "server-error-busy"
        0x0508 -> "server-error-job-canceled"
        else -> if (code >= 0x0500) "server-error" else if (code >= 0x0400) "client-error" else "status-0x%04X".format(code)
    }

    fun buildGetPrinterAttributesIppPayload(
        printerUri: String = "ipp://localhost/ipp/print",
        requestId: Int = 1
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(IPP_VERSION_MAJOR.toInt())
        out.write(IPP_VERSION_MINOR.toInt())
        writeShortBE(out, OP_GET_PRINTER_ATTRIBUTES.toInt())
        writeIntBE(out, requestId)

        out.write(TAG_OPERATION_ATTRIBUTES.toInt())
        writeStringAttr(out, TAG_CHARSET, "attributes-charset", "utf-8")
        writeStringAttr(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        writeStringAttr(out, TAG_URI, "printer-uri", printerUri)

        val requested = listOf(
            "printer-make-and-model",
            "color-supported",
            "document-format-supported",
            "printer-resolution-default",
            "media-supported"
        )
        requested.forEachIndexed { idx, attr ->
            writeStringAttr(
                out = out,
                tag = TAG_KEYWORD,
                name = if (idx == 0) "requested-attributes" else "",
                value = attr
            )
        }
        out.write(TAG_END_OF_ATTRIBUTES.toInt())
        return out.toByteArray()
    }

    fun buildPrintJobIppHeader(
        documentFormat: String,
        jobName: String,
        copies: Int = 1,
        mediaKeyword: String? = null,
        printerUri: String = "ipp://localhost/ipp/print",
        requestId: Int = 2
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(IPP_VERSION_MAJOR.toInt())
        out.write(IPP_VERSION_MINOR.toInt())
        writeShortBE(out, OP_PRINT_JOB.toInt())
        writeIntBE(out, requestId)

        out.write(TAG_OPERATION_ATTRIBUTES.toInt())
        writeStringAttr(out, TAG_CHARSET, "attributes-charset", "utf-8")
        writeStringAttr(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        writeStringAttr(out, TAG_URI, "printer-uri", printerUri)
        writeStringAttr(out, TAG_NAME_WITHOUT_LANG, "requesting-user-name", "UniversalUSBPrinter")
        writeStringAttr(out, TAG_NAME_WITHOUT_LANG, "job-name", jobName.take(127))
        writeStringAttr(out, TAG_MIME_MEDIA_TYPE, "document-format", documentFormat)

        if (copies > 1 || !mediaKeyword.isNullOrBlank()) {
            out.write(TAG_JOB_ATTRIBUTES.toInt())
            if (copies > 1) {
                writeIntAttr(out, "copies", copies)
            }
            if (!mediaKeyword.isNullOrBlank()) {
                writeStringAttr(out, TAG_KEYWORD, "media", mediaKeyword)
            }
        }

        out.write(TAG_END_OF_ATTRIBUTES.toInt())
        return out.toByteArray()
    }

    fun wrapInHttpPostRequest(
        ippPayloadLength: Long?,
        chunked: Boolean = false,
        path: String = "/ipp/print",
        host: String = "localhost"
    ): ByteArray {
        val header = buildString {
            append("POST ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Content-Type: application/ipp\r\n")
            append("User-Agent: UniversalUSBPrinter/1.0\r\n")
            if (chunked || ippPayloadLength == null) {
                append("Transfer-Encoding: chunked\r\n")
            } else {
                append("Content-Length: ").append(ippPayloadLength).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        return header.toByteArray(StandardCharsets.US_ASCII)
    }

    fun encodeHttpChunk(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        if (length <= 0) return ByteArray(0)
        val prefix = "${length.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII)
        val suffix = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(prefix.size + length + suffix.size)
        System.arraycopy(prefix, 0, out, 0, prefix.size)
        System.arraycopy(data, offset, out, prefix.size, length)
        System.arraycopy(suffix, 0, out, prefix.size + length, suffix.size)
        return out
    }

    fun encodeHttpFinalChunk(): ByteArray = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

    /**
     * Parses a raw HTTP/1.1 response over IPP-over-USB bulk input, supporting both
     * Content-Length and Transfer-Encoding: chunked framing.
     */
    fun parseHttpIppResponse(rawBytes: ByteArray): HttpIppResponse? {
        val headerEndIdx = findHeaderSeparator(rawBytes) ?: return null
        val headerText = String(rawBytes, 0, headerEndIdx, StandardCharsets.US_ASCII)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null

        val statusParts = lines[0].split(' ', limit = 3)
        val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: return null

        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }

        val bodyStart = headerEndIdx + 4
        val bodyBytes = if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            decodeChunkedBody(rawBytes, bodyStart)
        } else {
            val contentLength = headers["content-length"]?.toIntOrNull()
            val available = (rawBytes.size - bodyStart).coerceAtLeast(0)
            val copyLen = if (contentLength != null) minOf(contentLength, available) else available
            rawBytes.copyOfRange(bodyStart, bodyStart + copyLen)
        }

        return HttpIppResponse(statusCode = statusCode, headers = headers, ippBody = bodyBytes)
    }

    fun isHttpResponseComplete(rawBytes: ByteArray): Boolean {
        val headerEndIdx = findHeaderSeparator(rawBytes) ?: return false
        val headerText = String(rawBytes, 0, headerEndIdx, StandardCharsets.US_ASCII).lowercase()
        val bodyStart = headerEndIdx + 4
        if (headerText.contains("transfer-encoding: chunked")) {
            // Look for terminating chunk "0\r\n\r\n"
            val tail = String(rawBytes, bodyStart, rawBytes.size - bodyStart, StandardCharsets.ISO_8859_1)
            return tail.contains("0\r\n\r\n")
        }
        val contentLengthRegex = Regex("content-length:\\s*(\\d+)")
        val match = contentLengthRegex.find(headerText)
        if (match != null) {
            val expectedLen = match.groupValues[1].toIntOrNull() ?: return false
            return rawBytes.size - bodyStart >= expectedLen
        }
        return false
    }

    fun parseIppAttributes(ippBytes: ByteArray): IppCapabilities? {
        if (ippBytes.size < 9) return null
        val buf = ByteBuffer.wrap(ippBytes).order(ByteOrder.BIG_ENDIAN)
        buf.get() // major
        buf.get() // minor
        val statusCode = buf.short.toInt() and 0xFFFF
        buf.int // requestId

        if (statusCode >= 0x0400) {
            return null
        }

        var makeAndModel = ""
        var colorSupported: Boolean? = null
        val formats = mutableListOf<String>()
        var defaultResolutionDpi: Int? = null
        val media = mutableListOf<String>()

        var currentAttrName = ""

        while (buf.hasRemaining()) {
            val tag = buf.get()
            if (tag == TAG_END_OF_ATTRIBUTES) break
            // Delimiter tags are 0x00..0x0F
            if (tag.toInt() in 0x00..0x0F) {
                currentAttrName = ""
                continue
            }

            if (buf.remaining() < 2) break
            val nameLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < nameLen + 2) break

            if (nameLen > 0) {
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                currentAttrName = String(nameBytes, StandardCharsets.UTF_8)
            }

            val valLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < valLen) break
            val valBytes = ByteArray(valLen)
            buf.get(valBytes)

            when (currentAttrName) {
                "printer-make-and-model" -> {
                    if (tag == TAG_TEXT_WITHOUT_LANG || tag == TAG_NAME_WITHOUT_LANG) {
                        makeAndModel = String(valBytes, StandardCharsets.UTF_8).trim()
                    }
                }
                "color-supported" -> {
                    if (tag == TAG_BOOLEAN && valBytes.isNotEmpty()) {
                        colorSupported = valBytes[0].toInt() != 0
                    }
                }
                "document-format-supported" -> {
                    if (tag == TAG_MIME_MEDIA_TYPE || tag == TAG_KEYWORD) {
                        val fmt = String(valBytes, StandardCharsets.UTF_8).trim().lowercase()
                        if (fmt.isNotEmpty() && !formats.contains(fmt)) formats.add(fmt)
                    }
                }
                "printer-resolution-default" -> {
                    if (tag == TAG_RESOLUTION && valBytes.size >= 9) {
                        val rBuf = ByteBuffer.wrap(valBytes).order(ByteOrder.BIG_ENDIAN)
                        val xRes = rBuf.int
                        rBuf.int // yRes
                        val units = rBuf.get().toInt() and 0xFF
                        defaultResolutionDpi = if (units == 4) {
                            (xRes * 2.54).toInt()
                        } else {
                            xRes
                        }
                    }
                }
                "media-supported" -> {
                    if (tag == TAG_KEYWORD || tag == TAG_NAME_WITHOUT_LANG) {
                        val m = String(valBytes, StandardCharsets.UTF_8).trim()
                        if (m.isNotEmpty() && !media.contains(m)) media.add(m)
                    }
                }
            }
        }

        return IppCapabilities(
            makeAndModel = makeAndModel,
            colorSupported = colorSupported,
            documentFormatsSupported = formats,
            defaultResolutionDpi = defaultResolutionDpi,
            mediaSupported = media
        )
    }

    private fun decodeChunkedBody(bytes: ByteArray, start: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = start
        while (pos < bytes.size) {
            val crlf = findCrlf(bytes, pos) ?: break
            val sizeLine = String(bytes, pos, crlf - pos, StandardCharsets.US_ASCII)
                .substringBefore(';')
                .trim()
            val chunkSize = sizeLine.toIntOrNull(16) ?: break
            pos = crlf + 2
            if (chunkSize == 0) break
            val available = (bytes.size - pos).coerceAtMost(chunkSize)
            if (available > 0) {
                out.write(bytes, pos, available)
                pos += available
            }
            if (pos + 1 < bytes.size && bytes[pos] == '\r'.code.toByte() && bytes[pos + 1] == '\n'.code.toByte()) {
                pos += 2
            }
        }
        return out.toByteArray()
    }

    private fun findHeaderSeparator(bytes: ByteArray): Int? {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun findCrlf(bytes: ByteArray, start: Int): Int? {
        for (i in start until bytes.size - 1) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte()) {
                return i
            }
        }
        return null
    }

    private fun writeShortBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeStringAttr(out: ByteArrayOutputStream, tag: Byte, name: String, value: String) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val valBytes = value.toByteArray(StandardCharsets.UTF_8)
        out.write(tag.toInt())
        writeShortBE(out, nameBytes.size)
        if (nameBytes.isNotEmpty()) out.write(nameBytes)
        writeShortBE(out, valBytes.size)
        if (valBytes.isNotEmpty()) out.write(valBytes)
    }

    private fun writeIntAttr(out: ByteArrayOutputStream, name: String, value: Int) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        out.write(TAG_INTEGER.toInt())
        writeShortBE(out, nameBytes.size)
        out.write(nameBytes)
        writeShortBE(out, 4)
        writeIntBE(out, value)
    }
}
