package com.example.document

import com.example.model.AnchorPosition
import com.example.model.ImagesPerPageOption
import com.example.model.PageOrientation
import com.example.model.PhotoLayoutOptions
import com.example.model.PhysicalPhotoSizePreset
import kotlin.math.floor

data class SheetCellMm(
    val slotIndex: Int,
    val leftMm: Float,
    val topMm: Float,
    val widthMm: Float,
    val heightMm: Float
)

data class PhotoPageAssignment(
    val pageIndex: Int,
    val slots: List<Pair<SheetCellMm, Int>> // Cell -> photo index in batch
)

data class PhotoSheetPlan(
    val paperWidthMm: Float,
    val paperHeightMm: Float,
    val marginMm: Float,
    val gapMm: Float,
    val columns: Int,
    val rows: Int,
    val cells: List<SheetCellMm>,
    val pages: List<PhotoPageAssignment>
)

object PhotoLayoutGeometry {

    fun effectivePaperSizeMm(
        options: PhotoLayoutOptions,
        firstPhotoAspect: Float = 0.75f
    ): Pair<Float, Float> {
        val baseW = options.paperSize.widthMm
        val baseH = options.paperSize.heightMm
        val landscape = when (options.orientation) {
            PageOrientation.PORTRAIT -> false
            PageOrientation.LANDSCAPE -> true
            PageOrientation.AUTO -> {
                val fixed = options.imagesPerPage.fixedCount
                when {
                    fixed == 2 || fixed == 6 || fixed == 8 -> true
                    fixed == 1 && firstPhotoAspect > 1.05f -> true
                    else -> false
                }
            }
        }
        return if (landscape) maxOf(baseW, baseH) to minOf(baseW, baseH)
        else minOf(baseW, baseH) to maxOf(baseW, baseH)
    }

