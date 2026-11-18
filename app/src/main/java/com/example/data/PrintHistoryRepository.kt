package com.example.data

import kotlinx.coroutines.flow.Flow

class PrintHistoryRepository(private val dao: PrintHistoryDao) {
    val allHistory: Flow<List<PrintHistoryEntity>> = dao.observeAll()

    suspend fun recordJob(entry: PrintHistoryEntity) {
        dao.insert(entry)
    }

    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }
}
