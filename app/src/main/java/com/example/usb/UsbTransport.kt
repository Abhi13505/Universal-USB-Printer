package com.example.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build
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
    private val writeTimeoutMs: Int = DEFAULT_WRITE_TIMEOUT_MS,
    private val maxWriteRetries: Int = DEFAULT_MAX_WRITE_RETRIES,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val onBytesWritten: ((Int) -> Unit)? = null
) : UsbByteTransport {

    companion object {
        const val DEFAULT_WRITE_TIMEOUT_MS = 60_000
        const val DEFAULT_MAX_WRITE_RETRIES = 2
        const val DEFAULT_RETRY_DELAY_MS = 250L

        /**
         * Streams [length] bytes starting at [offset] in [chunkSize]-byte slices, retrying transient
         * timeouts (transfer returning <= 0) up to [maxRetries] times per chunk so busy printers
         * pausing mid-job to heat up or feed paper do not abort the print stream.
         */
        internal fun writeChunkedWithRetry(
            offset: Int,
            length: Int,
            chunkSize: Int,
            maxRetries: Int,
            retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
            onBytesWritten: ((Int) -> Unit)? = null,
            sleepFn: (Long) -> Unit = Thread::sleep,
            isInterruptedFn: () -> Boolean = { Thread.currentThread().isInterrupted },
            transferChunk: (chunkOffset: Int, chunkLength: Int) -> Int
        ): Int {
            var totalWritten = 0
            var cursor = offset
            val end = offset + length

            while (cursor < end) {
                val blockLen = minOf(chunkSize, end - cursor)
                var attempt = 0
                var written = -1

                while (attempt <= maxRetries) {
                    if (isInterruptedFn()) {
                        throw IOException("USB bulk write cancelled at offset $cursor")
                    }
                    written = transferChunk(cursor, blockLen)
                    if (written > 0) {
                        break
                    }
                    if (attempt < maxRetries) {
                        if (retryDelayMs > 0L) {
                            try {
                                sleepFn(retryDelayMs)
                            } catch (ie: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw IOException("USB bulk write interrupted while waiting to retry", ie)
                            }
                        }
                    }
                    attempt++
                }

                if (written < 0) {
                    throw IOException(
                        "USB bulk write failed ($written) at offset $cursor after ${maxRetries + 1} attempts"
                    )
                }
                if (written == 0) {
                    throw IOException(
                        "USB bulk write timed out with 0 bytes transferred at offset $cursor after ${maxRetries + 1} attempts"
                    )
                }
                totalWritten += written
                cursor += written
                onBytesWritten?.invoke(written)
            }
            return totalWritten
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        val ep = outEndpoint ?: throw IOException("USB Bulk OUT endpoint unavailable")
        return writeChunkedWithRetry(
            offset = offset,
            length = length,
            chunkSize = chunkSize,
            maxRetries = maxWriteRetries,
            retryDelayMs = retryDelayMs,
            onBytesWritten = onBytesWritten
        ) { chunkOffset, blockLen ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connection.bulkTransfer(ep, data, chunkOffset, blockLen, writeTimeoutMs)
            } else {
                val slice = if (chunkOffset == 0 && blockLen == data.size) {
                    data
                } else {
                    data.copyOfRange(chunkOffset, chunkOffset + blockLen)
                }
                connection.bulkTransfer(ep, slice, blockLen, writeTimeoutMs)
            }
        }
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
