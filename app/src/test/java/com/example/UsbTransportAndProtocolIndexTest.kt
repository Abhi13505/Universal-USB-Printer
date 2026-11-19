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
    fun `writeChunkedWithRetry recovers from transient timeouts when printer pauses`() {
        val attemptsPerOffset = mutableMapOf<Int, Int>()
        var reportedBytes = 0

        val total = AndroidUsbBulkTransport.writeChunkedWithRetry(
            offset = 0,
            length = 25,
            chunkSize = 10,
            maxRetries = 2,
            retryDelayMs = 0L,
            onBytesWritten = { reportedBytes += it },
            sleepFn = {}
        ) { chunkOffset, chunkLength ->
            val count = (attemptsPerOffset[chunkOffset] ?: 0) + 1
            attemptsPerOffset[chunkOffset] = count
            // Simulate printer pausing and timing out twice on the second chunk before accepting it
            if (chunkOffset == 10 && count <= 2) {
                -1
            } else {
                chunkLength
            }
        }

        assertEquals(25, total)
        assertEquals(25, reportedBytes)
        assertEquals(1, attemptsPerOffset[0])
        assertEquals(3, attemptsPerOffset[10])
        assertEquals(1, attemptsPerOffset[20])
    }

    @Test
    fun `writeChunkedWithRetry throws IOException after exhausting retries`() {
        var calls = 0
        try {
            AndroidUsbBulkTransport.writeChunkedWithRetry(
                offset = 0,
                length = 10,
                chunkSize = 10,
                maxRetries = 2,
                retryDelayMs = 0L,
                sleepFn = {}
            ) { _, _ ->
                calls++
                0
            }
            fail("Expected IOException when retries are exhausted")
        } catch (expected: IOException) {
            assertEquals(3, calls)
        }
    }
}
