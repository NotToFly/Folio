package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.books.BookFormat

@Entity(tableName = "recent_documents")
data class RecentDocumentEntity(
    @PrimaryKey val uriString: String,
    val displayName: String,
    val fileSizeBytes: Long,
    val totalPages: Int,
    val lastPage: Int,
    val lastZoom: Float = 1f,
    val lastOpenedAt: Long,
    val thumbnailPath: String? = null,
    val isPinned: Boolean = false,
    val formatType: String = BookFormat.PDF.name,
    val lastCharOffset: Int = 0
) {
    val bookFormat: BookFormat
        get() = BookFormat.fromStoredName(formatType)

    val progressFraction: Float
        get() {
            if (totalPages <= 0) return 0f
            if (totalPages == 1) return 1f
            val currentIndex = if (bookFormat.isFixedLayout) lastPage else lastCharOffset
            return ((currentIndex + 1).toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val isFinished: Boolean
        get() {
            if (totalPages <= 0) return false
            val currentIndex = if (bookFormat.isFixedLayout) lastPage else lastCharOffset
            return currentIndex >= totalPages - 1
        }
}
