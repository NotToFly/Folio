package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY isPinned DESC, lastOpenedAt DESC")
    fun observeRecentDocuments(): Flow<List<RecentDocumentEntity>>

    @Query("SELECT * FROM recent_documents WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): RecentDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: RecentDocumentEntity)

    @Query(
        "UPDATE recent_documents " +
            "SET lastPage = :lastPage, totalPages = :totalPages, lastZoom = :lastZoom, lastOpenedAt = :lastOpenedAt " +
            "WHERE uriString = :uriString"
    )
    suspend fun updateReadingPosition(
        uriString: String,
        lastPage: Int,
        totalPages: Int,
        lastZoom: Float,
        lastOpenedAt: Long
    )

    @Query("UPDATE recent_documents SET isPinned = :isPinned WHERE uriString = :uriString")
    suspend fun setPinned(uriString: String, isPinned: Boolean)

    @Query("DELETE FROM recent_documents WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM recent_documents")
    suspend fun clearAll()
}
