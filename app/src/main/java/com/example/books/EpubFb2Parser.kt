package com.example.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

internal object SafeXmlSax {
    fun parse(xml: String, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val parser = factory.newSAXParser()
        val cleaned = xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        parser.parse(InputSource(StringReader(cleaned)), handler)
    }

    fun getAttr(attributes: Attributes, localName: String): String? {
        for (i in 0 until attributes.length) {
            val qName = attributes.getQName(i)
            if (qName.equals(localName, ignoreCase = true) || qName.endsWith(":$localName", ignoreCase = true)) {
                return attributes.getValue(i)
            }
        }
        return null
    }
}

internal object PureBase64Decoder {
    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        for (c in 'A'..'Z') this[c.code] = c.code - 'A'.code
        for (c in 'a'..'z') this[c.code] = c.code - 'a'.code + 26
        for (c in '0'..'9') this[c.code] = c.code - '0'.code + 52
        this['+'.code] = 62
        this['/'.code] = 63
    }

    fun decode(input: String): ByteArray {
        val out = ByteArrayOutputStream((input.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (i in input.indices) {
            val ch = input[i].code
            if (ch == '='.code) break
            if (ch >= 128) continue
            val value = DECODE_TABLE[ch]
            if (value < 0) continue
            acc = (acc shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

internal object BookBitmapHelper {
    fun decodeThumbnail(bytes: ByteArray?, targetWidthPx: Int = 360): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
            null
        }
    }
}

object EpubParser {
    private const val MAX_IMAGE_TOTAL_BYTES = 10 * 1024 * 1024
    private const val MAX_SINGLE_ENTRY_BYTES = 4 * 1024 * 1024

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        val entries = mutableMapOf<String, ByteArray>()
        var totalImageBytes = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normPath = normalizeZipPath(entry.name)
                    val isImage = isImagePath(normPath)
                    val shouldRead = !isImage || totalImageBytes < MAX_IMAGE_TOTAL_BYTES
                    if (shouldRead) {
                        val content = readBoundedBytes(zis, MAX_SINGLE_ENTRY_BYTES)
                        if (content != null) {
                            entries[normPath] = content
                            if (isImage) totalImageBytes += content.size
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (entries.isEmpty()) {
            throw BookParseException("EPUB archive is empty or corrupted.")
        }

        val encryptionXml = entries["meta-inf/encryption.xml"]?.let { TextEncodingDecoder.decodeSmart(it) }
        if (encryptionXml != null && hasContentDrm(encryptionXml)) {
            throw BookParseException(
                message = "This EPUB book is DRM-protected and cannot be opened.",
                isDrmProtected = true
            )
        }

        val containerXml = entries["meta-inf/container.xml"]?.let { TextEncodingDecoder.decodeSmart(it) }
        val opfPath = containerXml?.let(::parseOpfPathFromContainer)
            ?: entries.keys.firstOrNull { it.endsWith(".opf") }
            ?: throw BookParseException("EPUB package metadata (.opf) was not found.")

        val opfKey = normalizeZipPath(opfPath)
        val opfBytes = entries[opfKey]
            ?: throw BookParseException("Unable to read EPUB OPF package file.")
        val opfDir = opfKey.substringBeforeLast('/', missingDelimiterValue = "")

        val parsedOpf = parseOpf(TextEncodingDecoder.decodeSmart(opfBytes))

        val images = mutableMapOf<String, ByteArray>()
        for ((path, data) in entries) {
            if (isImagePath(path)) {
                images[path] = data
            }
        }

        val coverHref = parsedOpf.coverId?.let { id -> parsedOpf.manifest[id]?.href }
            ?: parsedOpf.manifest.values.firstOrNull { it.properties.contains("cover-image") }?.href
            ?: parsedOpf.manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") && it.id.contains("cover", ignoreCase = true)
            }?.href
        val coverPath = coverHref?.let { resolveRelativePath(opfDir, it) }
        val coverBitmap = BookBitmapHelper.decodeThumbnail(coverPath?.let { images[it] })

        val ncxTitles = parsedOpf.manifest.values
            .firstOrNull { it.mediaType.contains("dtbncx") || it.href.endsWith(".ncx", ignoreCase = true) }
            ?.let { ncxItem ->
                val ncxPath = resolveRelativePath(opfDir, ncxItem.href)
                val ncxDir = ncxPath.substringBeforeLast('/', missingDelimiterValue = "")
                entries[ncxPath]?.let { parseNcxTitles(TextEncodingDecoder.decodeSmart(it), ncxDir) }
            }
            .orEmpty()

        val spineItems = if (parsedOpf.spineIdRefs.isNotEmpty()) {
            parsedOpf.spineIdRefs.mapNotNull { parsedOpf.manifest[it] }
        } else {
            parsedOpf.manifest.values.filter {
                it.mediaType.contains("html") || it.href.endsWith(".xhtml", true) || it.href.endsWith(".html", true)
            }
        }

        val rawChapters = mutableListOf<Pair<String, List<BookBlock>>>()
        for ((index, item) in spineItems.withIndex()) {
            val itemPath = resolveRelativePath(opfDir, item.href.substringBefore('#'))
            val htmlBytes = entries[itemPath] ?: continue
            val html = TextEncodingDecoder.decodeSmart(htmlBytes)
            val itemDir = itemPath.substringBeforeLast('/', missingDelimiterValue = "")

            val blocks = HtmlBlockParser.parseHtmlToBlocks(html) { src ->
                val cleanedSrc = src.substringBefore('#').substringBefore('?')
                val resolved = resolveRelativePath(itemDir, cleanedSrc)
                if (images.containsKey(resolved)) resolved else null
            }
            if (blocks.isEmpty()) continue

            val firstHeading = blocks.firstOrNull { it is BookBlock.Heading } as? BookBlock.Heading
            val chapterTitle = ncxTitles[itemPath]
                ?: firstHeading?.text
                ?: HtmlBlockParser.extractTitle(html)
                ?: "Chapter ${index + 1}"

            rawChapters.add(chapterTitle to blocks)
        }

        val title = parsedOpf.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        return BookBuilder.buildBook(
            title = title,
            author = parsedOpf.author,
            format = BookFormat.EPUB,
            coverBitmap = coverBitmap,
            rawChapters = rawChapters,
            images = images
        )
    }

    private fun hasContentDrm(encryptionXml: String): Boolean {
        val lower = encryptionXml.lowercase(Locale.US)
        if (!lower.contains("encrypteddata")) return false
        val uriMatches = Regex("uri\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(encryptionXml)
            .map { it.groupValues[1].lowercase(Locale.US) }
            .toList()
        if (uriMatches.isEmpty()) return true
        return uriMatches.any { uri ->
            !uri.endsWith(".ttf") && !uri.endsWith(".otf") && !uri.endsWith(".woff") && !uri.endsWith(".woff2")
        }
    }

    private fun parseOpfPathFromContainer(containerXml: String): String? {
        val match = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(containerXml)
        return match?.groupValues?.getOrNull(1)
    }

    private data class ParsedOpf(
        val title: String?,
        val author: String?,
        val coverId: String?,
        val manifest: Map<String, ManifestItem>,
        val spineIdRefs: List<String>
    )

    private fun parseOpf(opfXml: String): ParsedOpf {
        var title: String? = null
        var author: String? = null
        var coverId: String? = null
        val manifest = linkedMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        runCatching {
            val textBuf = StringBuilder()
            var captureTag: String? = null

            SafeXmlSax.parse(opfXml, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    val attrs = attributes
                    when (local) {
                        "title", "creator" -> {
                            captureTag = local
                            textBuf.setLength(0)
                        }
                        "meta" -> if (attrs != null) {
                            val name = SafeXmlSax.getAttr(attrs, "name")
                            val content = SafeXmlSax.getAttr(attrs, "content")
                            if (name.equals("cover", ignoreCase = true) && !content.isNullOrBlank()) {
                                coverId = content.trim()
                            }
                        }
                        "item" -> if (attrs != null) {
                            val id = SafeXmlSax.getAttr(attrs, "id").orEmpty()
                            val href = SafeXmlSax.getAttr(attrs, "href").orEmpty()
                            val mediaType = SafeXmlSax.getAttr(attrs, "media-type").orEmpty()
                            val props = SafeXmlSax.getAttr(attrs, "properties").orEmpty()
                            if (id.isNotBlank() && href.isNotBlank()) {
                                manifest[id] = ManifestItem(id, decodeUrlPath(href), mediaType, props)
                            }
                        }
                        "itemref" -> if (attrs != null) {
                            val idref = SafeXmlSax.getAttr(attrs, "idref")
                            val linear = SafeXmlSax.getAttr(attrs, "linear")
                            if (!idref.isNullOrBlank() && !linear.equals("no", ignoreCase = true)) {
                                spine.add(idref)
                            }
                        }
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (captureTag != null && ch != null) {
                        textBuf.append(ch, start, length)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    if (local == captureTag) {
                        val text = textBuf.toString().trim()
                        if (local == "title" && title.isNullOrBlank() && text.isNotBlank()) title = text
                        if (local == "creator" && author.isNullOrBlank() && text.isNotBlank()) author = text
                        captureTag = null
                        textBuf.setLength(0)
                    }
                }
            })
        }

        return ParsedOpf(title, author, coverId, manifest, spine)
    }

    private fun parseNcxTitles(ncxXml: String, ncxDir: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        runCatching {
            val textBuf = StringBuilder()
            var inText = false
            var currentLabel: String? = null

            SafeXmlSax.parse(ncxXml, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    when (local) {
                        "text" -> {
                            inText = true
                            textBuf.setLength(0)
                        }
                        "content" -> if (attributes != null) {
                            val src = SafeXmlSax.getAttr(attributes, "src")
                            val label = currentLabel
                            if (!src.isNullOrBlank() && !label.isNullOrBlank()) {
                                val resolved = resolveRelativePath(ncxDir, decodeUrlPath(src.substringBefore('#')))
                                result.putIfAbsent(resolved, label)
                            }
                        }
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (inText && ch != null) textBuf.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val local = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    if (local == "text") {
                        currentLabel = textBuf.toString().trim().takeIf { it.isNotBlank() }
                        inText = false
                        textBuf.setLength(0)
                    }
                }
            })
        }
        return result
    }

    internal fun resolveRelativePath(baseDir: String, relativeHref: String): String {
        val combined = if (baseDir.isBlank() || relativeHref.startsWith('/')) {
            relativeHref.trimStart('/')
        } else {
            "$baseDir/$relativeHref"
        }
        val stack = ArrayDeque<String>()
        for (segment in combined.replace('\\', '/').split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/").lowercase(Locale.US)
    }

    private fun normalizeZipPath(path: String): String {
        return path.replace('\\', '/').trimStart('/').lowercase(Locale.US)
    }

    private fun decodeUrlPath(raw: String): String {
        return raw.replace("%20", " ").replace("%28", "(").replace("%29", ")")
    }

    private fun isImagePath(lowerPath: String): Boolean {
        return lowerPath.endsWith(".jpg") ||
            lowerPath.endsWith(".jpeg") ||
            lowerPath.endsWith(".png") ||
            lowerPath.endsWith(".webp") ||
            lowerPath.endsWith(".gif")
    }

    private fun readBoundedBytes(zis: ZipInputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = zis.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }
}

object Fb2Parser {
    private const val MAX_IMAGE_TOTAL_BYTES = 10 * 1024 * 1024

    fun parse(bytes: ByteArray, fallbackTitle: String): ReflowableBook {
        val xmlBytes = if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            extractFb2FromZip(bytes)
                ?: throw BookParseException("Could not find an .fb2 file inside this compressed archive.")
        } else {
            bytes
        }

        val declaredEncoding = detectXmlEncoding(xmlBytes)
        val xmlText = TextEncodingDecoder.decodeSmart(xmlBytes, preferredCharset = declaredEncoding)

        var bookTitle: String? = null
        val authorParts = mutableListOf<String>()
        var coverBinaryId: String? = null
        val images = mutableMapOf<String, ByteArray>()
        var totalImageBytes = 0

        val rawChapters = mutableListOf<Pair<String, MutableList<BookBlock>>>()
        var currentChapterBlocks: MutableList<BookBlock>? = null
        var inDescription = false
        var inBookTitle = false
        var inAuthor = false
        var inCoverpage = false
        var inBody = false
        var inTitle = false
        var inCiteOrEpigraph = false
        var inBinaryId: String? = null
        var sectionDepth = 0

        val textBuf = StringBuilder()

        fun ensureChapter(defaultTitle: String): MutableList<BookBlock> {
            val existing = currentChapterBlocks
            if (existing != null) return existing
            val created = mutableListOf<BookBlock>()
            rawChapters.add(defaultTitle to created)
            currentChapterBlocks = created
            return created
        }

        try {
            SafeXmlSax.parse(xmlText, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    val tag = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    when (tag) {
                        "description" -> inDescription = true
                        "book-title" -> if (inDescription) {
                            inBookTitle = true
                            textBuf.setLength(0)
                        }
                        "author" -> if (inDescription && authorParts.isEmpty()) inAuthor = true
                        "first-name", "middle-name", "last-name" -> if (inAuthor) textBuf.setLength(0)
                        "coverpage" -> if (inDescription) inCoverpage = true
                        "body" -> {
                            val bodyName = attributes?.let { SafeXmlSax.getAttr(it, "name") }
                            inBody = true
                            if (!bodyName.isNullOrBlank()) {
                                val chapterBlocks = mutableListOf<BookBlock>()
                                currentChapterBlocks = chapterBlocks
                                rawChapters.add(bodyName.replaceFirstChar { it.uppercase() } to chapterBlocks)
                            }
                        }
                        "section" -> if (inBody) {
                            sectionDepth++
                            if (sectionDepth == 1) {
                                val chapterBlocks = mutableListOf<BookBlock>()
                                currentChapterBlocks = chapterBlocks
                                rawChapters.add("Section ${rawChapters.size + 1}" to chapterBlocks)
                            }
                        }
                        "title" -> if (inBody) {
                            inTitle = true
                            textBuf.setLength(0)
                        }
                        "subtitle", "p", "v" -> if (inBody && !inTitle) {
                            textBuf.setLength(0)
                        }
                        "cite", "epigraph" -> if (inBody) inCiteOrEpigraph = true
                        "empty-line" -> if (inBody) {
                            ensureChapter(bookTitle ?: fallbackTitle).add(BookBlock.Divider)
                        }
                        "image" -> if (attributes != null) {
                            val href = SafeXmlSax.getAttr(attributes, "href")?.trimStart('#')
                            if (!href.isNullOrBlank()) {
                                if (inCoverpage && coverBinaryId == null) {
                                    coverBinaryId = href
                                } else if (inBody) {
                                    ensureChapter(bookTitle ?: fallbackTitle).add(BookBlock.ImageBlock(href))
                                }
                            }
                        }
                        "binary" -> if (attributes != null && totalImageBytes < MAX_IMAGE_TOTAL_BYTES) {
                            inBinaryId = SafeXmlSax.getAttr(attributes, "id")?.takeIf { it.isNotBlank() }
                            textBuf.setLength(0)
                        }
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (ch != null) {
                        textBuf.append(ch, start, length)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val tag = (qName ?: localName).orEmpty().substringAfter(':').lowercase(Locale.US)
                    when (tag) {
                        "description" -> inDescription = false
                        "book-title" -> if (inBookTitle) {
                            bookTitle = textBuf.toString().trim().takeIf { it.isNotBlank() }
                            inBookTitle = false
                        }
                        "first-name", "middle-name", "last-name" -> if (inAuthor) {
                            val part = textBuf.toString().trim()
                            if (part.isNotEmpty()) authorParts.add(part)
                        }
                        "author" -> inAuthor = false
                        "coverpage" -> inCoverpage = false
                        "body" -> inBody = false
                        "section" -> if (inBody && sectionDepth > 0) sectionDepth--
                        "cite", "epigraph" -> inCiteOrEpigraph = false
                        "title" -> if (inBody && inTitle) {
                            val heading = cleanWhitespace(textBuf.toString())
                            if (heading.isNotEmpty()) {
                                val chapterList = ensureChapter(heading)
                                if (chapterList.isEmpty() && rawChapters.isNotEmpty()) {
                                    val lastIdx = rawChapters.lastIndex
                                    rawChapters[lastIdx] = heading.take(80) to chapterList
                                }
                                chapterList.add(BookBlock.Heading(if (sectionDepth <= 1) 1 else 2, heading))
                            }
                            inTitle = false
                            textBuf.setLength(0)
                        }
                        "subtitle" -> if (inBody) {
                            val sub = cleanWhitespace(textBuf.toString())
                            if (sub.isNotEmpty()) {
                                ensureChapter(bookTitle ?: fallbackTitle).add(BookBlock.Heading(3, sub))
                            }
                            textBuf.setLength(0)
                        }
                        "p" -> if (inBody && !inTitle) {
                            val para = cleanWhitespace(textBuf.toString())
                            if (para.isNotEmpty()) {
                                val block = if (inCiteOrEpigraph) BookBlock.Quote(para) else BookBlock.Paragraph(para)
                                ensureChapter(bookTitle ?: fallbackTitle).add(block)
                            }
                            textBuf.setLength(0)
                        }
                        "v" -> if (inBody) {
                            val verse = cleanWhitespace(textBuf.toString())
                            if (verse.isNotEmpty()) {
                                ensureChapter(bookTitle ?: fallbackTitle).add(BookBlock.Quote(verse))
                            }
                            textBuf.setLength(0)
                        }
                        "binary" -> {
                            val id = inBinaryId
                            if (id != null && totalImageBytes < MAX_IMAGE_TOTAL_BYTES) {
                                val decoded = PureBase64Decoder.decode(textBuf.toString())
                                if (decoded.isNotEmpty() && totalImageBytes + decoded.size <= MAX_IMAGE_TOTAL_BYTES) {
                                    images[id] = decoded
                                    totalImageBytes += decoded.size
                                }
                            }
                            inBinaryId = null
                            textBuf.setLength(0)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            if (rawChapters.all { it.second.isEmpty() }) {
                throw BookParseException("Unable to parse FictionBook (FB2) XML: ${e.message}", cause = e)
            }
        }

        val resolvedTitle = bookTitle ?: fallbackTitle
        val author = authorParts.joinToString(" ").takeIf { it.isNotBlank() }
        val coverBitmap = BookBitmapHelper.decodeThumbnail(coverBinaryId?.let { images[it] })

        val nonEmptyChapters = rawChapters
            .filter { it.second.isNotEmpty() }
            .map { it.first to it.second.toList() }

        return BookBuilder.buildBook(
            title = resolvedTitle,
            author = author,
            format = BookFormat.FB2,
            coverBitmap = coverBitmap,
            rawChapters = nonEmptyChapters,
            images = images
        )
    }

    private fun extractFb2FromZip(bytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase(Locale.US).endsWith(".fb2")) {
                    val out = ByteArrayOutputStream()
                    zis.copyTo(out)
                    return out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun detectXmlEncoding(bytes: ByteArray): Charset? {
        val header = String(bytes, 0, bytes.size.coerceAtMost(256), Charsets.ISO_8859_1)
        val match = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(header)
        val encName = match?.groupValues?.getOrNull(1)?.trim() ?: return null
        return runCatching { Charset.forName(encName) }.getOrNull()
    }

    private fun cleanWhitespace(raw: String): String {
        return raw.replace(Regex("[\\r\\n\\t ]+"), " ").trim()
    }
}
