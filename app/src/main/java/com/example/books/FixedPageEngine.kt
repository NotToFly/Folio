package com.example.books

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.example.pdf.PageRenderKey
import com.example.pdf.PdfDocumentEngine
import com.example.pdf.PdfOpenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Common abstraction for fixed-layout page engines (PDF and CBZ comic archives).
 */
interface FixedPageDocumentEngine {
    val pageCount: Int
    suspend fun open(uri: Uri): PdfOpenInfo
    fun getCachedAspectRatio(pageIndex: Int): Float
    suspend fun ensureAspectRatio(pageIndex: Int): Float
    fun getCachedBitmap(pageIndex: Int, bucketWidthPx: Int): Bitmap?
    fun getAnyCachedBitmapForPage(pageIndex: Int): Bitmap?
    suspend fun renderPage(pageIndex: Int, bucketWidthPx: Int): Bitmap?
    suspend fun close()
}

class PdfPageEngineAdapter(context: Context) : FixedPageDocumentEngine {
    private val delegate = PdfDocumentEngine(context)

    override val pageCount: Int
        get() = delegate.pageCount

    override suspend fun open(uri: Uri): PdfOpenInfo = delegate.open(uri)

    override fun getCachedAspectRatio(pageIndex: Int): Float =
        delegate.getCachedAspectRatio(pageIndex)

    override suspend fun ensureAspectRatio(pageIndex: Int): Float =
        delegate.ensureAspectRatio(pageIndex)

    override fun getCachedBitmap(pageIndex: Int, bucketWidthPx: Int): Bitmap? =
        delegate.getCachedBitmap(pageIndex, bucketWidthPx)

    override fun getAnyCachedBitmapForPage(pageIndex: Int): Bitmap? =
        delegate.getAnyCachedBitmapForPage(pageIndex)

    override suspend fun renderPage(pageIndex: Int, bucketWidthPx: Int): Bitmap? =
        delegate.renderPage(pageIndex, bucketWidthPx)

    override suspend fun close() = delegate.close()
}

/**
 * Memory-bounded CBZ comic/image-archive reader using random-access ZipFile and downsampled BitmapFactory decoding.
 */
class CbzDocumentEngine(private val context: Context) : FixedPageDocumentEngine {

    private val renderMutex = Mutex()
    private var zipFile: ZipFile? = null
    private var tempArchiveFile: File? = null
    private var imageEntries: List<ZipEntry> = emptyList()

    private val aspectRatioCache = ConcurrentHashMap<Int, Float>()
    private var defaultAspectRatio: Float = 0.72f

    @Volatile
    override var pageCount: Int = 0
        private set

    private val bitmapCache: LruCache<PageRenderKey, Bitmap> = createBitmapCache()

    private fun createBitmapCache(): LruCache<PageRenderKey, Bitmap> {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheSizeKb = (maxMemoryKb / 8).coerceIn(20 * 1024, 80 * 1024)
        return object : LruCache<PageRenderKey, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: PageRenderKey, value: Bitmap): Int {
                return (value.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    override suspend fun open(uri: Uri): PdfOpenInfo = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            closeInternal()

            val archiveFile = resolveArchiveFile(uri)
            val openedZip = ZipFile(archiveFile)
            val sortedImages = openedZip.entries()
                .asSequence()
                .filter { entry -> !entry.isDirectory && isSupportedImageEntry(entry.name) }
                .sortedWith(NaturalEntryComparator)
                .toList()

            if (sortedImages.isEmpty()) {
                runCatching { openedZip.close() }
                throw BookParseException("This CBZ archive does not contain any supported comic page images.")
            }

            zipFile = openedZip
            imageEntries = sortedImages
            pageCount = sortedImages.size

            val firstRatio = readEntryAspectRatio(openedZip, sortedImages[0])
            defaultAspectRatio = firstRatio
            aspectRatioCache[0] = firstRatio

            val coverThumb = decodeEntryBitmap(openedZip, sortedImages[0], targetWidthPx = 360)

            PdfOpenInfo(
                pageCount = pageCount,
                defaultAspectRatio = defaultAspectRatio,
                coverThumbnail = coverThumb
            )
        }
    }

    private fun resolveArchiveFile(uri: Uri): File {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let { File(it) }
                ?: throw IOException("Invalid CBZ file URI")
            if (file.exists()) return file
        }
        val cacheDir = File(context.cacheDir, "cbz_stream_cache").apply { mkdirs() }
        val tempFile = File.createTempFile("folio_cbz_", ".cbz", cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: throw IOException("Unable to read CBZ stream")
        tempArchiveFile = tempFile
        return tempFile
    }

    override fun getCachedAspectRatio(pageIndex: Int): Float {
        return aspectRatioCache[pageIndex] ?: defaultAspectRatio
    }

