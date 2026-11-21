package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val isPinned: Boolean = false
) {
    val progressFraction: Float
        get() = if (totalPages <= 1) {
            if (totalPages == 1) 1f else 0f
        } else {
            ((lastPage + 1).toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val isFinished: Boolean
        get() = totalPages > 0 && lastPage >= totalPages - 1
}
