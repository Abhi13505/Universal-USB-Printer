package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "print_history")
data class PrintHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val documentTitle: String,
    val pageCount: Int,
    val copies: Int,
    val paperSize: String,
    val resolvedProtocol: String,
    val colorMode: String,
    val bytesSent: Long,
    val status: String
)
