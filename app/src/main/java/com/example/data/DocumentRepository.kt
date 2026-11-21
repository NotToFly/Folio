package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class DocumentMetadata(
    val displayName: String,
    val fileSizeBytes: Long
)

class DocumentRepository(
    private val context: Context,
    private val dao: RecentDocumentDao
) {
    val recentDocuments: Flow<List<RecentDocumentEntity>> = dao.observeRecentDocuments()

    fun takePersistableReadPermission(uri: Uri) {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Provider may not support persistable permissions (e.g., temporary share intents)
            } catch (_: IllegalArgumentException) {
                // Ignore invalid flags
            }
        }
    }

    suspend fun getDocumentByUri(uriString: String): RecentDocumentEntity? =
        withContext(Dispatchers.IO) {
            dao.getByUri(uriString)
        }

    suspend fun queryMetadata(uri: Uri): DocumentMetadata = withContext(Dispatchers.IO) {
        var displayName: String? = null
        var fileSizeBytes = 0L

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx)
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            fileSizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall back to path segment below
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    displayName = file.name
                    fileSizeBytes = file.length()
                }
            }
        }

        val resolvedName = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Document.pdf"

        DocumentMetadata(
            displayName = resolvedName,
            fileSizeBytes = fileSizeBytes.coerceAtLeast(0L)
        )
    }

    suspend fun saveThumbnail(uriString: String, bitmap: Bitmap): String? =
        withContext(Dispatchers.IO) {
            try {
                val thumbDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
                val hash = sha256Short(uriString)
                val outFile = File(thumbDir, "$hash.jpg")
                FileOutputStream(outFile).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                }
                outFile.absolutePath
            } catch (_: Exception) {
                null
            }
        }

    suspend fun recordDocumentOpened(
        uri: Uri,
        totalPages: Int,
        thumbnailBitmap: Bitmap?
    ): RecentDocumentEntity = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val existing = dao.getByUri(uriString)
        val metadata = queryMetadata(uri)
        val now = System.currentTimeMillis()

        val savedThumbPath = if (thumbnailBitmap != null) {
            saveThumbnail(uriString, thumbnailBitmap)
        } else {
            existing?.thumbnailPath
        }

        val clampedLastPage = (existing?.lastPage ?: 0)
            .coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        val entity = RecentDocumentEntity(
            uriString = uriString,
            displayName = metadata.displayName,
            fileSizeBytes = if (metadata.fileSizeBytes > 0L) metadata.fileSizeBytes else (existing?.fileSizeBytes ?: 0L),
            totalPages = totalPages,
            lastPage = clampedLastPage,
            lastZoom = existing?.lastZoom ?: 1f,
            lastOpenedAt = now,
            thumbnailPath = savedThumbPath,
            isPinned = existing?.isPinned ?: false
        )
        dao.upsert(entity)
        entity
    }

    suspend fun updateReadingPosition(
        uriString: String,
        lastPage: Int,
        totalPages: Int,
        lastZoom: Float
    ) = withContext(Dispatchers.IO) {
        val safeTotal = totalPages.coerceAtLeast(1)
        val safePage = lastPage.coerceIn(0, safeTotal - 1)
        val safeZoom = lastZoom.coerceIn(1f, 5f)
        dao.updateReadingPosition(
            uriString = uriString,
            lastPage = safePage,
            totalPages = safeTotal,
            lastZoom = safeZoom,
            lastOpenedAt = System.currentTimeMillis()
        )
    }

    suspend fun setPinned(uriString: String, isPinned: Boolean) = withContext(Dispatchers.IO) {
        dao.setPinned(uriString, isPinned)
    }

    suspend fun restoreDocument(document: RecentDocumentEntity) = withContext(Dispatchers.IO) {
        dao.upsert(document)
    }

    suspend fun removeDocument(uriString: String) = withContext(Dispatchers.IO) {
        val existing = dao.getByUri(uriString)
        dao.deleteByUri(uriString)
        existing?.thumbnailPath?.let { path ->
            runCatching { File(path).delete() }
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
        val thumbDir = File(context.filesDir, "thumbnails")
        runCatching {
            if (thumbDir.exists()) {
                thumbDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
