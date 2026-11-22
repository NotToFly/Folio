package com.example.books

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin Microsoft Word (.docx), Rich Text (.rtf), and legacy Word 97–2003 (.doc OLE2/CFBF) parsers.
 * Avoids heavy JVM desktop dependencies like Apache POI while preserving headings, lists, tables, and images.
 */
object DocxParser {
    private const val MAX_IMAGE_TOTAL_BYTES = 8 * 1024 * 1024

    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        var documentXml: String? = null
        var coreXml: String? = null
        var relsXml: String? = null
        val images = mutableMapOf<String, ByteArray>()
        var totalImageBytes = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val lower = entry.name.replace('\\', '/').trimStart('/').lowercase(Locale.US)
                    when {
                        lower == "word/document.xml" -> {
                            documentXml = TextEncodingDecoder.decodeSmart(readAll(zis))
                        }
                        lower == "docprops/core.xml" -> {
                            coreXml = TextEncodingDecoder.decodeSmart(readAll(zis))
                        }
                        lower == "word/_rels/document.xml.rels" -> {
                            relsXml = TextEncodingDecoder.decodeSmart(readAll(zis))
                        }
                        lower.startsWith("word/media/") && totalImageBytes < MAX_IMAGE_TOTAL_BYTES -> {
                            val data = readAll(zis)
                            if (data.isNotEmpty() && totalImageBytes + data.size <= MAX_IMAGE_TOTAL_BYTES) {
                                images[lower] = data
                                totalImageBytes += data.size
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val docContent = documentXml
            ?: throw BookParseException("This DOCX file is missing word/document.xml or is encrypted.")

        val (coreTitle, coreAuthor) = coreXml?.let(::parseCoreProps) ?: (null to null)
        val relMap = relsXml?.let(::parseRelationships).orEmpty()
        val blocks = parseDocumentXml(docContent, relMap, images)

        val firstHeading = (blocks.firstOrNull { it is BookBlock.Heading } as? BookBlock.Heading)?.text
        val title = coreTitle?.takeIf { it.isNotBlank() } ?: firstHeading ?: fallbackTitle
        val chapters = BookBuilder.splitBlocksIntoChapters(blocks, title)
        val coverBitmap = BookBitmapHelper.decodeThumbnail(images.values.firstOrNull())

        return BookBuilder.buildBook(
            title = title,
            author = coreAuthor,
            format = BookFormat.DOCX,
            coverBitmap = coverBitmap,
            rawChapters = chapters,
            images = images
        )
    }

    private fun parseCoreProps(coreXml: String): Pair<String?, String?> {
        var title: String? = null
        var author: String? = null
        runCatching {
            val buf = StringBuilder()
            var capture: String? = null
            SafeXmlSax.parse(coreXml, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    if (local == "title" || local == "creator") {
                        capture = local
                        buf.setLength(0)
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (capture != null && ch != null) buf.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    if (local == capture) {
                        val value = buf.toString().trim().takeIf { it.isNotBlank() }
                        if (local == "title") title = value
                        if (local == "creator") author = value
                        capture = null
                        buf.setLength(0)
                    }
                }
            })
        }
        return title to author
    }

    private fun parseRelationships(relsXml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            SafeXmlSax.parse(relsXml, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    val local = (qName ?: localName).orEmpty().substringAfter(':')
                    if (local.equals("Relationship", ignoreCase = true) && attributes != null) {
                        val id = SafeXmlSax.getAttr(attributes, "Id")
                        val target = SafeXmlSax.getAttr(attributes, "Target")
                        if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                            map[id] = EpubParser.resolveRelativePath("word", target)
                        }
                    }
                }
            })
        }
        return map
    }

    private fun parseDocumentXml(
        xml: String,
        relMap: Map<String, String>,
        images: Map<String, ByteArray>
    ): List<BookBlock> {
        val blocks = mutableListOf<BookBlock>()

        var inTable = false
        var inRow = false
        var inCell = false
        var inParagraph = false
        var inTextNode = false
        var paragraphStyle: String? = null
        var isListItem = false

        val paragraphBuf = StringBuilder()
        val cellBuf = StringBuilder()
        var rowCells = mutableListOf<String>()

        SafeXmlSax.parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                when (local) {
                    "tbl" -> inTable = true
                    "tr" -> if (inTable) {
                        inRow = true
                        rowCells = mutableListOf()
                    }
                    "tc" -> if (inRow) {
                        inCell = true
                        cellBuf.setLength(0)
                    }
                    "p" -> {
                        inParagraph = true
                        paragraphStyle = null
                        isListItem = false
                        paragraphBuf.setLength(0)
                    }
                    "pstyle" -> if (inParagraph && attributes != null) {
                        paragraphStyle = SafeXmlSax.getAttr(attributes, "val")
                    }
                    "numpr" -> if (inParagraph) {
                        isListItem = true
                    }
                    "t" -> if (inParagraph) {
                        inTextNode = true
                    }
                    "tab" -> if (inParagraph) paragraphBuf.append("  ")
                    "br", "cr" -> if (inParagraph) paragraphBuf.append('\n')
                    "blip" -> if (attributes != null) {
                        val embedId = SafeXmlSax.getAttr(attributes, "embed")
                        val imgKey = embedId?.let { relMap[it] }
                        if (imgKey != null && images.containsKey(imgKey) && !inTable) {
                            blocks.add(BookBlock.ImageBlock(imgKey))
                        }
                    }
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inTextNode && inParagraph && ch != null) {
                    paragraphBuf.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                when (local) {
                    "t" -> inTextNode = false
                    "p" -> {
                        val text = paragraphBuf.toString()
                            .replace(Regex("[ \\t]+"), " ")
                            .trim()
                        if (inCell) {
                            if (text.isNotEmpty()) {
                                if (cellBuf.isNotEmpty()) cellBuf.append(' ')
                                cellBuf.append(text)
                            }
                        } else if (text.isNotEmpty()) {
                            blocks.add(classifyDocxParagraph(text, paragraphStyle, isListItem))
                        }
                        inParagraph = false
                        inTextNode = false
                    }
                    "tc" -> if (inRow) {
                        rowCells.add(cellBuf.toString().trim())
                        inCell = false
                    }
                    "tr" -> if (inTable) {
                        if (rowCells.any { it.isNotEmpty() }) {
                            blocks.add(BookBlock.TableRow(rowCells.toList()))
                        }
                        inRow = false
                    }
                    "tbl" -> inTable = false
                }
            }
        })

        return blocks
    }

    private fun classifyDocxParagraph(text: String, style: String?, isListItem: Boolean): BookBlock {
        val styleLower = style?.lowercase(Locale.US).orEmpty()
        return when {
            styleLower == "title" || styleLower == "heading1" || styleLower == "1" ->
                BookBlock.Heading(1, text)
            styleLower == "subtitle" || styleLower == "heading2" || styleLower == "2" ->
                BookBlock.Heading(2, text)
            styleLower == "heading3" || styleLower == "3" ->
                BookBlock.Heading(3, text)
            styleLower.startsWith("heading") ->
                BookBlock.Heading(4, text)
            styleLower.contains("quote") ->
                BookBlock.Quote(text)
            isListItem || styleLower.contains("list") ->
                BookBlock.ListItem("•", text)
            else ->
                BookBlock.Paragraph(text)
        }
    }

    private fun readAll(zis: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        zis.copyTo(out)
        return out.toByteArray()
    }
}