    fun computeSheetPlan(
        photoCount: Int,
        options: PhotoLayoutOptions,
        photoAspectRatios: List<Float> = emptyList()
    ): PhotoSheetPlan {
        val firstAspect = photoAspectRatios.firstOrNull() ?: 0.75f
        val (paperW, paperH) = effectivePaperSizeMm(options, firstAspect)
        val margin = options.marginPreset.mm
        val gap = options.gapPreset.mm

        val printableW = (paperW - 2f * margin).coerceAtLeast(10f)
        val printableH = (paperH - 2f * margin).coerceAtLeast(10f)

        val (cols, rows, cellW, cellH) = resolveGridAndCellSize(
            printableW = printableW,
            printableH = printableH,
            gapMm = gap,
            imagesPerPage = options.imagesPerPage,
            preset = options.physicalSizePreset
        )

        val gridTotalW = cols * cellW + (cols - 1).coerceAtLeast(0) * gap
        val gridTotalH = rows * cellH + (rows - 1).coerceAtLeast(0) * gap

        val extraX = (printableW - gridTotalW).coerceAtLeast(0f)
        val extraY = (printableH - gridTotalH).coerceAtLeast(0f)

        val originX = margin + when (options.anchorPosition.col) {
            0 -> 0f
            1 -> extraX / 2f
            else -> extraX
        }
        val originY = margin + when (options.anchorPosition.row) {
            0 -> 0f
            1 -> extraY / 2f
            else -> extraY
        }

        val cells = ArrayList<SheetCellMm>(rows * cols)
        var slotIdx = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                cells.add(
                    SheetCellMm(
                        slotIndex = slotIdx++,
                        leftMm = originX + c * (cellW + gap),
                        topMm = originY + r * (cellH + gap),
                        widthMm = cellW,
                        heightMm = cellH
                    )
                )
            }
        }

        val pages = assignPhotosToPages(
            photoCount = photoCount,
            cells = cells,
            repeatSinglePhoto = options.repeatSinglePhoto
        )

        return PhotoSheetPlan(
            paperWidthMm = paperW,
            paperHeightMm = paperH,
            marginMm = margin,
            gapMm = gap,
            columns = cols,
            rows = rows,
            cells = cells,
            pages = pages
        )
    }

    private data class GridSpec(
        val cols: Int,
        val rows: Int,
        val cellW: Float,
        val cellH: Float
    )

    private fun resolveGridAndCellSize(
        printableW: Float,
        printableH: Float,
        gapMm: Float,
        imagesPerPage: ImagesPerPageOption,
        preset: PhysicalPhotoSizePreset
    ): GridSpec {
        val presetW = preset.widthMm
        val presetH = preset.heightMm

        if (imagesPerPage == ImagesPerPageOption.AUTO_GRID) {
            if (presetW != null && presetH != null) {
                val (colsP, rowsP) = maxFitGrid(printableW, printableH, gapMm, presetW, presetH)
                val (colsL, rowsL) = maxFitGrid(printableW, printableH, gapMm, presetH, presetW)
                return if (colsL * rowsL > colsP * rowsP) {
                    GridSpec(
                        cols = colsL,
                        rows = rowsL,
                        cellW = presetH.coerceAtMost(printableW),
                        cellH = presetW.coerceAtMost(printableH)
                    )
                } else {
                    GridSpec(
                        cols = colsP,
                        rows = rowsP,
                        cellW = presetW.coerceAtMost(printableW),
                        cellH = presetH.coerceAtMost(printableH)
                    )
                }
            }
            // Auto grid with Auto Fit Cell defaults to balanced 2x2
            val cols = 2
            val rows = 2
            val cellW = ((printableW - gapMm * (cols - 1)) / cols).coerceAtLeast(5f)
            val cellH = ((printableH - gapMm * (rows - 1)) / rows).coerceAtLeast(5f)
            return GridSpec(cols, rows, cellW, cellH)
        }

        val count = imagesPerPage.fixedCount ?: 1
        val isLandscape = printableW > printableH
        val (cols, rows) = when (count) {
            1 -> 1 to 1
            2 -> if (isLandscape) 2 to 1 else 1 to 2
            4 -> 2 to 2
            6 -> if (isLandscape) 3 to 2 else 2 to 3
            8 -> if (isLandscape) 4 to 2 else 2 to 4
            9 -> 3 to 3
            else -> 1 to 1
        }

        val maxCellW = ((printableW - gapMm * (cols - 1)) / cols).coerceAtLeast(5f)
        val maxCellH = ((printableH - gapMm * (rows - 1)) / rows).coerceAtLeast(5f)

        if (presetW == null || presetH == null) {
            return GridSpec(cols, rows, maxCellW, maxCellH)
        }

        // Fit physical photo size inside maxCell (allow 90° orientation flip if it fits better)
        val scaleNormal = minOf(maxCellW / presetW, maxCellH / presetH, 1f)
        val scaleFlipped = minOf(maxCellW / presetH, maxCellH / presetW, 1f)

        return if (scaleFlipped > scaleNormal) {
            GridSpec(cols, rows, presetH * scaleFlipped, presetW * scaleFlipped)
        } else {
            GridSpec(cols, rows, presetW * scaleNormal, presetH * scaleNormal)
        }
    }

    private fun maxFitGrid(
        printableW: Float,
        printableH: Float,
        gapMm: Float,
        itemW: Float,
        itemH: Float
    ): Pair<Int, Int> {
        val cols = floor(((printableW + gapMm) / (itemW + gapMm)).toDouble()).toInt().coerceAtLeast(1)
        val rows = floor(((printableH + gapMm) / (itemH + gapMm)).toDouble()).toInt().coerceAtLeast(1)
        return cols to rows
    }

    private fun assignPhotosToPages(
        photoCount: Int,
        cells: List<SheetCellMm>,
        repeatSinglePhoto: Boolean
    ): List<PhotoPageAssignment> {
        if (photoCount <= 0 || cells.isEmpty()) return emptyList()
        if (photoCount == 1 && repeatSinglePhoto) {
            return listOf(
                PhotoPageAssignment(
                    pageIndex = 0,
                    slots = cells.map { it to 0 }
                )
            )
        }
        val perPage = cells.size
        val totalPages = ((photoCount + perPage - 1) / perPage).coerceAtLeast(1)
        return (0 until totalPages).map { pageIdx ->
            val startPhoto = pageIdx * perPage
            val slots = mutableListOf<Pair<SheetCellMm, Int>>()
            for (slotIdx in cells.indices) {
                val photoIdx = startPhoto + slotIdx
                if (photoIdx < photoCount) {
                    slots.add(cells[slotIdx] to photoIdx)
                }
            }
            PhotoPageAssignment(pageIndex = pageIdx, slots = slots)
        }
    }

    fun shouldAutoRotatePhoto(
        photoW: Int,
        photoH: Int,
        cellW: Float,
        cellH: Float,
        autoRotateEnabled: Boolean
    ): Boolean {
        if (!autoRotateEnabled || photoW <= 0 || photoH <= 0) return false
        val photoLandscape = photoW > photoH
        val cellLandscape = cellW > cellH
        return photoLandscape != cellLandscape && photoW != photoH && kotlin.math.abs(cellW - cellH) > 0.5f
    }

    fun mmToPx(mm: Float, dpi: Int): Int {
        return ((mm / 25.4f) * dpi).toInt().coerceAtLeast(1)
    }
}
