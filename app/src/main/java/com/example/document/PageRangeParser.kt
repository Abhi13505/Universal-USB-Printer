package com.example.document

import com.example.model.PageRangeMode

object PageRangeParser {

    /**
     * Resolves 0-based page indices for a PDF of [totalPages] given the selected [mode],
     * [currentPageIndex] (0-based), and [customText] (1-based ranges like "1-3, 5").
     */
    fun resolvePages(
        totalPages: Int,
        mode: PageRangeMode,
        currentPageIndex: Int,
        customText: String
    ): List<Int> {
        if (totalPages <= 0) return emptyList()
        return when (mode) {
            PageRangeMode.ALL -> (0 until totalPages).toList()
            PageRangeMode.CURRENT -> listOf(currentPageIndex.coerceIn(0, totalPages - 1))
            PageRangeMode.ODD -> (0 until totalPages).filter { (it + 1) % 2 == 1 }
            PageRangeMode.EVEN -> (0 until totalPages).filter { (it + 1) % 2 == 0 }
            PageRangeMode.CUSTOM -> parseCustomRanges(customText, totalPages)
        }
    }

    fun parseCustomRanges(input: String, totalPages: Int): List<Int> {
        if (totalPages <= 0 || input.isBlank()) return emptyList()
        val result = linkedSetOf<Int>()
        val parts = input.split(',', ';')
        for (rawPart in parts) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            if (part.contains('-')) {
                val bounds = part.split('-', limit = 2)
                val startOne = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                val endOne = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                if (startOne != null && endOne != null) {
                    val low = minOf(startOne, endOne).coerceIn(1, totalPages)
                    val high = maxOf(startOne, endOne).coerceIn(1, totalPages)
                    for (p in low..high) {
                        result.add(p - 1)
                    }
                }
            } else {
                val pageOne = part.toIntOrNull()
                if (pageOne != null && pageOne in 1..totalPages) {
                    result.add(pageOne - 1)
                }
            }
        }
        return result.toList().sorted()
    }
}
