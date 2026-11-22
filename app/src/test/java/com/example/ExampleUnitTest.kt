package com.example

import androidx.compose.ui.geometry.Offset
import com.example.books.BookBlock
import com.example.books.BookFormat
import com.example.books.BookFormatDetector
import com.example.books.BookParseException
import com.example.books.CbzDocumentEngine
import com.example.books.DocParser
import com.example.books.DocxParser
import com.example.books.EpubParser
import com.example.books.Fb2Parser
import com.example.books.HtmlBookParser
import com.example.books.MarkdownParser
import com.example.books.MobiParser
import com.example.books.ReflowPaginator
import com.example.books.RtfParser
import com.example.books.TxtParser
import com.example.ui.components.formatFileSize
import com.example.ui.reader.PdfZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExampleUnitTest {

    @Test
    fun `pinch zoom scales within bounds and clamps offsets`() {
        val zoomed = PdfZoomMath.computePinchTransform(
            currentScale = 1f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
            zoomFactor = 2f,
            pan = Offset.Zero,
            centroid = Offset(500f, 1000f),
            viewportWidth = 1000f,
            viewportHeight = 2000f
        )
        assertEquals(2f, zoomed.scale, 0.001f)
        assertEquals(0f, zoomed.offsetX, 0.001f)
        assertEquals(0f, zoomed.offsetY, 0.001f)

        val overZoomed = PdfZoomMath.computePinchTransform(
            currentScale = 4f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
            zoomFactor = 2f,
            pan = Offset.Zero,
            centroid = Offset(500f, 1000f),
            viewportWidth = 1000f,
            viewportHeight = 2000f
        )
        assertEquals(PdfZoomMath.MAX_SCALE, overZoomed.scale, 0.001f)
    }

    @Test
    fun `render bucket width increases with zoom scale and respects cap`() {
        val base = PdfZoomMath.computeRenderBucketWidth(1000, 1.0f)
        val medium = PdfZoomMath.computeRenderBucketWidth(1000, 1.8f)
        val high = PdfZoomMath.computeRenderBucketWidth(1000, 3.8f)

        assertTrue(medium > base)
        assertTrue(high > medium)
        assertTrue(high <= 2200)
    }

    @Test
    fun `file size formatter formats KB MB GB cleanly`() {
        assertEquals("", formatFileSize(0L))
        assertEquals("512 KB", formatFileSize(512L * 1024L))
        assertEquals("4.5 MB", formatFileSize((4.5 * 1024 * 1024).toLong()))
        assertEquals("1.25 GB", formatFileSize((1.25 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `format detector identifies all ebook and word formats accurately`() {
        assertEquals(BookFormat.PDF, BookFormatDetector.detect("book.bin", null, "%PDF-1.7".toByteArray()))
        assertEquals(BookFormat.RTF, BookFormatDetector.detect("legacy.doc", null, "{\\rtf1\\ansi Hello}".toByteArray()))
        assertEquals(
            BookFormat.DOC,
            BookFormatDetector.detect(
                "report.doc",
                null,
                byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            )
        )
        assertEquals(BookFormat.FB2, BookFormatDetector.detect("novel.fb2", null, "<?xml version=\"1.0\"?><FictionBook>".toByteArray()))
        assertEquals(BookFormat.HTML, BookFormatDetector.detect("page.html", null, "<!DOCTYPE html><html><body>Hi</body></html>".toByteArray()))
        assertEquals(BookFormat.MARKDOWN, BookFormatDetector.detect("notes.md", null, "# Heading\nBody".toByteArray()))
        assertEquals(BookFormat.TXT, BookFormatDetector.detect("readme.txt", "text/plain", "Just text".toByteArray()))

        val mobiHeader = ByteArray(70)
        "BOOKMOBI".toByteArray().copyInto(mobiHeader, destinationOffset = 60)
        assertEquals(BookFormat.MOBI, BookFormatDetector.detect("ebook.mobi", null, mobiHeader))
    }

    @Test
    fun `markdown html rtf and txt parsers extract structured blocks`() {
        val md = MarkdownParser.parse(
            "# Odyssey\n\n> Sing in me, Muse.\n\n- First item\n- Second item\n\nParagraph text with **bold** words.".toByteArray(),
            "fallback"
        )
        assertEquals("Odyssey", md.title)
        assertTrue(md.flattenedBlocks.any { it.block is BookBlock.Heading })
        assertTrue(md.flattenedBlocks.any { it.block is BookBlock.Quote })
        assertTrue(md.flattenedBlocks.any { it.block is BookBlock.ListItem })
        assertTrue(md.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text?.contains("bold words") == true })

        val rtf = RtfParser.parse(
            "{\\rtf1\\ansi{\\fonttbl{\\f0 Times;}}Hello \\u8212? World\\par Second paragraph.}".toByteArray(),
            "Letter"
        )
        val rtfText = rtf.flattenedBlocks.mapNotNull { (it.block as? BookBlock.Paragraph)?.text }.joinToString(" ")
        assertTrue(rtfText.contains("Hello — World"))
        assertTrue(rtfText.contains("Second paragraph."))

        val docAsRtf = DocParser.parse("{\\rtf1\\ansi Legacy Word RTF\\par Line two}".toByteArray(), "Legacy")
        assertTrue(docAsRtf.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text?.contains("Legacy Word RTF") == true })

        val html = HtmlBookParser.parse(
            "<html><head><title>Sample HTML</title></head><body><h1>Chapter One</h1><p>Hello &amp; welcome.</p></body></html>".toByteArray(),
            "Fallback"
        )
        assertEquals("Sample HTML", html.title)
        assertTrue(html.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text == "Hello & welcome." })

        val txt = TxtParser.parse("Chapter 1\n\nOpening paragraph.\n\nSecond paragraph.".toByteArray(), "Book")
        assertTrue(txt.flattenedBlocks.any { it.block is BookBlock.Heading })
        assertTrue(txt.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text == "Opening paragraph." })
    }

    @Test
    fun `epub parser reads synthetic epub archive cleanly`() {
        val epubBytes = buildZipBytes(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray(),
            "OEBPS/content.opf" to """
                <package version="2.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Cosmic Drift</dc:title>
                    <dc:creator>Ada Lovelace</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>
            """.trimIndent().toByteArray(),
            "OEBPS/ch1.xhtml" to """
                <html><body><h1>Arrival</h1><p>The stars were bright tonight.</p></body></html>
            """.trimIndent().toByteArray()
        )

        val book = EpubParser.parse(epubBytes, "Fallback")
        assertEquals("Cosmic Drift", book.title)
        assertEquals("Ada Lovelace", book.author)
        assertEquals(1, book.chapters.size)
        assertEquals("Arrival", book.chapters.first().title)
        assertTrue(book.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text == "The stars were bright tonight." })
    }

    @Test
    fun `docx parser extracts headings paragraphs lists and tables`() {
        val docxBytes = buildZipBytes(
            "docProps/core.xml" to """
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                  xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>Quarterly Memo</dc:title>
                  <dc:creator>Jane Doe</dc:creator>
                </cp:coreProperties>
            """.trimIndent().toByteArray(),
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Executive Summary</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Revenue grew steadily this quarter.</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr/></w:pPr><w:r><w:t>Action item one</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Metric</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                  </w:body>
                </w:document>
            """.trimIndent().toByteArray()
        )

        val book = DocxParser.parse(docxBytes, "Fallback")
        assertEquals("Quarterly Memo", book.title)
        assertEquals("Jane Doe", book.author)
        assertTrue(book.flattenedBlocks.any { (it.block as? BookBlock.Heading)?.text == "Executive Summary" })
        assertTrue(book.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text == "Revenue grew steadily this quarter." })
        assertTrue(book.flattenedBlocks.any { (it.block as? BookBlock.ListItem)?.text == "Action item one" })
        assertTrue(book.flattenedBlocks.any { (it.block as? BookBlock.TableRow)?.cells == listOf("Metric", "Value") })
    }

    @Test
    fun `fb2 parser reads fictionbook metadata and chapters`() {
        val fb2Xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info>
                <book-title>Northern Lights</book-title>
                <author><first-name>Leo</first-name><last-name>Tolstoy</last-name></author>
              </title-info></description>
              <body>
                <section>
                  <title><p>Part I</p></title>
                  <p>Snow covered the silent pines.</p>
                </section>
              </body>
            </FictionBook>
        """.trimIndent().toByteArray()

        val book = Fb2Parser.parse(fb2Xml, "Fallback")
        assertEquals("Northern Lights", book.title)
        assertEquals("Leo Tolstoy", book.author)
        assertEquals(1, book.chapters.size)
        assertTrue(book.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text == "Snow covered the silent pines." })
    }

    @Test
    fun `mobi parser decodes uncompressed palmdoc and detects drm`() {
        val unencrypted = buildSyntheticMobi(
            htmlBody = "<html><body><h1>Mobi Chapter</h1><p>Hello from PalmDOC.</p></body></html>",
            encryptionType = 0
        )
        val book = MobiParser.parse(unencrypted, "Fallback")
        assertTrue(book.flattenedBlocks.any { (it.block as? BookBlock.Paragraph)?.text == "Hello from PalmDOC." })

        val encrypted = buildSyntheticMobi(htmlBody = "Secret", encryptionType = 2)
        try {
            MobiParser.parse(encrypted, "Fallback")
            fail("Expected BookParseException for DRM-protected MOBI")
        } catch (e: BookParseException) {
            assertTrue(e.isDrmProtected)
        }
    }

    @Test
    fun `reflow paginator splits long book into pages and preserves block anchor`() {
        val text = (1..30).joinToString("\n\n") { idx ->
            "Paragraph $idx: " + "Reading smoothly across pages keeps the reader anchored cleanly. ".repeat(4)
        }
        val book = TxtParser.parse(text.toByteArray(), "Long Book")
        val pages = ReflowPaginator.paginate(
            blocks = book.flattenedBlocks,
            viewportWidthDp = 360f,
            viewportHeightDp = 640f,
            fontSizeSp = 18f
        )
        assertTrue("Expected multiple pages, got ${pages.size}", pages.size > 2)

        val targetBlock = book.flattenedBlocks.size / 2
        val pageIdx = ReflowPaginator.findPageForBlock(pages, targetBlock)
        val page = pages[pageIdx]
        assertTrue(targetBlock in page.firstBlockIndex..page.lastBlockIndex)
    }

    @Test
    fun `cbz natural comparator sorts page2 before page10`() {
        assertTrue(CbzDocumentEngine.compareNatural("page2.jpg", "page10.jpg") < 0)
        assertTrue(CbzDocumentEngine.compareNatural("ch01/009.png", "ch01/010.png") < 0)
    }

    private fun buildZipBytes(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildSyntheticMobi(htmlBody: String, encryptionType: Int): ByteArray {
        val textBytes = htmlBody.toByteArray(Charsets.UTF_8)
        val rec0 = ByteArray(100)
        val r0 = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)
        r0.putShort(0, 1) // compression = 1 (uncompressed)
        r0.putInt(4, textBytes.size)
        r0.putShort(8, 1) // 1 text record
        r0.putShort(12, encryptionType.toShort())
        "MOBI".toByteArray().copyInto(rec0, destinationOffset = 16)
        r0.putInt(20, 84) // header len
        r0.putInt(28, 65001) // UTF-8

        val numRecords = 2
        val rec0Offset = 78 + numRecords * 8
        val rec1Offset = rec0Offset + rec0.size
        val totalSize = rec1Offset + textBytes.size
        val result = ByteArray(totalSize)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        "Test_Mobi".toByteArray().copyInto(result, 0)
        "BOOKMOBI".toByteArray().copyInto(result, 60)
        buf.putShort(76, numRecords.toShort())
        buf.putInt(78, rec0Offset)
        buf.putInt(86, rec1Offset)
        rec0.copyInto(result, rec0Offset)
        textBytes.copyInto(result, rec1Offset)
        return result
    }
}