object RtfParser {
    private val SKIPPED_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "header", "footer", "headerl", "headerr", "headerf",
        "footerl", "footerr", "footerf", "fldinst", "listtable",
        "listoverridetable", "revtbl", "rsidtbl", "generator", "themedata",
        "colorschememapping", "latentstyles", "datastore", "xmlnstbl"
    )

    private data class RtfGroupState(
        var ucSkipCount: Int = 1,
        var skipGroup: Boolean = false
    )

    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder(raw.length / 2)
        val stack = ArrayDeque<RtfGroupState>()
        var current = RtfGroupState()
        var charset: Charset = Charset.forName("windows-1252")
        val hexBytes = ByteArrayOutputStream()

        fun flushHex() {
            if (hexBytes.size() > 0) {
                val decoded = String(hexBytes.toByteArray(), charset)
                if (!current.skipGroup) out.append(decoded)
                hexBytes.reset()
            }
        }

        var i = 0
        val len = raw.length
        while (i < len) {
            val ch = raw[i]
            when (ch) {
                '{' -> {
                    flushHex()
                    stack.addLast(current.copy())
                    i++
                }
                '}' -> {
                    flushHex()
                    current = if (stack.isNotEmpty()) stack.removeLast() else RtfGroupState()
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= len) break
                    val next = raw[i]
                    when (next) {
                        '\\', '{', '}' -> {
                            flushHex()
                            if (!current.skipGroup) out.append(next)
                            i++
                        }
                        '*' -> {
                            flushHex()
                            current.skipGroup = true
                            i++
                        }
                        '\'' -> {
                            if (i + 2 < len) {
                                val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                                if (hex != null) hexBytes.write(hex)
                                i += 3
                            } else {
                                i++
                            }
                        }
                        '~' -> {
                            flushHex()
                            if (!current.skipGroup) out.append(' ')
                            i++
                        }
                        '-', '_' -> {
                            flushHex()
                            if (!current.skipGroup && next == '_') out.append('-')
                            i++
                        }
                        '\r', '\n' -> {
                            flushHex()
                            if (!current.skipGroup) out.append('\n')
                            i++
                        }
                        else -> {
                            flushHex()
                            val wordStart = i
                            while (i < len && raw[i].isLetter()) i++
                            val word = raw.substring(wordStart, i).lowercase(Locale.US)
                            var param: Int? = null
                            if (i < len && (raw[i] == '-' || raw[i].isDigit())) {
                                val numStart = i
                                if (raw[i] == '-') i++
                                while (i < len && raw[i].isDigit()) i++
                                param = raw.substring(numStart, i).toIntOrNull()
                            }
                            if (i < len && raw[i] == ' ') i++

                            when (word) {
                                "ansicpg" -> {
                                    param?.let { cp ->
                                        runCatching { Charset.forName("windows-$cp") }.getOrNull()?.let { charset = it }
                                    }
                                }
                                "uc" -> {
                                    current.ucSkipCount = (param ?: 1).coerceIn(0, 8)
                                }
                                "u" -> {
                                    val codeUnit = param ?: 0
                                    val unsigned = if (codeUnit < 0) codeUnit + 65536 else codeUnit
                                    if (!current.skipGroup && unsigned in 1..0xFFFF) {
                                        out.append(unsigned.toChar())
                                    }
                                    // Skip uc fallback characters
                                    var skipped = 0
                                    while (skipped < current.ucSkipCount && i < len) {
                                        if (raw[i] == '\\' && i + 3 < len && raw[i + 1] == '\'') {
                                            i += 4
                                        } else if (raw[i] == '{' || raw[i] == '}') {
                                            break
                                        } else {
                                            i++
                                        }
                                        skipped++
                                    }
                                }
                                "par", "line" -> if (!current.skipGroup) out.append('\n')
                                "tab" -> if (!current.skipGroup) out.append("  ")
                                "emdash" -> if (!current.skipGroup) out.append('—')
                                "endash" -> if (!current.skipGroup) out.append('–')
                                "lquote" -> if (!current.skipGroup) out.append('‘')
                                "rquote" -> if (!current.skipGroup) out.append('’')
                                "ldblquote" -> if (!current.skipGroup) out.append('“')
                                "rdblquote" -> if (!current.skipGroup) out.append('”')
                                "bullet" -> if (!current.skipGroup) out.append("• ")
                                else -> if (word in SKIPPED_DESTINATIONS) {
                                    current.skipGroup = true
                                }
                            }
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    flushHex()
                    if (!current.skipGroup) out.append(ch)
                    i++
                }
            }
        }
        flushHex()

        val blocks = out.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                if (line.startsWith("• ")) BookBlock.ListItem("•", line.removePrefix("• ").trim())
                else BookBlock.Paragraph(line)
            }

        val chapters = BookBuilder.splitBlocksIntoChapters(blocks, fallbackTitle)
        return BookBuilder.buildBook(
            title = fallbackTitle,
            author = null,
            format = BookFormat.RTF,
            rawChapters = chapters
        )
    }
}

