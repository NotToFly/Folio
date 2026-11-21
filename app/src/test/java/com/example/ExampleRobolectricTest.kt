package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.DocumentRepository
import com.example.data.FolioDatabase
import com.example.ui.reader.PdfZoomMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: FolioDatabase
    private lateinit var repository: DocumentRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FolioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DocumentRepository(context, database.recentDocumentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `app name matches Folio`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("Folio", appName)
    }

    @Test
    fun `reading position is remembered across reopen`() = runTest {
        val fakeUri = Uri.parse("content://com.example.docs/manual.pdf")

        // First open
        val opened = repository.recordDocumentOpened(
            uri = fakeUri,
            totalPages = 120,
            thumbnailBitmap = null
        )
        assertEquals(0, opened.lastPage)
        assertEquals(120, opened.totalPages)

        // User scrolls to page 41 (0-indexed)
        repository.updateReadingPosition(
            uriString = fakeUri.toString(),
            lastPage = 41,
            totalPages = 120,
            lastZoom = 1.8f
        )

        // Re-opening same document preserves saved page 41
        val reopened = repository.recordDocumentOpened(
            uri = fakeUri,
            totalPages = 120,
            thumbnailBitmap = null
        )
        assertEquals(41, reopened.lastPage)

        val recentList = repository.recentDocuments.first()
        assertEquals(1, recentList.size)
        assertEquals(41, recentList.first().lastPage)
    }

    @Test
    fun `document pinning removal and progress metrics work in Room`() = runTest {
        val uriA = Uri.parse("content://com.example.docs/alpha.pdf")
        val uriB = Uri.parse("content://com.example.docs/beta.pdf")

        repository.recordDocumentOpened(uriA, totalPages = 100, thumbnailBitmap = null)
        repository.recordDocumentOpened(uriB, totalPages = 50, thumbnailBitmap = null)

        repository.updateReadingPosition(uriA.toString(), lastPage = 49, totalPages = 100, lastZoom = 1f)
        repository.setPinned(uriA.toString(), true)

        val list = repository.recentDocuments.first()
        assertEquals(2, list.size)
        // Pinned document comes first
        assertEquals(uriA.toString(), list.first().uriString)
        assertTrue(list.first().isPinned)
        assertEquals(50, list.first().progressPercent)

        repository.removeDocument(uriB.toString())
        val afterRemove = repository.recentDocuments.first()
        assertEquals(1, afterRemove.size)
    }

    @Test
    fun `zoom math clamps offsets and anchors double tap`() {
        val update = PdfZoomMath.computeDoubleTapTarget(
            currentScale = 1f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
            tapOffset = androidx.compose.ui.geometry.Offset(0f, 0f),
            viewportWidth = 1000f,
            viewportHeight = 2000f
        )
        assertEquals(PdfZoomMath.DOUBLE_TAP_SCALE, update.scale, 0.001f)
        assertEquals(750f, update.offsetX, 0.01f)
        assertEquals(1500f, update.offsetY, 0.01f)

        val reset = PdfZoomMath.computeDoubleTapTarget(
            currentScale = 2.5f,
            currentOffsetX = 300f,
            currentOffsetY = -400f,
            tapOffset = androidx.compose.ui.geometry.Offset(500f, 1000f),
            viewportWidth = 1000f,
            viewportHeight = 2000f
        )
        assertEquals(1f, reset.scale, 0.001f)
        assertEquals(0f, reset.offsetX, 0.001f)
        assertEquals(0f, reset.offsetY, 0.001f)
    }

    @Test
    fun `reader route codec preserves encoded ExternalStorageProvider URIs exactly`() {
        val original =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ADownload%2FThe%20%20Soviet%20Mafia_%20--%20Arkadi%C4%AD%20--%20Anna%E2%80%99s%20Archive.pdf"
        val encoded = ReaderRouteCodec.encode(original)
        val decoded = ReaderRouteCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `clearing history keeps bookmarked documents and their reading positions`() = runTest {
        val bookmarkedUri = Uri.parse("content://com.example.docs/bookmarked.pdf")
        val recentUri = Uri.parse("content://com.example.docs/recent.pdf")

        repository.recordDocumentOpened(bookmarkedUri, totalPages = 80, thumbnailBitmap = null)
        repository.updateReadingPosition(bookmarkedUri.toString(), lastPage = 25, totalPages = 80, lastZoom = 1.5f)
        repository.setPinned(bookmarkedUri.toString(), true)

        repository.recordDocumentOpened(recentUri, totalPages = 40, thumbnailBitmap = null)
        repository.updateReadingPosition(recentUri.toString(), lastPage = 10, totalPages = 40, lastZoom = 1f)

        repository.clearUnpinnedHistory()

        val remaining = repository.recentDocuments.first()
        assertEquals(1, remaining.size)
        assertEquals(bookmarkedUri.toString(), remaining.first().uriString)
        assertTrue(remaining.first().isPinned)
        assertEquals(25, remaining.first().lastPage)
    }
}
