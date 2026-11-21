package com.example.pdf

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

data class PageRenderKey(
    val pageIndex: Int,
    val bucketWidthPx: Int
)

data class PdfOpenInfo(
    val pageCount: Int,
    val defaultAspectRatio: Float,
    val coverThumbnail: Bitmap?
)

/**
 * High-performance, memory-bounded PDF rendering engine built on Android's native PdfRenderer.
 * Designed for large PDFs:
 * - Zero-copy seekable ParcelFileDescriptor streaming (with automatic temp-file fallback only if non-seekable).
 * - Single Mutex-protected PdfRenderer session.
 * - Memory-bounded LruCache keyed by (pageIndex, resolutionBucket).
 * - Lazy per-page aspect ratio cache to avoid blocking on 1,000+ page documents during open.
 */
class PdfDocumentEngine(private val context: Context) {

    private val renderMutex = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var tempFallbackFile: File? = null

    private val aspectRatioCache = ConcurrentHashMap<Int, Float>()
    private var defaultAspectRatio: Float = 1f / 1.414f
    @Volatile
    var pageCount: Int = 0
        private set

    private val bitmapCache: LruCache<PageRenderKey, Bitmap> = createBitmapCache()

    private fun createBitmapCache(): LruCache<PageRenderKey, Bitmap> {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheSizeKb = (maxMemoryKb / 8).coerceIn(24 * 1024, 96 * 1024)
        return object : LruCache<PageRenderKey, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: PageRenderKey, value: Bitmap): Int {
                return (value.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    suspend fun open(uri: Uri): PdfOpenInfo = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            closeInternal()

            val (pfd, pdfRenderer) = openRendererWithFallback(uri)
            fileDescriptor = pfd
            renderer = pdfRenderer
            pageCount = pdfRenderer.pageCount

            if (pageCount <= 0) {
                throw IOException("PDF document contains no readable pages")
            }

            // Read page 0 dimensions and generate a compact cover thumbnail
            var coverThumb: Bitmap? = null
            pdfRenderer.openPage(0).use { firstPage ->
                val ratio = computeSafeAspectRatio(firstPage.width, firstPage.height)
                defaultAspectRatio = ratio
                aspectRatioCache[0] = ratio
                coverThumb = renderPageToBitmap(firstPage, targetWidthPx = 360)
            }

            PdfOpenInfo(
                pageCount = pageCount,
                defaultAspectRatio = defaultAspectRatio,
                coverThumbnail = coverThumb
            )
        }
    }

    private fun openRendererWithFallback(uri: Uri): Pair<ParcelFileDescriptor, PdfRenderer> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let { File(it) }
                ?: throw IOException("Invalid file URI")
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return pfd to PdfRenderer(pfd)
        }

        val directPfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (se: SecurityException) {
            throw se
        } catch (_: Exception) {
            null
        }

        if (directPfd != null) {
            try {
                return directPfd to PdfRenderer(directPfd)
            } catch (_: IOException) {
                runCatching { directPfd.close() }
            }
        }

        val fallbackPfd = copyToTempCacheFile(uri)
        return fallbackPfd to PdfRenderer(fallbackPfd)
    }

    private fun copyToTempCacheFile(uri: Uri): ParcelFileDescriptor {
        val cacheDir = File(context.cacheDir, "pdf_stream_cache").apply { mkdirs() }
        val tempFile = File.createTempFile("folio_stream_", ".pdf", cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: throw IOException("Unable to read PDF stream")
        tempFallbackFile = tempFile
        return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun getCachedAspectRatio(pageIndex: Int): Float {
        return aspectRatioCache[pageIndex] ?: defaultAspectRatio
    }

    suspend fun ensureAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        aspectRatioCache[pageIndex]?.let { return@withContext it }
        coroutineContext.ensureActive()
        renderMutex.withLock {
            aspectRatioCache[pageIndex]?.let { return@withLock it }
            val currentRenderer = renderer ?: return@withLock defaultAspectRatio
            if (pageIndex !in 0 until currentRenderer.pageCount) return@withLock defaultAspectRatio
            currentRenderer.openPage(pageIndex).use { page ->
                val ratio = computeSafeAspectRatio(page.width, page.height)
                aspectRatioCache[pageIndex] = ratio
                ratio
            }
        }
    }

    fun getCachedBitmap(pageIndex: Int, bucketWidthPx: Int): Bitmap? {
        return bitmapCache.get(PageRenderKey(pageIndex, bucketWidthPx))
    }

    /**
     * Returns any already-cached bitmap for [pageIndex] (e.g., lower-res 1x version while 2.5x renders)
     * so zooming never flashes blank white.
     */
    fun getAnyCachedBitmapForPage(pageIndex: Int): Bitmap? {
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

    suspend fun renderPage(pageIndex: Int, bucketWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val safeWidth = bucketWidthPx.coerceIn(320, 2200)
            val key = PageRenderKey(pageIndex, safeWidth)
            bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

            coroutineContext.ensureActive()

            renderMutex.withLock {
                coroutineContext.ensureActive()
                bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withLock it }

                val currentRenderer = renderer ?: return@withLock null
                if (pageIndex !in 0 until currentRenderer.pageCount) return@withLock null

                currentRenderer.openPage(pageIndex).use { page ->
                    val ratio = computeSafeAspectRatio(page.width, page.height)
                    aspectRatioCache[pageIndex] = ratio
                    val bitmap = renderPageToBitmap(page, safeWidth)
                    bitmapCache.put(key, bitmap)
                    bitmap
                }
            }
        }

    private fun renderPageToBitmap(page: PdfRenderer.Page, targetWidthPx: Int): Bitmap {
        val pageWidth = page.width.coerceAtLeast(1)
        val pageHeight = page.height.coerceAtLeast(1)
        val aspect = pageHeight.toFloat() / pageWidth.toFloat()

        var outWidth = targetWidthPx.coerceIn(240, 2200)
        var outHeight = (outWidth * aspect).toInt().coerceAtLeast(1)

        // Cap extreme tall pages so a single bitmap never triggers OOM
        val maxHeightPx = 3200
        if (outHeight > maxHeightPx) {
            outHeight = maxHeightPx
            outWidth = (outHeight / aspect).toInt().coerceAtLeast(1)
        }

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun computeSafeAspectRatio(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1f / 1.414f
        return (width.toFloat() / height.toFloat()).coerceIn(0.25f, 4f)
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            closeInternal()
        }
    }

    private fun closeInternal() {
        bitmapCache.evictAll()
        aspectRatioCache.clear()
        pageCount = 0
        runCatching { renderer?.close() }
        renderer = null
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
        tempFallbackFile?.let { file ->
            runCatching { file.delete() }
        }
        tempFallbackFile = null
    }
}