/**
 * Pure-Kotlin legacy Microsoft Word 97–2003 (.doc) parser.
 * Implements OLE2/CFBF container reading + Word FIB & PlcPcd piece-table reconstruction.
 * Also automatically delegates misnamed .doc files that are actually RTF, DOCX, or HTML.
 */
object DocParser {
    private const val FREESECT = -1
    private const val ENDOFCHAIN = -2
    private const val FATSECT = -3
    private const val DIFSECT = -4

    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        // Handle misnamed .doc files (many apps save RTF, HTML, or DOCX with a .doc extension)
        val headerSlice = bytes.copyOfRange(0, bytes.size.coerceAtMost(512))
        val detected = BookFormatDetector.detect("probe", null, headerSlice)
        if (detected == BookFormat.RTF) return RtfParser.parse(bytes, fallbackTitle)
        if (detected == BookFormat.DOCX) return DocxParser.parse(bytes, fallbackTitle)
        if (detected == BookFormat.HTML) return HtmlBookParser.parse(bytes, fallbackTitle)

        if (!isOle2Header(bytes)) {
            // Plain text renamed as .doc
            return TxtParser.parse(bytes, fallbackTitle).copy(format = BookFormat.DOC)
        }

        val ole = Ole2Container.parse(bytes)
        val wordDoc = ole.readStream("WordDocument")
            ?: throw BookParseException("This OLE2 file does not contain a Microsoft Word document stream.")

