package com.example.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException

interface UsbByteTransport {
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int
    fun read(maxLength: Int, timeoutMs: Int = 3000): ByteArray
    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int = 2000
    ): Int
}

class AndroidUsbBulkTransport(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint?,
    private val inEndpoint: UsbEndpoint?,
    private val chunkSize: Int = 16 * 1024,
    private val onBytesWritten: ((Int) -> Unit)? = null
) : UsbByteTransport {

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        val ep = outEndpoint ?: throw IOException("USB Bulk OUT endpoint unavailable")
        var totalWritten = 0
        var cursor = offset
        val end = offset + length

        while (cursor < end) {
            val blockLen = minOf(chunkSize, end - cursor)
            val slice = if (cursor == 0 && blockLen == data.size) {
                data
            } else {
                data.copyOfRange(cursor, cursor + blockLen)
            }
            val written = connection.bulkTransfer(ep, slice, blockLen, 8000)
            if (written < 0) {
                throw IOException("USB bulk write failed ($written) at offset $cursor")
            }
            if (written == 0) {
                throw IOException("USB bulk write timed out with 0 bytes transferred")
            }
            totalWritten += written
            cursor += written
            onBytesWritten?.invoke(written)
        }
        return totalWritten
    }

    override fun read(maxLength: Int, timeoutMs: Int): ByteArray {
        val ep = inEndpoint ?: return ByteArray(0)
        val buffer = ByteArray(maxLength)
        val readCount = connection.bulkTransfer(ep, buffer, maxLength, timeoutMs)
        return if (readCount > 0) buffer.copyOf(readCount) else ByteArray(0)
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int
    ): Int {
        return connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
    }
}
