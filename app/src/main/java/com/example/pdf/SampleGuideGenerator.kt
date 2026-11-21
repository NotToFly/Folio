package com.example.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Creates a real, vector-rendered 24-page PDF using Android's PdfDocument API when the user
 * explicitly selects "Try 24-Page Sample Guide" so smooth scrolling, pinch-to-zoom crispness,
 * page jumping, and reading position memory can be verified right away.
 */
object SampleGuideGenerator {

    private const val PAGE_WIDTH = 595 // A4 @ 72dpi
    private const val PAGE_HEIGHT = 842

    suspend fun getOrCreateSampleGuideUri(context: Context): Uri = withContext(Dispatchers.IO) {
        val sampleDir = File(context.filesDir, "samples").apply { mkdirs() }
        val file = File(sampleDir, "Folio_Reader_Guide.pdf")
        if (!file.exists() || file.length() < 4096L) {
            generatePdf(file)
        }
        Uri.fromFile(file)
    }

    private fun generatePdf(outputFile: File) {
        val doc = PdfDocument()
        val totalPages = 24

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1917")
            textSize = 28f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C84B31")
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1917")
            textSize = 18f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E2C28")
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val finePrintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F4B43")
            textSize = 7.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5F5B53")
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C84B31")
            style = Paint.Style.FILL
        }
        val sageFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3D6B5E")
            style = Paint.Style.FILL
        }
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5F1E8")
            style = Paint.Style.FILL
        }
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DDD5C5")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        val chapters = listOf(
            "Architecture for Large PDFs" to "Folio streams pages on demand through a seekable file descriptor without loading the entire document into RAM. Only visible and adjacent pages are rendered into a bounded LRU bitmap cache.",
            "Smooth Pinch-to-Zoom" to "Pinch anywhere with two fingers or double-tap to smoothly zoom up to 450%. As you zoom closer, Folio automatically re-renders visible pages at higher DPI so vector typography stays razor sharp.",
            "Automatic Position Memory" to "Every page turn is persisted locally to SQLite via Room. Close the app, switch documents, or reopen the same PDF later and Folio returns right to your last page.",
            "Editorial Reading Modes" to "Tap once anywhere on a page to reveal or hide the reader controls. Switch between Paper, Warm Sepia, and Night Invert modes without altering your underlying document."
        )

        for (pageIdx in 0 until totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIdx + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            if (pageIdx == 0) {
                drawCoverPage(canvas, subtitlePaint, titlePaint, bodyPaint, accentFill, sageFill, cardFill, rulePaint)
            } else {
                val chapter = chapters[(pageIdx - 1) % chapters.size]
                drawContentPage(
                    canvas = canvas,
                    pageNumber = pageIdx + 1,
                    totalPages = totalPages,
                    chapterTitle = chapter.first,
                    chapterBody = chapter.second,
                    subtitlePaint = subtitlePaint,
                    headingPaint = headingPaint,
                    bodyPaint = bodyPaint,
                    finePrintPaint = finePrintPaint,
                    monoPaint = monoPaint,
                    accentFill = accentFill,
                    sageFill = sageFill,
                    cardFill = cardFill,
                    rulePaint = rulePaint
                )
            }

            doc.finishPage(page)
        }

        FileOutputStream(outputFile).use { stream ->
            doc.writeTo(stream)
        }
        doc.close()
    }

    private fun drawCoverPage(
        canvas: Canvas,
        subtitlePaint: Paint,
        titlePaint: Paint,
        bodyPaint: Paint,
        accentFill: Paint,
        sageFill: Paint,
        cardFill: Paint,
        rulePaint: Paint
    ) {
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 12f, accentFill)
        canvas.drawRoundRect(RectF(48f, 56f, 547f, 360f), 12f, 12f, cardFill)

        canvas.drawText("FOLIO EDITION • SAMPLE GUIDE", 76f, 108f, subtitlePaint)
        canvas.drawText("The Pocket", 76f, 168f, titlePaint)
        canvas.drawText("Archival Reader", 76f, 206f, titlePaint)
        canvas.drawText("24-page vector specimen for testing smooth scrolling,", 76f, 254f, bodyPaint)
        canvas.drawText("high-DPI zoom crispness, and page position memory.", 76f, 274f, bodyPaint)

        // Geometric specimen diagram on cover
        canvas.drawCircle(450f, 220f, 54f, sageFill)
        canvas.drawCircle(478f, 248f, 28f, accentFill)

        canvas.drawLine(48f, 400f, 547f, 400f, rulePaint)
        canvas.drawText("QUICK GESTURES", 48f, 434f, subtitlePaint)

        val tips = listOf(
            "1. Scroll smoothly across all 24 pages or drag the page scrubber.",
            "2. Pinch to zoom in up to 4.5x, or double-tap any paragraph.",
            "3. Pan horizontally & vertically while zoomed, or tap Page # to jump.",
            "4. Leave the document and reopen it — Folio restores your exact page."
        )
        var y = 468f
        for (tip in tips) {
            canvas.drawText(tip, 48f, y, bodyPaint)
            y += 28f
        }

        canvas.drawRoundRect(RectF(48f, 610f, 547f, 760f), 10f, 10f, cardFill)
        canvas.drawRect(48f, 610f, 56f, 760f, accentFill)
        canvas.drawText("OPTICAL ZOOM SPECIMEN", 76f, 646f, subtitlePaint)
        canvas.drawText("Zoom into the fine vector grid on the following pages to inspect", 76f, 676f, bodyPaint)
        canvas.drawText("automatic high-resolution re-rendering at 2x, 3x, and 4x magnification.", 76f, 696f, bodyPaint)
    }

    private fun drawContentPage(
        canvas: Canvas,
        pageNumber: Int,
        totalPages: Int,
        chapterTitle: String,
        chapterBody: String,
        subtitlePaint: Paint,
        headingPaint: Paint,
        bodyPaint: Paint,
        finePrintPaint: Paint,
        monoPaint: Paint,
        accentFill: Paint,
        sageFill: Paint,
        cardFill: Paint,
        rulePaint: Paint
    ) {
        // Header rule
        canvas.drawText("FOLIO SPECIMEN", 48f, 48f, monoPaint)
        canvas.drawText("PAGE $pageNumber OF $totalPages", 448f, 48f, monoPaint)
        canvas.drawLine(48f, 60f, 547f, 60f, rulePaint)

        // Chapter heading
        canvas.drawText("SECTION ${((pageNumber - 1) / 6) + 1}", 48f, 96f, subtitlePaint)
        canvas.drawText(chapterTitle, 48f, 126f, headingPaint)

        // Wrapped body paragraphs
        var y = 162f
        val paragraphs = listOf(
            chapterBody,
            "When viewing large technical manuals, textbooks, or architectural blueprints, consistent frame pacing is critical. Folio isolates PDF rasterization onto a background coroutine dispatcher protected by a strict mutex, ensuring that rapid scrolling never drops UI frames.",
            "Each page reserves its exact layout proportions before pixels finish rasterizing, preventing layout shifts as you flick rapidly through hundreds of pages. Try scrolling rapidly to page 18, zooming in on the calibration matrix below, and pressing Back to verify your reading position is saved."
        )

        for (para in paragraphs) {
            y = drawWrappedText(canvas, para, 48f, y, 499f, 18f, bodyPaint) + 14f
        }

        // Vector Zoom Calibration Box
        val boxTop = (y + 12f).coerceAtMost(500f)
        val boxBottom = boxTop + 210f
        canvas.drawRoundRect(RectF(48f, boxTop, 547f, boxBottom), 8f, 8f, cardFill)
        canvas.drawRoundRect(RectF(48f, boxTop, 547f, boxBottom), 8f, 8f, rulePaint)

        canvas.drawText("ZOOM CALIBRATION TARGET • PAGE $pageNumber", 68f, boxTop + 28f, subtitlePaint)

        // Concentric calibration rings + fine print text to make zoom sharpness obvious
        val cx = 460f
        val cy = boxTop + 112f
        canvas.drawCircle(cx, cy, 52f, rulePaint)
        canvas.drawCircle(cx, cy, 36f, sageFill)
        canvas.drawCircle(cx, cy, 20f, cardFill)
        canvas.drawCircle(cx, cy, 8f, accentFill)

        var fineY = boxTop + 56f
        val fineLines = listOf(
            "6.5pt MONO • PINCH TO ZOOM IN AND INSPECT CRISP VECTOR EDGES",
            "RESOLUTION SCALE: 100% -> 180% -> 280% -> 400% ADAPTIVE TILE",
            "MEMORY BUDGET: BOUNDED LRU BITMAP POOL • ZERO WHOLE-FILE LOAD",
            "INDEX POSITION: STORED IN ROOM SQLITE • PAGE ${pageNumber}/$totalPages",
            "LINE DENSITY CHECK: 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "MICRO-SPECIMEN: Folio keeps typography legible at 4.5x zoom."
        )
        for (line in fineLines) {
            canvas.drawText(line, 68f, fineY, finePrintPaint)
            fineY += 20f
        }

        // Footer
        canvas.drawLine(48f, 788f, 547f, 788f, rulePaint)
        canvas.drawText("Folio PDF Engine Verification Document", 48f, 810f, monoPaint)
        canvas.drawText(String.format("%02d", pageNumber), 530f, 810f, monoPaint)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
        paint: Paint
    ): Float {
        val words = text.split(" ")
        var line = StringBuilder()
        var y = startY
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line.clear()
                line.append(candidate)
            } else {
                canvas.drawText(line.toString(), x, y, paint)
                y += lineHeight
                line.clear()
                line.append(word)
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), x, y, paint)
            y += lineHeight
        }
        return y
    }
}
