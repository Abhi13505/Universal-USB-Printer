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
    private val maxWriteRetriesBeforeFirstByte: Int = DEFAULT_MAX_WRITE_RETRIES,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val onBytesWritten: ((Int) -> Unit)? = null
) : UsbByteTransport {

    private var sessionBytesWritten: Long = 0L

    companion object {
        const val DEFAULT_WRITE_TIMEOUT_MS = 60_000
        const val DEFAULT_MAX_WRITE_RETRIES = 2
        const val DEFAULT_RETRY_DELAY_MS = 250L

        /**
         * Streams [length] bytes starting at [offset] in [chunkSize]-byte slices.
         *
         * Android's UsbDeviceConnection.bulkTransfer() does not report how many bytes were actually
         * transmitted before a timeout (-1 or 0). Therefore retries are permitted ONLY while
         * [hasSentAnyByte] is false (before the first byte of the current job succeeds). Once any
         * byte has been sent, any transfer error or timeout fails immediately without retrying so
         * the printer stream can never be corrupted with duplicate mid-stream bytes.
         */
        internal fun writeChunkedSafely(
            offset: Int,
            length: Int,
            chunkSize: Int,
            maxRetriesBeforeFirstByte: Int,
            retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
            initialBytesSentInJob: Long = 0L,
            onBytesWritten: ((Int) -> Unit)? = null,
            sleepFn: (Long) -> Unit = Thread::sleep,
            isInterruptedFn: () -> Boolean = { Thread.currentThread().isInterrupted },
            transferChunk: (chunkOffset: Int, chunkLength: Int) -> Int
        ): Long {
            var totalSentInJob = initialBytesSentInJob
            var cursor = offset
            val end = offset + length

            while (cursor < end) {
                val blockLen = minOf(chunkSize, end - cursor)
                val allowedRetries = if (totalSentInJob == 0L) maxRetriesBeforeFirstByte else 0
                var attempt = 0
                var written = -1

                while (attempt <= allowedRetries) {
                    if (isInterruptedFn()) {
                        throw IOException("USB bulk write cancelled at offset $cursor")
                    }
                    written = transferChunk(cursor, blockLen)
                    if (written > 0) {
                        break
                    }
                    if (attempt < allowedRetries && retryDelayMs > 0L) {
                        try {
                            sleepFn(retryDelayMs)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("USB bulk write interrupted while waiting to retry", ie)
                        }
                    }
                    attempt++
                }

                if (written <= 0) {
                    val msg = if (totalSentInJob > 0L) {
                        "USB bulk transfer timed out/failed ($written) after $totalSentInJob bytes were already sent; job aborted to avoid duplicating data mid-stream. Please check the printer and reprint."
                    } else {
                        "USB bulk write failed ($written) before any job data was sent after ${allowedRetries + 1} attempts."
                    }
                    throw IOException(msg)
                }

                totalSentInJob += written.toLong()
                cursor += written
                onBytesWritten?.invoke(written)
            }
            return totalSentInJob
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        val ep = outEndpoint ?: throw IOException("USB Bulk OUT endpoint unavailable")
        val before = sessionBytesWritten
        sessionBytesWritten = writeChunkedSafely(
            offset = offset,
            length = length,
            chunkSize = chunkSize,
            maxRetriesBeforeFirstByte = maxWriteRetriesBeforeFirstByte,
            retryDelayMs = retryDelayMs,
            initialBytesSentInJob = before,
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
        return (sessionBytesWritten - before).toInt()
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
