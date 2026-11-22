package com.example.books

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal object TextEncodingDecoder {
    fun decodeSmart(bytes: ByteArray, preferredCharset: Charset? = null): String {
        if (bytes.isEmpty()) return ""
        // Check BOM markers
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        if (preferredCharset != null) {
            return runCatching { String(bytes, preferredCharset) }
                .getOrElse { String(bytes, Charsets.UTF_8) }
        }

        // Strict UTF-8 validation
        val utf8Valid = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
        if (utf8Valid != null) return utf8Valid

        // Detect Cyrillic Windows-1251 vs Western Windows-1252
        var cyrillicRangeCount = 0
        var highByteCount = 0
        val sampleLimit = bytes.size.coerceAtMost(8192)
        for (i in 0 until sampleLimit) {
            val unsigned = bytes[i].toInt() and 0xFF
            if (unsigned >= 0x80) {
                highByteCount++
                if (unsigned in 0xC0..0xFF) cyrillicRangeCount++
            }
        }
        val fallbackCharsetName = if (highByteCount > 12 && cyrillicRangeCount.toFloat() / highByteCount > 0.72f) {
            "windows-1251"
        } else {
            "windows-1252"
        }
        return runCatching { String(bytes, Charset.forName(fallbackCharsetName)) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }
}

internal object HtmlBlockParser {
    private val SCRIPT_STYLE_REGEX = Regex(
        "<(script|style|head|noscript|svg)[^>]*>[\\s\\S]*?</\\1>",
        RegexOption.IGNORE_CASE
    )
    private val COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val TITLE_REGEX = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
    private val BLOCK_TAG_REGEX = Regex(
        "<(h[1-6]|p|blockquote|li|tr|hr|img|div|pre)([^>]*?)(/?)>([\\s\\S]*?)(?:</\\1>)?",
        RegexOption.IGNORE_CASE
    )
    private val IMG_SRC_REGEX = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val IMG_ALT_REGEX = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val TD_TH_REGEX = Regex("<t[dh][^>]*>([\\s\\S]*?)</t[dh]>", RegexOption.IGNORE_CASE)
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val ANY_TAG_REGEX = Regex("<[^>]+>")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(x?[0-9a-fA-F]+);")

    fun extractTitle(html: String): String? {
        val match = TITLE_REGEX.find(html) ?: return null
        return decodeHtmlEntities(stripTags(match.groupValues[1])).trim().takeIf { it.isNotBlank() }
    }

    fun parseHtmlToBlocks(
        html: String,
        imageResolver: ((String) -> String?)? = null
    ): List<BookBlock> {
        val cleaned = html
            .replace(COMMENT_REGEX, " ")
            .replace(SCRIPT_STYLE_REGEX, " ")

        val bodyStart = cleaned.indexOf("<body", ignoreCase = true)
        val bodyContent = if (bodyStart >= 0) {
            val tagEnd = cleaned.indexOf('>', bodyStart)
            if (tagEnd >= 0) cleaned.substring(tagEnd + 1) else cleaned
        } else {
            cleaned
        }

        val blocks = mutableListOf<BookBlock>()
        // Split cleanly on major structural elements using a lightweight scanner
        val tokenRegex = Regex(
            "<(/?)(h[1-6]|p|blockquote|li|tr|hr|img|pre|div|section|article|table)([^>]*)>",
            RegexOption.IGNORE_CASE
        )

        var cursor = 0
        var activeTag: String? = null
        val buffer = StringBuilder()

        fun flushBuffer(tag: String?) {
            val raw = buffer.toString()
            buffer.setLength(0)
            val normalized = normalizeInlineText(raw)
            if (normalized.isBlank()) return
            when (tag) {
                "h1" -> blocks.add(BookBlock.Heading(1, normalized))
                "h2" -> blocks.add(BookBlock.Heading(2, normalized))
                "h3" -> blocks.add(BookBlock.Heading(3, normalized))
                "h4", "h5", "h6" -> blocks.add(BookBlock.Heading(4, normalized))
                "blockquote" -> blocks.add(BookBlock.Quote(normalized))
                "li" -> blocks.add(BookBlock.ListItem("•", normalized))
                "pre" -> blocks.add(BookBlock.Quote(raw.replace(ANY_TAG_REGEX, "").let(::decodeHtmlEntities).trim()))
                "tr" -> {
                    val cells = TD_TH_REGEX.findAll(raw)
                        .map { normalizeInlineText(it.groupValues[1]) }
                        .filter { it.isNotBlank() }
                        .toList()
                    if (cells.isNotEmpty()) {
                        blocks.add(BookBlock.TableRow(cells))
                    } else {
                        blocks.add(BookBlock.Paragraph(normalized))
                    }
                }
                else -> blocks.add(BookBlock.Paragraph(normalized))
            }
        }

        for (match in tokenRegex.findAll(bodyContent)) {
            val start = match.range.first
            if (start > cursor) {
                buffer.append(bodyContent, cursor, start)
            }
            cursor = match.range.last + 1

            val isClosing = match.groupValues[1] == "/"
            val tag = match.groupValues[2].lowercase(Locale.US)
            val attrs = match.groupValues[3]

            when {
                tag == "hr" -> {
                    flushBuffer(activeTag)
                    activeTag = null
                    blocks.add(BookBlock.Divider)
                }
                tag == "img" -> {
                    if (imageResolver != null) {
                        val src = IMG_SRC_REGEX.find(attrs)?.groupValues?.getOrNull(1)
                        val resolved = src?.let { imageResolver(it) }
                        if (resolved != null) {
                            flushBuffer(activeTag)
                            val alt = IMG_ALT_REGEX.find(attrs)?.groupValues?.getOrNull(1)
                                ?.let(::decodeHtmlEntities)?.trim()?.takeIf { it.isNotBlank() }
                            blocks.add(BookBlock.ImageBlock(resolved, alt))
                        }
                    }
                }
                tag == "section" || tag == "article" || tag == "table" -> {
                    flushBuffer(activeTag)
                    activeTag = null
                }
                isClosing -> {
                    flushBuffer(activeTag ?: tag)
                    activeTag = null
                }
                else -> {
                    if (buffer.isNotBlank()) {
                        flushBuffer(activeTag)
                    }
                    activeTag = tag
                }
            }
        }

        if (cursor < bodyContent.length) {
            buffer.append(bodyContent, cursor, bodyContent.length)
        }
        flushBuffer(activeTag)

        if (blocks.isEmpty()) {
            val fallbackText = normalizeInlineText(bodyContent)
            if (fallbackText.isNotBlank()) {
                fallbackText.split(Regex("\\n{2,}")).forEach { para ->
                    val trimmed = para.trim()
                    if (trimmed.isNotEmpty()) blocks.add(BookBlock.Paragraph(trimmed))
                }
            }
        }

        return blocks
    }

    fun stripTags(input: String): String {
        return input
            .replace(BR_REGEX, "\n")
            .replace(ANY_TAG_REGEX, " ")
    }

    fun normalizeInlineText(rawHtml: String): String {
        val withNewlines = rawHtml.replace(BR_REGEX, "\n")
        val noTags = withNewlines.replace(ANY_TAG_REGEX, " ")
        val decoded = decodeHtmlEntities(noTags)
        return decoded
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { line -> line.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        val named = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&copy;", "©")
            .replace("&bull;", "•")
        return NUMERIC_ENTITY_REGEX.replace(named) { m ->
            val raw = m.groupValues[1]
            val codePoint = if (raw.startsWith("x", ignoreCase = true)) {
                raw.substring(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull(10)
            }
            if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                String(Character.toChars(codePoint))
            } else {
                m.value
            }
        }
    }
}

object TxtParser {
    private val CHAPTER_HEADING_REGEX = Regex(
        "^(chapter|part|book|section|prologue|epilogue|глава|часть|раздел)\\b.{0,70}$",
        RegexOption.IGNORE_CASE
    )

    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        val decoded = TextEncodingDecoder.decodeSmart(bytes)
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val rawParagraphs = decoded.split(Regex("\\n\\s*\\n"))
            .map { chunk ->
                chunk.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }
            .filter { it.isNotBlank() }

        // If a text file uses single newlines instead of blank lines, break long blocks by line
        val paragraphs = if (rawParagraphs.size <= 3 && decoded.length > 1500) {
            decoded.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            rawParagraphs
        }

        val blocks = paragraphs.map { text ->
            if (text.length <= 80 && CHAPTER_HEADING_REGEX.matches(text)) {
                BookBlock.Heading(2, text)
            } else {
                BookBlock.Paragraph(text)
            }
        }

        val chapters = BookBuilder.splitBlocksIntoChapters(blocks, fallbackTitle)
        return BookBuilder.buildBook(
            title = fallbackTitle,
            author = null,
            format = BookFormat.TXT,
            rawChapters = chapters
        )
    }
}

object MarkdownParser {
    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
    private val UNORDERED_LIST_REGEX = Regex("^[-*+]\\s+(.+)$")
    private val ORDERED_LIST_REGEX = Regex("^(\\d+\\.)\\s+(.+)$")
    private val DIVIDER_REGEX = Regex("^([-*_])\\1{2,}\\s*$")
    private val LINK_REGEX = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val IMAGE_REGEX = Regex("!\\[([^\\]]*)]\\([^)]+\\)")

    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        val decoded = TextEncodingDecoder.decodeSmart(bytes)
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val blocks = mutableListOf<BookBlock>()
        val paragraphBuf = StringBuilder()
        var inCodeBlock = false
        val codeBuf = StringBuilder()
        var detectedTitle: String? = null

        fun flushParagraph() {
            val text = cleanInlineMarkdown(paragraphBuf.toString().trim())
            paragraphBuf.setLength(0)
            if (text.isNotEmpty()) blocks.add(BookBlock.Paragraph(text))
        }

        for (rawLine in decoded.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```")) {
                flushParagraph()
                if (inCodeBlock) {
                    val code = codeBuf.toString().trimEnd()
                    codeBuf.setLength(0)
                    if (code.isNotEmpty()) blocks.add(BookBlock.Quote(code))
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                codeBuf.append(rawLine).append('\n')
                continue
            }
            if (trimmed.isEmpty()) {
                flushParagraph()
                continue
            }
            if (DIVIDER_REGEX.matches(trimmed)) {
                flushParagraph()
                blocks.add(BookBlock.Divider)
                continue
            }
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                val level = headingMatch.groupValues[1].length.coerceIn(1, 4)
                val titleText = cleanInlineMarkdown(headingMatch.groupValues[2].trim().trimEnd('#').trim())
                if (level == 1 && detectedTitle == null) detectedTitle = titleText
                blocks.add(BookBlock.Heading(level, titleText))
                continue
            }
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoteText = cleanInlineMarkdown(trimmed.removePrefix(">").trim())
                if (quoteText.isNotEmpty()) blocks.add(BookBlock.Quote(quoteText))
                continue
            }
            val ulMatch = UNORDERED_LIST_REGEX.matchEntire(trimmed)
            if (ulMatch != null) {
                flushParagraph()
                blocks.add(BookBlock.ListItem("•", cleanInlineMarkdown(ulMatch.groupValues[1])))
                continue
            }
            val olMatch = ORDERED_LIST_REGEX.matchEntire(trimmed)
            if (olMatch != null) {
                flushParagraph()
                blocks.add(BookBlock.ListItem(olMatch.groupValues[1], cleanInlineMarkdown(olMatch.groupValues[2])))
                continue
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                flushParagraph()
                val cells = trimmed.trim('|').split('|').map { cleanInlineMarkdown(it.trim()) }
                val isSeparatorRow = cells.all { cell -> cell.all { ch -> ch == '-' || ch == ':' || ch == ' ' } }
                if (!isSeparatorRow && cells.any { it.isNotEmpty() }) {
                    blocks.add(BookBlock.TableRow(cells))
                }
                continue
            }

            if (paragraphBuf.isNotEmpty()) paragraphBuf.append(' ')
            paragraphBuf.append(trimmed)
        }
        flushParagraph()
        if (codeBuf.isNotEmpty()) {
            blocks.add(BookBlock.Quote(codeBuf.toString().trimEnd()))
        }

        val title = detectedTitle ?: fallbackTitle
        val chapters = BookBuilder.splitBlocksIntoChapters(blocks, title)
        return BookBuilder.buildBook(
            title = title,
            author = null,
            format = BookFormat.MARKDOWN,
            rawChapters = chapters
        )
    }

    private fun cleanInlineMarkdown(text: String): String {
        return text
            .replace(IMAGE_REGEX, "$1")
            .replace(LINK_REGEX, "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("~~", "")
            .replace("`", "")
            .replace(Regex("(?<!\\w)\\*([^*]+)\\*(?!\\w)"), "$1")
    }
}

object HtmlBookParser {
    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        val html = TextEncodingDecoder.decodeSmart(bytes)
        val title = HtmlBlockParser.extractTitle(html) ?: fallbackTitle
        val blocks = HtmlBlockParser.parseHtmlToBlocks(html)
        val chapters = BookBuilder.splitBlocksIntoChapters(blocks, title)
        return BookBuilder.buildBook(
            title = title,
            author = null,
            format = BookFormat.HTML,
            rawChapters = chapters
        )
    }
}