        val rawText = extractWord97Text(wordDoc, ole)
            ?: extractFallbackPrintableText(wordDoc)

        val cleanedText = stripWordFieldCodes(rawText)
        val blocks = convertWordTextToBlocks(cleanedText)
        val chapters = BookBuilder.splitBlocksIntoChapters(blocks, fallbackTitle)

        return BookBuilder.buildBook(
            title = fallbackTitle,
            author = null,
            format = BookFormat.DOC,
            rawChapters = chapters
        )
    }

    private fun isOle2Header(bytes: ByteArray): Boolean {
        return bytes.size >= 512 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0xE0.toByte() &&
            bytes[4] == 0xA1.toByte() &&
            bytes[5] == 0xB1.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0xE1.toByte()
    }

    private fun extractWord97Text(wordDoc: ByteArray, ole: Ole2Container): String? {
        if (wordDoc.size < 0x01AA) return null
        val buf = ByteBuffer.wrap(wordDoc).order(ByteOrder.LITTLE_ENDIAN)

        val wIdent = buf.getShort(0).toInt() and 0xFFFF
        if (wIdent != 0xA5EC && wIdent != 0xA5DC) return null

        val flags = buf.getShort(10).toInt() and 0xFFFF
        val isEncrypted = (flags and 0x0100) != 0
        if (isEncrypted) {
            throw BookParseException(
                message = "This Word (.doc) file is password-protected or encrypted.",
                isDrmProtected = true
            )
        }

        val use1Table = (flags and 0x0200) != 0
        val tableStream = ole.readStream(if (use1Table) "1Table" else "0Table")
            ?: ole.readStream(if (use1Table) "0Table" else "1Table")
            ?: return null

        // FIB Clx offset and length (Word 97–2003 FibRgFcLcb97)
        val fcClx = buf.getInt(0x01A2)
        val lcbClx = buf.getInt(0x01A6)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > tableStream.size) {
            return null
        }

        val tblBuf = ByteBuffer.wrap(tableStream).order(ByteOrder.LITTLE_ENDIAN)
        var pos = fcClx
        val clxEnd = fcClx + lcbClx

        // Skip optional Prc (0x01) records until Pcdt (0x02)
        while (pos < clxEnd) {
            val clxt = tableStream[pos].toInt() and 0xFF
            pos++
            if (clxt == 0x01) {
                if (pos + 2 > clxEnd) return null
                val cbGrpprl = tblBuf.getShort(pos).toInt() and 0xFFFF
                pos += 2 + cbGrpprl
            } else if (clxt == 0x02) {
                break
            } else {
                return null
            }
        }

        if (pos + 4 > clxEnd) return null
        val pcdtSize = tblBuf.getInt(pos)
        pos += 4
        if (pcdtSize < 16 || pos + pcdtSize > tableStream.size) return null

        val pieceCount = (pcdtSize - 4) / 12
        if (pieceCount <= 0) return null

        val cps = IntArray(pieceCount + 1) { idx -> tblBuf.getInt(pos + idx * 4) }
        val pcdBase = pos + (pieceCount + 1) * 4

        val cp1252 = Charset.forName("windows-1252")
        val sb = StringBuilder(wordDoc.size / 2)

        for (i in 0 until pieceCount) {
            val charCount = cps[i + 1] - cps[i]
            if (charCount <= 0) continue
            val pcdOffset = pcdBase + i * 8
            if (pcdOffset + 8 > tableStream.size) break

            val fcValue = tblBuf.getInt(pcdOffset + 2)
            val isCompressed = (fcValue and 0x40000000) != 0
            if (isCompressed) {
                val byteOffset = (fcValue and 0x3FFFFFFF) ushr 1
                val available = (wordDoc.size - byteOffset).coerceAtMost(charCount)
                if (byteOffset >= 0 && available > 0) {
                    sb.append(String(wordDoc, byteOffset, available, cp1252))
                }
            } else {
                val byteOffset = fcValue and 0x3FFFFFFF
                val byteLen = charCount * 2
                val available = (wordDoc.size - byteOffset).coerceAtMost(byteLen)
                if (byteOffset >= 0 && available >= 2) {
                    sb.append(String(wordDoc, byteOffset, available - (available % 2), Charsets.UTF_16LE))
                }
            }
        }

        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun stripWordFieldCodes(raw: String): String {
        val out = StringBuilder(raw.length)
        var fieldDepth = 0
        var inDisplayResult = false
        for (ch in raw) {
            when (ch.code) {
                0x13 -> {
                    fieldDepth++
                    inDisplayResult = false
                }
                0x14 -> {
                    if (fieldDepth > 0) inDisplayResult = true
                }
                0x15 -> {
                    if (fieldDepth > 0) fieldDepth--
                    if (fieldDepth == 0) inDisplayResult = false
                }
                else -> {
                    if (fieldDepth == 0 || inDisplayResult) {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }

    private fun convertWordTextToBlocks(text: String): List<BookBlock> {
        val normalized = text
            .replace("\r\n", "\r")
            .replace('\n', '\r')
            .replace('\u000B', '\r')
            .replace('\u000C', '\r')

        val blocks = mutableListOf<BookBlock>()
        val currentRow = mutableListOf<String>()

        for (segment in normalized.split('\r')) {
            if (segment.contains('\u0007')) {
                val cells = segment.split('\u0007')
                    .map { cleanControlChars(it) }
                    .filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) {
                    currentRow.addAll(cells)
                } else if (currentRow.isNotEmpty()) {
                    blocks.add(BookBlock.TableRow(currentRow.toList()))
                    currentRow.clear()
                }
            } else {
                if (currentRow.isNotEmpty()) {
                    blocks.add(BookBlock.TableRow(currentRow.toList()))
                    currentRow.clear()
                }
                val clean = cleanControlChars(segment)
                if (clean.isNotEmpty()) {
                    blocks.add(BookBlock.Paragraph(clean))
                }
            }
        }
        if (currentRow.isNotEmpty()) {
            blocks.add(BookBlock.TableRow(currentRow.toList()))
        }
        return blocks
    }

    private fun cleanControlChars(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '\t' -> sb.append("  ")
                ch.code >= 0x20 && ch != '\uFFFD' -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private fun extractFallbackPrintableText(wordDoc: ByteArray): String {
        val sb = StringBuilder()
        val run = StringBuilder()
        for (b in wordDoc) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == 0x0D || c == 0x0A || c == 0x09) {
                run.append(c.toChar())
            } else {
                if (run.length >= 20) {
                    sb.append(run).append('\r')
                }
                run.setLength(0)
            }
        }
        if (run.length >= 20) sb.append(run)
        return sb.toString()
    }

    private class Ole2Container(
        private val data: ByteArray,
        private val sectorSize: Int,
        private val miniSectorSize: Int,
        private val miniCutoff: Int,
        private val fat: IntArray,
        private val miniFat: IntArray,
        private val miniStream: ByteArray,
        private val entries: Map<String, DirEntry>
    ) {
        data class DirEntry(
            val name: String,
            val type: Int,
            val startSector: Int,
            val size: Int
        )

        fun readStream(name: String): ByteArray? {
            val entry = entries[name.lowercase(Locale.US)] ?: return null
            if (entry.size <= 0 || entry.startSector < 0) return ByteArray(0)
            return if (entry.size < miniCutoff && miniStream.isNotEmpty()) {
                readMiniStream(entry.startSector, entry.size)
            } else {
                readRegularStream(data, sectorSize, fat, entry.startSector, entry.size)
            }
        }

        private fun readMiniStream(startSector: Int, size: Int): ByteArray {
            val out = ByteArrayOutputStream(size)
            var sector = startSector
            var remaining = size
            var guard = 0
            while (sector >= 0 && remaining > 0 && guard++ < 200_000) {
                val offset = sector * miniSectorSize
                if (offset < 0 || offset >= miniStream.size) break
                val toRead = remaining.coerceAtMost(miniSectorSize).coerceAtMost(miniStream.size - offset)
                out.write(miniStream, offset, toRead)
                remaining -= toRead
                sector = if (sector < miniFat.size) miniFat[sector] else ENDOFCHAIN
            }
            return out.toByteArray()
        }

        companion object {
            fun parse(data: ByteArray): Ole2Container {
                val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val sectorShift = header.getShort(30).toInt() and 0xFFFF
                val miniSectorShift = header.getShort(32).toInt() and 0xFFFF
                val sectorSize = 1 shl sectorShift.coerceIn(9, 12)
                val miniSectorSize = 1 shl miniSectorShift.coerceIn(6, 9)

                val firstDirSector = header.getInt(48)
                val miniCutoff = header.getInt(56).coerceAtLeast(0)
                val firstMiniFatSector = header.getInt(60)
                val numMiniFatSectors = header.getInt(64).coerceAtLeast(0)
                val firstDifatSector = header.getInt(68)
                val numDifatSectors = header.getInt(72).coerceAtLeast(0)

                // Collect FAT sector IDs from header DIFAT (109 entries) + extended DIFAT sectors
                val fatSectors = mutableListOf<Int>()
                for (i in 0 until 109) {
                    val sec = header.getInt(76 + i * 4)
                    if (sec >= 0) fatSectors.add(sec)
                }
                var difatSec = firstDifatSector
                var difatCount = 0
                val intsPerSector = sectorSize / 4
                while (difatSec >= 0 && difatCount < numDifatSectors && difatCount < 4096) {
                    val base = sectorOffset(difatSec, sectorSize)
                    if (base + sectorSize > data.size) break
                    for (i in 0 until intsPerSector - 1) {
                        val sec = header.getInt(base + i * 4)
                        if (sec >= 0) fatSectors.add(sec)
                    }
                    difatSec = header.getInt(base + (intsPerSector - 1) * 4)
                    difatCount++
                }

                // Build FAT
                val fat = IntArray(fatSectors.size * intsPerSector) { ENDOFCHAIN }
                var fatPos = 0
                for (fatSec in fatSectors) {
                    val base = sectorOffset(fatSec, sectorSize)
                    if (base + sectorSize > data.size) break
                    for (i in 0 until intsPerSector) {
                        fat[fatPos++] = header.getInt(base + i * 4)
                    }
                }

                // Read Directory stream
                val dirBytes = readRegularStream(data, sectorSize, fat, firstDirSector, Int.MAX_VALUE)
                val dirBuf = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
                val entryCount = dirBytes.size / 128
                val entryMap = mutableMapOf<String, DirEntry>()
                var rootEntry: DirEntry? = null

                for (i in 0 until entryCount) {
                    val base = i * 128
                    val nameByteLen = (dirBuf.getShort(base + 64).toInt() and 0xFFFF).coerceIn(0, 64)
                    val objType = dirBytes[base + 66].toInt() and 0xFF
                    if (objType == 0 || nameByteLen < 2) continue
                    val name = String(dirBytes, base, (nameByteLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                        .trim('\u0000')
                    val startSec = dirBuf.getInt(base + 116)
                    val size = dirBuf.getInt(base + 120)
                    val entry = DirEntry(name, objType, startSec, size)
                    if (objType == 5 && rootEntry == null) {
                        rootEntry = entry
                    } else if (objType == 2) {
                        entryMap[name.lowercase(Locale.US)] = entry
                    }
                }

                // Build MiniFAT & Root MiniStream
                val miniFatBytes = if (firstMiniFatSector >= 0 && numMiniFatSectors > 0) {
                    readRegularStream(data, sectorSize, fat, firstMiniFatSector, numMiniFatSectors * sectorSize)
                } else {
                    ByteArray(0)
                }
                val miniFat = IntArray(miniFatBytes.size / 4)
                if (miniFat.isNotEmpty()) {
                    val mb = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in miniFat.indices) miniFat[i] = mb.getInt(i * 4)
                }

                val miniStream = if (rootEntry != null && rootEntry.startSector >= 0 && rootEntry.size > 0) {
                    readRegularStream(data, sectorSize, fat, rootEntry.startSector, rootEntry.size)
                } else {
                    ByteArray(0)
                }

                return Ole2Container(data, sectorSize, miniSectorSize, miniCutoff, fat, miniFat, miniStream, entryMap)
            }

            private fun sectorOffset(sector: Int, sectorSize: Int): Int = (sector + 1) * sectorSize

            private fun readRegularStream(
                data: ByteArray,
                sectorSize: Int,
                fat: IntArray,
                startSector: Int,
                maxBytes: Int
            ): ByteArray {
                val out = ByteArrayOutputStream(if (maxBytes == Int.MAX_VALUE) 8192 else maxBytes.coerceAtMost(1024 * 1024))
                var sector = startSector
                var remaining = maxBytes
                var guard = 0
                while (sector >= 0 && sector != ENDOFCHAIN && sector != FREESECT && sector != FATSECT && sector != DIFSECT && remaining > 0 && guard++ < 200_000) {
                    val offset = sectorOffset(sector, sectorSize)
                    if (offset < 0 || offset >= data.size) break
                    val toRead = remaining.coerceAtMost(sectorSize).coerceAtMost(data.size - offset)
                    out.write(data, offset, toRead)
                    if (remaining != Int.MAX_VALUE) remaining -= toRead
                    sector = if (sector < fat.size) fat[sector] else ENDOFCHAIN
                }
                return out.toByteArray()
            }
        }
    }
}