    override suspend fun ensureAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        aspectRatioCache[pageIndex]?.let { return@withContext it }
        coroutineContext.ensureActive()
        renderMutex.withLock {
            aspectRatioCache[pageIndex]?.let { return@withLock it }
            val currentZip = zipFile ?: return@withLock defaultAspectRatio
            val entry = imageEntries.getOrNull(pageIndex) ?: return@withLock defaultAspectRatio
            val ratio = readEntryAspectRatio(currentZip, entry)
            aspectRatioCache[pageIndex] = ratio
            ratio
        }
    }

    override fun getCachedBitmap(pageIndex: Int, bucketWidthPx: Int): Bitmap? {
        return bitmapCache.get(PageRenderKey(pageIndex, bucketWidthPx))
    }

    override fun getAnyCachedBitmapForPage(pageIndex: Int): Bitmap? {
        val snapshot = bitmapCache.snapshot()
        var best: Bitmap? = null
        var bestWidth = 0
        for ((key, bmp) in snapshot) {
            if (key.pageIndex == pageIndex && !bmp.isRecycled && bmp.width > bestWidth) {
                best = bmp
                bestWidth = bmp.width
            }
        }
        return best
    }

    override suspend fun renderPage(pageIndex: Int, bucketWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val safeWidth = bucketWidthPx.coerceIn(320, 2200)
            val key = PageRenderKey(pageIndex, safeWidth)
            bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

            coroutineContext.ensureActive()
            renderMutex.withLock {
                coroutineContext.ensureActive()
                bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withLock it }
                val currentZip = zipFile ?: return@withLock null
                val entry = imageEntries.getOrNull(pageIndex) ?: return@withLock null
                val bmp = decodeEntryBitmap(currentZip, entry, safeWidth) ?: return@withLock null
                val ratio = computeSafeAspectRatio(bmp.width, bmp.height)
                aspectRatioCache[pageIndex] = ratio
                bitmapCache.put(key, bmp)
                bmp
            }
        }

    private fun readEntryAspectRatio(zip: ZipFile, entry: ZipEntry): Float {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
        return computeSafeAspectRatio(opts.outWidth, opts.outHeight)
    }

    private fun decodeEntryBitmap(zip: ZipFile, entry: ZipEntry, targetWidthPx: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth.coerceAtLeast(1)
        val rawH = boundsOpts.outHeight.coerceAtLeast(1)
        var sampleSize = 1
        while (rawW / (sampleSize * 2) >= targetWidthPx && rawH / (sampleSize * 2) >= 240) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return zip.getInputStream(entry).use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }

    private fun computeSafeAspectRatio(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0.72f
        return (width.toFloat() / height.toFloat()).coerceIn(0.25f, 4f)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            closeInternal()
        }
    }

    private fun closeInternal() {
        bitmapCache.evictAll()
        aspectRatioCache.clear()
        imageEntries = emptyList()
        pageCount = 0
        runCatching { zipFile?.close() }
        zipFile = null
        tempArchiveFile?.let { file ->
            runCatching { file.delete() }
        }
        tempArchiveFile = null
    }

    companion object {
        internal fun isSupportedImageEntry(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            if (lower.startsWith("__macosx/") || lower.contains("/__macosx/")) return false
            val fileName = lower.substringAfterLast('/')
            if (fileName.startsWith('.')) return false
            return fileName.endsWith(".jpg") ||
                fileName.endsWith(".jpeg") ||
                fileName.endsWith(".png") ||
                fileName.endsWith(".webp") ||
                fileName.endsWith(".gif")
        }

        internal val NaturalEntryComparator = Comparator<ZipEntry> { a, b ->
            compareNatural(a.name, b.name)
        }

        internal fun compareNatural(left: String, right: String): Int {
            var i = 0
            var j = 0
            while (i < left.length && j < right.length) {
                val c1 = left[i]
                val c2 = right[j]
                if (c1.isDigit() && c2.isDigit()) {
                    var n1End = i
                    while (n1End < left.length && left[n1End].isDigit()) n1End++
                    var n2End = j
                    while (n2End < right.length && right[n2End].isDigit()) n2End++
                    val num1 = left.substring(i, n1End).trimStart('0').ifEmpty { "0" }
                    val num2 = right.substring(j, n2End).trimStart('0').ifEmpty { "0" }
                    if (num1.length != num2.length) return num1.length - num2.length
                    val cmp = num1.compareTo(num2)
                    if (cmp != 0) return cmp
                    i = n1End
                    j = n2End
                } else {
                    val cmp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                    if (cmp != 0) return cmp
                    i++
                    j++
                }
            }
            return left.length - right.length
        }
    }
}