/**
 * Pure-Kotlin unencrypted MOBI / PRC / PalmDOC parser.
 * Supports Compression=1 (uncompressed) and Compression=2 (PalmDOC LZ77).
 * Detects DRM encryption and reports a clear error.
 */
object MobiParser {
    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        if (bytes.size < 86) {
            throw BookParseException("MOBI file is truncated or invalid.")
        }

        val numRecords = readUInt16BE(bytes, 76)
        if (numRecords < 2 || 78 + numRecords * 8 > bytes.size) {
            throw BookParseException("Invalid PalmDB record table in MOBI file.")
        }

        val recordOffsets = IntArray(numRecords) { idx ->
            readUInt32BE(bytes, 78 + idx * 8).toInt()
        }

        val rec0Start = recordOffsets[0]
        val rec0End = recordOffsets[1].coerceAtMost(bytes.size)
        if (rec0Start < 0 || rec0Start + 16 > rec0End) {
            throw BookParseException("Invalid MOBI Record 0 header.")
        }

        val compression = readUInt16BE(bytes, rec0Start)
        val textRecordCount = readUInt16BE(bytes, rec0Start + 8)
        val encryptionType = readUInt16BE(bytes, rec0Start + 12)

        if (encryptionType != 0) {
            throw BookParseException(
                message = "This MOBI/Kindle book is DRM-protected and cannot be opened.",
                isDrmProtected = true
            )
        }

