package com.example.books

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.pdf.PdfOpenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

sealed interface LoadedBook {
    val format: BookFormat

    data class Fixed(
        override val format: BookFormat,
        val engine: FixedPageDocumentEngine,
        val openInfo: PdfOpenInfo
    ) : LoadedBook

    data class Reflowable(
        val book: ReflowableBook
    ) : LoadedBook {
        override val format: BookFormat
            get() = book.format
    }
}

/**
 * Unified offline book loader. Sniffs magic bytes, extension, and MIME type,
 * then opens either a FixedPageDocumentEngine (PDF/CBZ) or parses a ReflowableBook
 * (EPUB, FB2/FB2.ZIP, MOBI, DOCX, DOC, RTF, HTML, Markdown, TXT).
 */
class BookLoader(private val context: Context) {

    companion object {
        private const val MAX_REFLOWABLE_FILE_BYTES = 40 * 1024 * 1024 // 40 MB safety cap
    }

    suspend fun load(
        uri: Uri,
        displayName: String,
        mimeType: String?
    ): LoadedBook = withContext(Dispatchers.IO) {
        val headerBytes = readHeaderBytes(uri, maxBytes = 4096)
        val format = BookFormatDetector.detect(displayName, mimeType, headerBytes)
        val fallbackTitle = displayName.substringBeforeLast('.').ifBlank { displayName }

        when (format) {
            BookFormat.PDF -> {
                val engine = PdfPageEngineAdapter(context)
                try {
                    val info = engine.open(uri)
                    LoadedBook.Fixed(BookFormat.PDF, engine, info)
                } catch (e: Throwable) {
                    engine.close()
                    throw e
                }
            }

            BookFormat.CBZ -> {
                val engine = CbzDocumentEngine(context)
                try {
                    val info = engine.open(uri)
                    LoadedBook.Fixed(BookFormat.CBZ, engine, info)
                } catch (e: Throwable) {
                    engine.close()
                    throw e
                }
            }

            else -> {
                val allBytes = readAllBytesBounded(uri, MAX_REFLOWABLE_FILE_BYTES)
                val book = parseReflowable(format, allBytes, fallbackTitle)
                LoadedBook.Reflowable(book)
            }
        }
    }

    internal fun parseReflowable(
        format: BookFormat,
        bytes: ByteArray,
        fallbackTitle: String
    ): ReflowableBook {
        return when (format) {
            BookFormat.EPUB -> EpubParser.parse(bytes, fallbackTitle)
            BookFormat.FB2 -> Fb2Parser.parse(bytes, fallbackTitle)
            BookFormat.MOBI -> MobiParser.parse(bytes, fallbackTitle)
            BookFormat.DOCX -> DocxParser.parse(bytes, fallbackTitle)
            BookFormat.DOC -> DocParser.parse(bytes, fallbackTitle)
            BookFormat.RTF -> RtfParser.parse(bytes, fallbackTitle)
            BookFormat.HTML -> HtmlBookParser.parse(bytes, fallbackTitle)
            BookFormat.MARKDOWN -> MarkdownParser.parse(bytes, fallbackTitle)
            BookFormat.TXT -> TxtParser.parse(bytes, fallbackTitle)
            BookFormat.PDF, BookFormat.CBZ ->
                throw BookParseException("Fixed-layout format $format cannot be parsed as reflowable.")
        }
    }

    private fun openStream(uri: Uri): InputStream {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: throw IOException("Invalid file URI")
            return FileInputStream(File(path))
        }
        return context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open file stream")
    }

    private fun readHeaderBytes(uri: Uri, maxBytes: Int): ByteArray {
        return openStream(uri).use { stream ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val read = stream.read(buf, total, maxBytes - total)
                if (read <= 0) break
                total += read
            }
            if (total == maxBytes) buf else buf.copyOf(total)
        }
    }

    private fun readAllBytesBounded(uri: Uri, maxBytes: Int): ByteArray {
        return openStream(uri).use { stream ->
            val out = ByteArrayOutputStream(32 * 1024)
            val buf = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw BookParseException("This file exceeds the 40 MB limit for reflowable book parsing.")
                }
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
    }
}
