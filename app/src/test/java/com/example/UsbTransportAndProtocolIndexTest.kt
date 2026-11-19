package com.example

import com.example.usb.AndroidUsbBulkTransport
import com.example.usb.UsbPrinterManager
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class UsbTransportAndProtocolIndexTest {

    @Test
    fun `GET_DEVICE_ID wIndex places interface in high byte and alternate setting in low byte`() {
        assertEquals(0x0000, UsbPrinterManager.buildGetDeviceIdIndex(interfaceNumber = 0, alternateSetting = 0))
        assertEquals(0x0100, UsbPrinterManager.buildGetDeviceIdIndex(interfaceNumber = 1, alternateSetting = 0))
        assertEquals(0x0201, UsbPrinterManager.buildGetDeviceIdIndex(interfaceNumber = 2, alternateSetting = 1))
        assertEquals(0x0302, UsbPrinterManager.buildGetDeviceIdIndex(interfaceNumber = 3, alternateSetting = 2))
    }

    @Test
    fun `default print bulk write timeout is 60 seconds with retries`() {
        assertEquals(60_000, UsbPrinterManager.PRINT_BULK_WRITE_TIMEOUT_MS)
        assertEquals(2, UsbPrinterManager.PRINT_BULK_WRITE_RETRIES)
    }

    @Test
    fun `writeChunkedSafely allows retries before the first byte of the job`() {
        var firstChunkAttempts = 0
        var secondChunkAttempts = 0
        var reportedBytes = 0

        val totalInJob = AndroidUsbBulkTransport.writeChunkedSafely(
            offset = 0,
            length = 25,
            chunkSize = 10,
            maxRetriesBeforeFirstByte = 2,
            retryDelayMs = 0L,
            initialBytesSentInJob = 0L,
            onBytesWritten = { reportedBytes += it },
            sleepFn = {}
        ) { chunkOffset, chunkLength ->
            if (chunkOffset == 0) {
                firstChunkAttempts++
                // Transient timeouts BEFORE any byte of the job has succeeded may retry
                if (firstChunkAttempts <= 2) -1 else chunkLength
            } else {
                secondChunkAttempts++
                chunkLength
            }
        }

        assertEquals(25L, totalInJob)
        assertEquals(25, reportedBytes)
        assertEquals(3, firstChunkAttempts)
        assertEquals(2, secondChunkAttempts) // offset 10 (10 bytes) + offset 20 (5 bytes)
    }

    @Test
    fun `writeChunkedSafely aborts immediately without retry once any byte has been sent`() {
        val attemptsPerOffset = mutableMapOf<Int, Int>()
        try {
            AndroidUsbBulkTransport.writeChunkedSafely(
                offset = 0,
                length = 25,
                chunkSize = 10,
                maxRetriesBeforeFirstByte = 2,
                retryDelayMs = 0L,
                initialBytesSentInJob = 0L,
                sleepFn = {}
            ) { chunkOffset, chunkLength ->
                attemptsPerOffset[chunkOffset] = (attemptsPerOffset[chunkOffset] ?: 0) + 1
                if (chunkOffset == 0) chunkLength else -1
            }
            fail("Expected IOException when second chunk times out after first chunk wrote bytes")
        } catch (expected: IOException) {
            assertEquals(1, attemptsPerOffset[0])
            assertEquals(1, attemptsPerOffset[10]) // Zero retries once >0 bytes were written!
        }

        // Also test subsequent write() call in same job session where initialBytesSentInJob > 0
        var subsequentAttempts = 0
        try {
            AndroidUsbBulkTransport.writeChunkedSafely(
                offset = 0,
                length = 10,
                chunkSize = 10,
                maxRetriesBeforeFirstByte = 2,
                retryDelayMs = 0L,
                initialBytesSentInJob = 100L,
                sleepFn = {}
            ) { _, _ ->
                subsequentAttempts++
                -1
            }
            fail("Expected IOException without retry when initialBytesSentInJob > 0")
        } catch (_: IOException) {
            assertEquals(1, subsequentAttempts)
        }
    }

    @Test
    fun `writeChunkedSafely throws IOException after exhausting initial retries before first byte`() {
        var calls = 0
        try {
            AndroidUsbBulkTransport.writeChunkedSafely(
                offset = 0,
                length = 10,
                chunkSize = 10,
                maxRetriesBeforeFirstByte = 2,
                retryDelayMs = 0L,
                initialBytesSentInJob = 0L,
                sleepFn = {}
            ) { _, _ ->
                calls++
                0
            }
            fail("Expected IOException when retries are exhausted")
        } catch (_: IOException) {
            assertEquals(3, calls)
        }
    }
}
