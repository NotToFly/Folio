package com.example.books

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class BookPage(
    val pageIndex: Int,
    val chapterIndex: Int,
    val chapterTitle: String,
    val firstBlockIndex: Int,
    val lastBlockIndex: Int,
    val items: List<IndexedBlock>
)

/**
 * Deterministic paginator for reflowable books in Page-Turn mode.
 * Estimates vertical dp cost of each block for the active viewport width, height, and font size,
 * splitting oversized paragraphs across pages while keeping the original globalIndex so
 * Scroll <-> Pages toggling and font-size changes preserve the exact reading position.
 */
object ReflowPaginator {

    fun paginate(
        blocks: List<IndexedBlock>,
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        fontSizeSp: Float
    ): List<BookPage> {
        if (blocks.isEmpty()) return emptyList()

        val usableWidthDp = (viewportWidthDp - 44f).coerceAtLeast(180f)
        val usableHeightDp = (viewportHeightDp - 40f).coerceAtLeast(220f)
        val charsPerLine = floor(usableWidthDp / (fontSizeSp * 0.54f)).toInt().coerceAtLeast(20)
        val bodyLineHeightDp = fontSizeSp * 1.58f

        val pages = mutableListOf<BookPage>()
        var currentItems = mutableListOf<IndexedBlock>()
        var usedHeightDp = 0f

        fun flushPage() {
            if (currentItems.isEmpty()) return
            val first = currentItems.first()
            val last = currentItems.last()
            pages.add(
                BookPage(
                    pageIndex = pages.size,
                    chapterIndex = first.chapterIndex,
                    chapterTitle = first.chapterTitle,
                    firstBlockIndex = first.globalIndex,
                    lastBlockIndex = last.globalIndex,
                    items = currentItems.toList()
                )
            )
            currentItems = mutableListOf()
            usedHeightDp = 0f
        }

        for (indexed in blocks) {
            // Start a fresh page on chapter boundary if current page already has content
            if (currentItems.isNotEmpty() && currentItems.first().chapterIndex != indexed.chapterIndex) {
                flushPage()
            }

            val block = indexed.block
            val estimatedHeight = estimateBlockHeightDp(block, charsPerLine, fontSizeSp, bodyLineHeightDp)

            // Split very long paragraphs that would overflow an entire single page
            if (block is BookBlock.Paragraph && estimatedHeight > usableHeightDp * 0.92f) {
                if (currentItems.isNotEmpty() && usedHeightDp > usableHeightDp * 0.65f) {
                    flushPage()
                }
                val maxLinesPerChunk = max(4, floor((usableHeightDp - 24f) / bodyLineHeightDp).toInt())
                val maxCharsPerChunk = max(160, maxLinesPerChunk * charsPerLine)
                val chunks = splitLongText(block.text, maxCharsPerChunk)
                for (chunk in chunks) {
                    val chunkBlock = indexed.copy(block = BookBlock.Paragraph(chunk))
                    val chunkHeight = estimateTextHeightDp(chunk, charsPerLine, bodyLineHeightDp) + 12f
                    if (currentItems.isNotEmpty() && usedHeightDp + chunkHeight > usableHeightDp) {
                        flushPage()
                    }
                    currentItems.add(chunkBlock)
                    usedHeightDp += chunkHeight
                }
                continue
            }

            if (currentItems.isNotEmpty() && usedHeightDp + estimatedHeight > usableHeightDp) {
                flushPage()
            }

            currentItems.add(indexed)
            usedHeightDp += estimatedHeight
        }

        flushPage()
        return pages
    }

    fun findPageForBlock(pages: List<BookPage>, blockIndex: Int): Int {
        if (pages.isEmpty()) return 0
        val clamped = blockIndex.coerceAtLeast(0)
        val exact = pages.indexOfFirst { clamped in it.firstBlockIndex..it.lastBlockIndex }
        if (exact >= 0) return exact
        val nearest = pages.indexOfLast { it.firstBlockIndex <= clamped }
        return if (nearest >= 0) nearest else 0
    }

    private fun estimateBlockHeightDp(
        block: BookBlock,
        charsPerLine: Int,
        fontSizeSp: Float,
        bodyLineHeightDp: Float
    ): Float {
        return when (block) {
            is BookBlock.Heading -> {
                val scale = when (block.level) {
                    1 -> 1.45f
                    2 -> 1.28f
                    else -> 1.15f
                }
                val headingCharsPerLine = max(14, (charsPerLine / scale).toInt())
                estimateTextHeightDp(block.text, headingCharsPerLine, fontSizeSp * scale * 1.32f) + 22f
            }
            is BookBlock.Paragraph -> {
                estimateTextHeightDp(block.text, charsPerLine, bodyLineHeightDp) + 12f
            }
            is BookBlock.Quote -> {
                estimateTextHeightDp(block.text, max(16, charsPerLine - 5), bodyLineHeightDp * 0.96f) + 20f
            }
            is BookBlock.ListItem -> {
                estimateTextHeightDp(block.text, max(16, charsPerLine - 4), bodyLineHeightDp) + 8f
            }
            is BookBlock.TableRow -> {
                val joined = block.cells.joinToString(" | ")
                estimateTextHeightDp(joined, max(16, charsPerLine - 4), bodyLineHeightDp * 0.92f) + 14f
            }
            is BookBlock.Divider -> 24f
            is BookBlock.ImageBlock -> 210f
        }
    }

    private fun estimateTextHeightDp(text: String, charsPerLine: Int, lineHeightDp: Float): Float {
        if (text.isBlank()) return lineHeightDp
        var totalLines = 0
        for (segment in text.split('\n')) {
            totalLines += max(1, ceil(segment.length.toDouble() / charsPerLine.toDouble()).toInt())
        }
        return totalLines * lineHeightDp
    }

    private fun splitLongText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = (start + maxChars).coerceAtMost(text.length)
            if (hardEnd == text.length) {
                result.add(text.substring(start).trim())
                break
            }
            val splitAt = text.lastIndexOf(' ', hardEnd).takeIf { it > start + maxChars / 2 } ?: hardEnd
            result.add(text.substring(start, splitAt).trim())
            start = splitAt
            while (start < text.length && text[start] == ' ') start++
        }
        return result.filter { it.isNotEmpty() }
    }
}
