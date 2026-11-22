package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.books.BookFormat
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
    fun `reading position is remembered across reopen for PDF and EPUB`() = runTest {
        val pdfUri = Uri.parse("content://com.example.docs/manual.pdf")
        val epubUri = Uri.parse("content://com.example.docs/novel.epub")

        val openedPdf = repository.recordDocumentOpened(
            uri = pdfUri,
            format = BookFormat.PDF,
            totalPages = 120,
            thumbnailBitmap = null
        )
        assertEquals(0, openedPdf.lastPage)
        assertEquals(BookFormat.PDF, openedPdf.bookFormat)

        repository.updateReadingPosition(
            uriString = pdfUri.toString(),
            lastPage = 41,
            totalPages = 120,
            lastZoom = 1.8f
        )

        val reopenedPdf = repository.recordDocumentOpened(
            uri = pdfUri,
            format = BookFormat.PDF,
            totalPages = 120,
            thumbnailBitmap = null
        )
        assertEquals(41, reopenedPdf.lastPage)

        repository.recordDocumentOpened(
            uri = epubUri,
            format = BookFormat.EPUB,
            totalPages = 200,
            thumbnailBitmap = null
        )
        repository.updateReadingPosition(
            uriString = epubUri.toString(),
            lastPage = 99,
            totalPages = 200,
            lastZoom = 1f,
            lastCharOffset = 99
        )

        val reopenedEpub = repository.recordDocumentOpened(
            uri = epubUri,
            format = BookFormat.EPUB,
            totalPages = 200,
            thumbnailBitmap = null
        )
        assertEquals(BookFormat.EPUB, reopenedEpub.bookFormat)
        assertEquals(99, reopenedEpub.lastCharOffset)
        assertEquals(50, reopenedEpub.progressPercent)
    }

    @Test
    fun `document pinning keeps bookmarked items on top in Room`() = runTest {
        val uriA = Uri.parse("content://com.example.docs/alpha.docx")
        val uriB = Uri.parse("content://com.example.docs/beta.doc")

        repository.recordDocumentOpened(uriA, format = BookFormat.DOCX, totalPages = 100, thumbnailBitmap = null)
        repository.recordDocumentOpened(uriB, format = BookFormat.DOC, totalPages = 50, thumbnailBitmap = null)

        repository.updateReadingPosition(uriA.toString(), lastPage = 49, totalPages = 100, lastZoom = 1f, lastCharOffset = 49)
        repository.setPinned(uriA.toString(), true)

        val list = repository.recentDocuments.first()
        assertEquals(2, list.size)
        assertEquals(uriA.toString(), list.first().uriString)
        assertEquals(BookFormat.DOCX, list.first().bookFormat)
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
    }

    @Test
    fun `reader route codec preserves encoded ExternalStorageProvider URIs exactly`() {
        val original =
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ADownload%2FThe%20%20Soviet%20Mafia_%20--%20Arkadi%C4%AD%20--%20Anna%E2%80%99s%20Archive.epub"
        val encoded = ReaderRouteCodec.encode(original)
        val decoded = ReaderRouteCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `clearing history keeps bookmarked documents and their reading positions`() = runTest {
        val bookmarkedUri = Uri.parse("content://com.example.docs/bookmarked.fb2")
        val recentUri = Uri.parse("content://com.example.docs/recent.pdf")

        repository.recordDocumentOpened(bookmarkedUri, format = BookFormat.FB2, totalPages = 80, thumbnailBitmap = null)
        repository.updateReadingPosition(bookmarkedUri.toString(), lastPage = 25, totalPages = 80, lastZoom = 1f, lastCharOffset = 25)
        repository.setPinned(bookmarkedUri.toString(), true)

        repository.recordDocumentOpened(recentUri, format = BookFormat.PDF, totalPages = 40, thumbnailBitmap = null)
        repository.updateReadingPosition(recentUri.toString(), lastPage = 10, totalPages = 40, lastZoom = 1f)

        repository.clearUnpinnedHistory()

        val remaining = repository.recentDocuments.first()
        assertEquals(1, remaining.size)
        assertEquals(bookmarkedUri.toString(), remaining.first().uriString)
        assertEquals(BookFormat.FB2, remaining.first().bookFormat)
        assertTrue(remaining.first().isPinned)
        assertEquals(25, remaining.first().lastCharOffset)
    }
}
