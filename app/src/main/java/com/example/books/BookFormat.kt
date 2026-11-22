package com.example.books

import java.util.Locale

enum class BookFormat(
    val badge: String,
    val isFixedLayout: Boolean
) {
    PDF("PDF", true),
    CBZ("CBZ", true),
    EPUB("EPUB", false),
    FB2("FB2", false),
    MOBI("MOBI", false),
    DOCX("DOCX", false),
    DOC("DOC", false),
    RTF("RTF", false),
    HTML("HTML", false),
    MARKDOWN("MD", false),
    TXT("TXT", false);

    companion object {
        fun fromStoredName(raw: String?): BookFormat {
            if (raw.isNullOrBlank()) return PDF
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PDF
        }
    }
}

object BookFormatDetector {

    /**
     * Detects document format by prioritizing magic bytes (handling misnamed files such as
     * RTF/HTML saved with .doc or .fb2.zip archives), then filename extension and MIME type.
     */
    fun detect(
        displayName: String?,
        mimeType: String?,
        headerBytes: ByteArray
    ): BookFormat {
        val nameLower = displayName?.lowercase(Locale.US)?.trim().orEmpty()
        val mimeLower = mimeType?.lowercase(Locale.US)?.trim().orEmpty()

        // 1. Check unambiguous magic signatures first
        if (startsWithAscii(headerBytes, "%PDF")) return BookFormat.PDF
        if (startsWithAscii(headerBytes, "{\\rtf")) return BookFormat.RTF

        // OLE2 Compound File Binary Format (legacy .doc)
        if (headerBytes.size >= 8 &&
            headerBytes[0] == 0xD0.toByte() &&
            headerBytes[1] == 0xCF.toByte() &&
            headerBytes[2] == 0x11.toByte() &&
            headerBytes[3] == 0xE0.toByte() &&
            headerBytes[4] == 0xA1.toByte() &&
            headerBytes[5] == 0xB1.toByte() &&
            headerBytes[6] == 0x1A.toByte() &&
            headerBytes[7] == 0xE1.toByte()
        ) {
            return BookFormat.DOC
        }

        // PalmDB / MOBI header check (bytes 60..67 == "BOOKMOBI" or "TEXtREAd")
        if (headerBytes.size >= 68) {
            val ident = String(headerBytes, 60, 8, Charsets.ISO_8859_1)
            if (ident == "BOOKMOBI" || ident == "TEXtREAd") {
                return BookFormat.MOBI
            }
        }

        // ZIP-based formats: EPUB, DOCX, FB2.ZIP, CBZ
        val isZip = headerBytes.size >= 4 &&
            headerBytes[0] == 0x50.toByte() &&
            headerBytes[1] == 0x4B.toByte() &&
            (headerBytes[2] == 0x03.toByte() || headerBytes[2] == 0x05.toByte())
        if (isZip) {
            // Check uncompressed "mimetypeapplication/epub+zip" early in ZIP header
            val headerAscii = String(headerBytes, Charsets.ISO_8859_1)
            if (headerAscii.contains("application/epub+zip")) return BookFormat.EPUB
            if (nameLower.endsWith(".epub") || mimeLower.contains("epub")) return BookFormat.EPUB
            if (nameLower.endsWith(".docx") || mimeLower.contains("wordprocessingml")) return BookFormat.DOCX
            if (nameLower.endsWith(".fb2.zip") || nameLower.endsWith(".fbz") || nameLower.endsWith(".fb2")) {
                return BookFormat.FB2
            }
            if (nameLower.endsWith(".cbz") || mimeLower.contains("comicbook")) return BookFormat.CBZ
            if (headerAscii.contains("word/") || headerAscii.contains("[Content_Types].xml")) {
                return BookFormat.DOCX
            }
            if (headerAscii.contains(".fb2")) return BookFormat.FB2
            return BookFormat.CBZ
        }

        // XML-based check for FictionBook (.fb2) or HTML
        val headerText = String(headerBytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val headerTextLower = headerText.lowercase(Locale.US)
        if (headerTextLower.contains("<fictionbook")) return BookFormat.FB2
        if (headerTextLower.startsWith("<!doctype html") ||
            headerTextLower.startsWith("<html") ||
            headerTextLower.contains("<body")
        ) {
            if (!nameLower.endsWith(".fb2") && !nameLower.endsWith(".md")) {
                return BookFormat.HTML
            }
        }

        // 2. Fallback to filename extension
        return when {
            nameLower.endsWith(".pdf") -> BookFormat.PDF
            nameLower.endsWith(".epub") -> BookFormat.EPUB
            nameLower.endsWith(".fb2") || nameLower.endsWith(".fb2.zip") || nameLower.endsWith(".fbz") -> BookFormat.FB2
            nameLower.endsWith(".mobi") || nameLower.endsWith(".prc") || nameLower.endsWith(".azw") || nameLower.endsWith(".azw3") -> BookFormat.MOBI
            nameLower.endsWith(".docx") -> BookFormat.DOCX
            nameLower.endsWith(".doc") -> BookFormat.DOC
            nameLower.endsWith(".rtf") -> BookFormat.RTF
            nameLower.endsWith(".html") || nameLower.endsWith(".htm") || nameLower.endsWith(".xhtml") -> BookFormat.HTML
            nameLower.endsWith(".md") || nameLower.endsWith(".markdown") -> BookFormat.MARKDOWN
            nameLower.endsWith(".cbz") -> BookFormat.CBZ
            nameLower.endsWith(".txt") || nameLower.endsWith(".log") || nameLower.endsWith(".csv") -> BookFormat.TXT
            // 3. MIME type fallback
            mimeLower.contains("pdf") -> BookFormat.PDF
            mimeLower.contains("epub") -> BookFormat.EPUB
            mimeLower.contains("fictionbook") -> BookFormat.FB2
            mimeLower.contains("mobipocket") || mimeLower.contains("amazon.ebook") -> BookFormat.MOBI
            mimeLower.contains("wordprocessingml") -> BookFormat.DOCX
            mimeLower.contains("msword") -> BookFormat.DOC
            mimeLower.contains("rtf") -> BookFormat.RTF
            mimeLower.contains("html") -> BookFormat.HTML
            mimeLower.contains("markdown") -> BookFormat.MARKDOWN
            mimeLower.contains("comicbook") || mimeLower.contains("cbz") -> BookFormat.CBZ
            else -> BookFormat.TXT
        }
    }

    private fun startsWithAscii(bytes: ByteArray, prefix: String): Boolean {
        if (bytes.size < prefix.length) return false
        for (i in prefix.indices) {
            if (bytes[i].toInt().toChar() != prefix[i]) return false
        }
        return true
    }
}
