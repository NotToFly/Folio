package com.example.books

import android.graphics.Bitmap

sealed interface BookBlock {
    data class Heading(val level: Int, val text: String) : BookBlock
    data class Paragraph(val text: String) : BookBlock
    data class Quote(val text: String) : BookBlock
    data class ListItem(val bullet: String, val text: String) : BookBlock
    data class TableRow(val cells: List<String>) : BookBlock
    data object Divider : BookBlock
    data class ImageBlock(val imageKey: String, val caption: String? = null) : BookBlock
}

data class BookChapter(
    val index: Int,
    val title: String,
    val blocks: List<BookBlock>,
    val startGlobalBlockIndex: Int
)

data class IndexedBlock(
    val globalIndex: Int,
    val chapterIndex: Int,
    val chapterTitle: String,
    val block: BookBlock
)

data class ReflowableBook(
    val title: String,
    val author: String?,
    val format: BookFormat,
    val coverBitmap: Bitmap? = null,
    val chapters: List<BookChapter>,
    val images: Map<String, ByteArray> = emptyMap()
) {
    val flattenedBlocks: List<IndexedBlock> = buildList {
        var globalIdx = 0
        for (chapter in chapters) {
            for (block in chapter.blocks) {
                add(
                    IndexedBlock(
                        globalIndex = globalIdx++,
                        chapterIndex = chapter.index,
                        chapterTitle = chapter.title,
                        block = block
                    )
                )
            }
        }
    }

    val totalBlocks: Int
        get() = flattenedBlocks.size.coerceAtLeast(1)
}

class BookParseException(
    message: String,
    val isDrmProtected: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

internal object BookBuilder {
    fun buildBook(
        title: String,
        author: String?,
        format: BookFormat,
        coverBitmap: Bitmap? = null,
        rawChapters: List<Pair<String, List<BookBlock>>>,
        images: Map<String, ByteArray> = emptyMap()
    ): ReflowableBook {
        val nonEmpty = rawChapters.filter { it.second.isNotEmpty() }
        val effective = if (nonEmpty.isEmpty()) {
            listOf(title.ifBlank { "Document" } to listOf(BookBlock.Paragraph("(This document contains no readable text.)")))
        } else {
            nonEmpty
        }

        var cursor = 0
        val chapters = effective.mapIndexed { idx, (chapterTitle, blocks) ->
            val cleanTitle = chapterTitle.trim().ifBlank { "Section ${idx + 1}" }
            val chapter = BookChapter(
                index = idx,
                title = cleanTitle,
                blocks = blocks,
                startGlobalBlockIndex = cursor
            )
            cursor += blocks.size
            chapter
        }

        return ReflowableBook(
            title = title.trim().ifBlank { "Untitled Document" },
            author = author?.trim()?.takeIf { it.isNotBlank() },
            format = format,
            coverBitmap = coverBitmap,
            chapters = chapters,
            images = images
        )
    }

    /**
     * Splits a flat sequence of blocks into chapters whenever a level 1 or 2 heading appears,
     * or falls back to even sections when a long document has no explicit headings.
     */
    fun splitBlocksIntoChapters(
        blocks: List<BookBlock>,
        fallbackTitle: String
    ): List<Pair<String, List<BookBlock>>> {
        if (blocks.isEmpty()) return emptyList()

        val headingSplit = mutableListOf<Pair<String, MutableList<BookBlock>>>()
        var currentTitle = fallbackTitle.ifBlank { "Start" }
        var currentBlocks = mutableListOf<BookBlock>()

        for (block in blocks) {
            if (block is BookBlock.Heading && block.level <= 2 && currentBlocks.size >= 2) {
                headingSplit.add(currentTitle to currentBlocks)
                currentTitle = block.text.take(80)
                currentBlocks = mutableListOf(block)
            } else {
                if (block is BookBlock.Heading && currentBlocks.isEmpty()) {
                    currentTitle = block.text.take(80)
                }
                currentBlocks.add(block)
            }
        }
        if (currentBlocks.isNotEmpty()) {
            headingSplit.add(currentTitle to currentBlocks)
        }

        // If no heading split occurred and the document is long, chunk into navigable sections
        if (headingSplit.size == 1 && blocks.size > 120) {
            val chunkSize = 80
            return blocks.chunked(chunkSize).mapIndexed { index, chunk ->
                val title = if (index == 0) fallbackTitle.ifBlank { "Part 1" } else "Part ${index + 1}"
                title to chunk
            }
        }

        return headingSplit.map { it.first to it.second.toList() }
    }
}
