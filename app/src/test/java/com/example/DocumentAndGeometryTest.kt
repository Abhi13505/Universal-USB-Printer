package com.example

import com.example.document.PageRangeParser
import com.example.document.PhotoLayoutGeometry
import com.example.model.AnchorPosition
import com.example.model.GapPreset
import com.example.model.ImagesPerPageOption
import com.example.model.MarginPreset
import com.example.model.PageOrientation
import com.example.model.PageRangeMode
import com.example.model.PaperSize
import com.example.model.PhotoLayoutOptions
import com.example.model.PhysicalPhotoSizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAndGeometryTest {

    @Test
    fun `PageRangeParser resolves All, Current, Odd, Even, and Custom ranges`() {
        assertEquals(listOf(0, 1, 2, 3, 4), PageRangeParser.resolvePages(5, PageRangeMode.ALL, 0, ""))
        assertEquals(listOf(2), PageRangeParser.resolvePages(5, PageRangeMode.CURRENT, 2, ""))
        assertEquals(listOf(0, 2, 4), PageRangeParser.resolvePages(5, PageRangeMode.ODD, 0, ""))
        assertEquals(listOf(1, 3), PageRangeParser.resolvePages(5, PageRangeMode.EVEN, 0, ""))
        assertEquals(listOf(0, 1, 2, 4), PageRangeParser.resolvePages(6, PageRangeMode.CUSTOM, 0, "1-3, 5"))
    }

    @Test
    fun `PhotoLayoutGeometry Auto Grid maximizes physical Passport 35x45mm slots on A4`() {
        val opts = PhotoLayoutOptions(
            imagesPerPage = ImagesPerPageOption.AUTO_GRID,
            physicalSizePreset = PhysicalPhotoSizePreset.PASSPORT_35X45,
            gapPreset = GapPreset.COMPACT, // 2.5 mm
            marginPreset = MarginPreset.NARROW, // 5 mm
            paperSize = PaperSize.A4,
            orientation = PageOrientation.PORTRAIT,
            repeatSinglePhoto = true
        )
        val plan = PhotoLayoutGeometry.computeSheetPlan(photoCount = 1, options = opts)
        // Printable A4 = 200 x 287 mm; 35x45 mm + 2.5 mm gap fits 5 cols x 6 rows = 30 slots (or rotated 45x35 fits 4x7 = 28)
        assertTrue(plan.cells.size >= 25)
        assertEquals(1, plan.pages.size)
        assertEquals(plan.cells.size, plan.pages[0].slots.size)
        assertTrue(plan.pages[0].slots.all { it.second == 0 })
    }

    @Test
    fun `PhotoLayoutGeometry splits multi-photo batches across pages and obeys anchor position`() {
        val opts = PhotoLayoutOptions(
            imagesPerPage = ImagesPerPageOption.FOUR,
            physicalSizePreset = PhysicalPhotoSizePreset.WALLET_5X7_CM,
            gapPreset = GapPreset.NORMAL,
            marginPreset = MarginPreset.NORMAL,
            anchorPosition = AnchorPosition.BOTTOM_RIGHT,
            paperSize = PaperSize.A4,
            orientation = PageOrientation.PORTRAIT
        )
        val plan = PhotoLayoutGeometry.computeSheetPlan(photoCount = 6, options = opts)
        assertEquals(2, plan.pages.size)
        assertEquals(4, plan.pages[0].slots.size)
        assertEquals(2, plan.pages[1].slots.size)

        // Bottom-right anchor pushes cells toward right & bottom edges
        val lastCell = plan.cells.last()
        assertEquals(210f - 10f, lastCell.leftMm + lastCell.widthMm, 0.05f)
        assertEquals(297f - 10f, lastCell.topMm + lastCell.heightMm, 0.05f)
    }

    @Test
    fun `shouldAutoRotatePhoto matches photo aspect to cell aspect`() {
        assertTrue(PhotoLayoutGeometry.shouldAutoRotatePhoto(4000, 3000, 50f, 70f, autoRotateEnabled = true))
        assertFalse(PhotoLayoutGeometry.shouldAutoRotatePhoto(3000, 4000, 50f, 70f, autoRotateEnabled = true))
        assertFalse(PhotoLayoutGeometry.shouldAutoRotatePhoto(4000, 3000, 50f, 70f, autoRotateEnabled = false))
    }

    @Test
    fun `safe margin defaults to 5mm and applies only to PCL 5, PCL-XL, and PWG Raster`() {
        assertEquals(5f, com.example.model.PrintTuningOptions().safeMarginMm, 0.001f)
        assertEquals(300, com.example.encoder.PrintJobStreamer.TEST_PAGE_HEIGHT_PX)
        assertEquals(300, com.example.encoder.PrintJobStreamer.TEST_PAGE_DPI)

        assertTrue(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.PCL_5C_COLOR))
        assertTrue(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.PCL_5E_MONO))
        assertTrue(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.PCL_6_XL))
        assertTrue(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.IPP_USB_PWG_RASTER))

        assertFalse(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.SAMSUNG_QPDL_0X11))
        assertFalse(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.SAMSUNG_QPDL_0X13_JBIG1))
        assertFalse(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.SAMSUNG_QPDL_0X15_COLOR))
        assertFalse(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.POSTSCRIPT))
        assertFalse(com.example.encoder.PrintJobStreamer.usesSafeMargin(com.example.model.DriverProtocol.PDF_DIRECT))
    }

    @Test
    fun `computeSafeMarginInsetPx converts 5mm margin cleanly and returns zero when disabled`() {
        val (wPx, hPx) = com.example.document.BandCompositor.alignedPageDimensionsPx(210f, 297f, 600, 600)
        val (insetX, insetY) = com.example.document.BandCompositor.computeSafeMarginInsetPx(210f, 297f, wPx, hPx, 5f)
        val expectedApprox = (5f / 25.4f) * 600f // ~118.11 px
        assertEquals(expectedApprox, insetX, 2.0f)
        assertEquals(expectedApprox, insetY, 2.0f)

        val (zeroX, zeroY) = com.example.document.BandCompositor.computeSafeMarginInsetPx(210f, 297f, wPx, hPx, 0f)
        assertEquals(0f, zeroX, 0.001f)
        assertEquals(0f, zeroY, 0.001f)
    }
}
