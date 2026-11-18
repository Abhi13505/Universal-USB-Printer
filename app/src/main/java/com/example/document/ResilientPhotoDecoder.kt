package com.example.document

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor

object ResilientPhotoDecoder {

    private const val MAX_STREAM_BYTES = 32 * 1024 * 1024 // 32 MB cap

    data class DecodedPhoto(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int,
        val tierUsed: Int
    )

    /**
     * 3-Tier Resilient Photo Decoder supporting JPEG, PNG, WebP, HEIC, AVIF, and Photo Picker URIs:
     * 1. ImageDecoder (API 28+) with ALLOCATOR_SOFTWARE so pixel reads never fail on HARDWARE bitmaps.
     * 2. Seekable ParcelFileDescriptor via BitmapFactory.decodeFileDescriptor + inSampleSize + EXIF rotation.
     * 3. Bounded (32 MB) InputStream fallback for non-seekable content URIs + EXIF rotation.
     */
    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        maxDimensionPx: Int = 2400
    ): DecodedPhoto? {
        // Tier 1: ImageDecoder (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(resolver, uri)
                var origW = 0
                var origH = 0
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    origW = info.size.width
                    origH = info.size.height
                    val longest = maxOf(origW, origH)
                    if (longest > maxDimensionPx && maxDimensionPx > 0) {
                        val scale = maxDimensionPx.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (origW * scale).toInt().coerceAtLeast(1),
                            (origH * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
                val swBitmap = ensureSoftwareArgb8888(bitmap)
                return DecodedPhoto(
                    bitmap = swBitmap,
                    originalWidth = if (origW > 0) origW else swBitmap.width,
                    originalHeight = if (origH > 0) origH else swBitmap.height,
                    tierUsed = 1
                )
            } catch (_: Throwable) {
                // Fall through to Tier 2
            }
        }

        // Tier 2: Seekable ParcelFileDescriptor
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fd = pfd.fileDescriptor
                val decoded = decodeFromFileDescriptor(fd, maxDimensionPx)
                if (decoded != null) {
                    return decoded.copy(tierUsed = 2)
                }
            }
        } catch (_: Throwable) {
            // Fall through to Tier 3
        }

        // Tier 3: Bounded InputStream fallback (up to 32 MB)
        return try {
            val rawBytes = resolver.openInputStream(uri)?.use { input ->
                readBoundedBytes(input, MAX_STREAM_BYTES)
            } ?: return null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxDimensionPx)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return null
            val orientation = readExifOrientationFromBytes(rawBytes)
            val rotated = applyExifOrientation(rawBitmap, orientation)
            val swBitmap = ensureSoftwareArgb8888(rotated)
            DecodedPhoto(
                bitmap = swBitmap,
                originalWidth = swBitmap.width,
                originalHeight = swBitmap.height,
                tierUsed = 3
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeFromFileDescriptor(fd: FileDescriptor, maxDimensionPx: Int): DecodedPhoto? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxDimensionPx)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = BitmapFactory.decodeFileDescriptor(fd, null, decodeOpts) ?: return null
        val orientation = try {
            ExifInterface(fd).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val rotated = applyExifOrientation(rawBitmap, orientation)
        val swBitmap = ensureSoftwareArgb8888(rotated)
        return DecodedPhoto(
            bitmap = swBitmap,
            originalWidth = swBitmap.width,
            originalHeight = swBitmap.height,
            tierUsed = 2
        )
    }

    private fun readExifOrientationFromBytes(bytes: ByteArray): Int {
        return try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun ensureSoftwareArgb8888(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        }
        return bitmap
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (maxDim <= 0) return 1
        var inSampleSize = 1
        var longest = maxOf(width, height)
        while (longest / inSampleSize > maxDim * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }
}