        if (compression != 1 && compression != 2) {
            throw BookParseException(
                "This MOBI file uses unsupported compression (type $compression, such as HUFF/CDIC)."
            )
        }

        var encoding: Charset = Charset.forName("windows-1252")
        var title = fallbackTitle
        var extraDataFlags = 0

        if (rec0Start + 36 <= rec0End) {
            val magic = String(bytes, rec0Start + 16, 4, Charsets.ISO_8859_1)
            if (magic == "MOBI") {
                val mobiHeaderLen = readUInt32BE(bytes, rec0Start + 20).toInt()
                val textEncodingCode = readUInt32BE(bytes, rec0Start + 28).toInt()
                encoding = if (textEncodingCode == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")

                if (rec0Start + 92 <= rec0End) {
                    val fullNameOffset = readUInt32BE(bytes, rec0Start + 84).toInt()
                    val fullNameLength = readUInt32BE(bytes, rec0Start + 88).toInt()
                    if (fullNameOffset > 0 && fullNameLength in 1..512 &&
                        rec0Start + fullNameOffset + fullNameLength <= rec0End
                    ) {
                        val extractedTitle = String(
                            bytes,
                            rec0Start + fullNameOffset,
                            fullNameLength,
                            encoding
                        ).trim('\u0000', ' ')
                        if (extractedTitle.isNotBlank()) title = extractedTitle
                    }
                }

                if (mobiHeaderLen >= 228 && rec0Start + 244 <= rec0End) {
                    extraDataFlags = readUInt16BE(bytes, rec0Start + 242)
                }
            }
        }

        // Fallback PalmDB title (bytes 0..31)
        if (title == fallbackTitle) {
            val pdbName = String(bytes, 0, 32, Charsets.ISO_8859_1).trim('\u0000', ' ')
            if (pdbName.isNotBlank() && !pdbName.contains('\uFFFD')) {
                title = pdbName.replace('_', ' ')
            }
        }

        val out = ByteArrayOutputStream(64 * 1024)
        val maxRecord = minOf(textRecordCount, numRecords - 1)
        val maxOutputBytes = 12 * 1024 * 1024 // Cap decoded text at 12 MB for OOM safety

        for (i in 1..maxRecord) {
            if (out.size() >= maxOutputBytes) break
            val start = recordOffsets[i]
            val rawEnd = if (i + 1 < numRecords) recordOffsets[i + 1] else bytes.size
            val end = trimTrailingExtraBytes(bytes, start, rawEnd.coerceAtMost(bytes.size), extraDataFlags)
            if (end <= start) continue

            if (compression == 1) {
                out.write(bytes, start, end - start)
            } else {
                decompressPalmDocLz77(bytes, start, end, out)
            }
        }

        val textBytes = out.toByteArray()
        val decoded = TextEncodingDecoder.decodeSmart(textBytes, preferredCharset = encoding)
        val looksLikeHtml = decoded.contains("<p", ignoreCase = true) ||
            decoded.contains("<html", ignoreCase = true) ||
            decoded.contains("<body", ignoreCase = true) ||
            decoded.contains("<br", ignoreCase = true)

        val blocks = if (looksLikeHtml) {
            HtmlBlockParser.parseHtmlToBlocks(decoded)
        } else {
            decoded.replace("\r\n", "\n")
                .split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { BookBlock.Paragraph(it) }
        }

        val chapters = BookBuilder.splitBlocksIntoChapters(blocks, title)
        return BookBuilder.buildBook(
            title = title,
            author = null,
            format = BookFormat.MOBI,
            rawChapters = chapters
        )
    }

    private fun trimTrailingExtraBytes(
        data: ByteArray,
        start: Int,
        end: Int,
        flags: Int
    ): Int {
        if (flags == 0 || end <= start) return end
        var curEnd = end
        // Strip variable-length trailing entries for bits 1..15
        var flagMask = flags ushr 1
        while (flagMask != 0 && curEnd > start) {
            if ((flagMask and 1) != 0) {
                val entrySize = readBackwardVarInt(data, start, curEnd)
                if (entrySize <= 0 || curEnd - entrySize < start) break
                curEnd -= entrySize
            }
            flagMask = flagMask ushr 1
        }
        // Bit 0: multibyte character overlap count
        if ((flags and 1) != 0 && curEnd > start) {
            val overlap = (data[curEnd - 1].toInt() and 0x03) + 1
            if (curEnd - overlap >= start) {
                curEnd -= overlap
            }
        }
        return curEnd
    }

    private fun readBackwardVarInt(data: ByteArray, start: Int, end: Int): Int {
        var value = 0
        var shift = 0
        var pos = end - 1
        while (pos >= start && shift < 28) {
            val b = data[pos].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            shift += 7
            if ((b and 0x80) != 0) break
            pos--
        }
        return value
    }

    internal fun decompressPalmDocLz77(
        src: ByteArray,
        start: Int,
        end: Int,
        out: ByteArrayOutputStream
    ) {
        val local = ByteArrayOutputStream(4096)
        var i = start
        while (i < end) {
            val b = src[i++].toInt() and 0xFF
            when {
                b == 0 -> local.write(0)
                b in 1..8 -> {
                    val count = b.coerceAtMost(end - i)
                    local.write(src, i, count)
                    i += count
                }
                b in 0x09..0x7F -> local.write(b)
                b in 0x80..0xBF -> {
                    if (i >= end) break
                    val next = src[i++].toInt() and 0xFF
                    val combined = ((b shl 8) or next) and 0x3FFF
                    val distance = combined ushr 3
                    val length = (combined and 0x07) + 3
                    val buf = local.toByteArray()
                    val copyStart = buf.size - distance
                    if (distance > 0 && copyStart >= 0) {
                        val temp = ByteArray(length)
                        for (k in 0 until length) {
                            val idx = copyStart + k
                            temp[k] = if (idx < buf.size) buf[idx] else temp[idx - buf.size]
                        }
                        local.write(temp)
                    }
                }
                else -> {
                    // 0xC0..0xFF: space + (b xor 0x80)
                    local.write(' '.code)
                    local.write(b xor 0x80)
                }
            }
        }
        out.write(local.toByteArray())
    }

    private fun readUInt16BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32BE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}
