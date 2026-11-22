package com.example.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.books.BookBlock
import com.example.books.BookChapter
import com.example.books.IndexedBlock
import com.example.books.ReflowPaginator
import com.example.books.ReflowableBook
import com.example.ui.components.CompactIconButton
import com.example.ui.components.CompactPillButton
import com.example.ui.theme.BookNightBg
import com.example.ui.theme.BookNightInk
import com.example.ui.theme.BookNightMuted
import com.example.ui.theme.BookPaperBg
import com.example.ui.theme.BookPaperInk
import com.example.ui.theme.BookPaperMuted
import com.example.ui.theme.BookSepiaBg
import com.example.ui.theme.BookSepiaInk
import com.example.ui.theme.BookSepiaMuted
import com.example.ui.theme.NewsreaderFontFamily
import com.example.ui.theme.OnyxAccentBlue
import com.example.ui.theme.OnyxBadgeBg
import com.example.ui.theme.OnyxBorder
import com.example.ui.theme.OnyxHairline
import com.example.ui.theme.OnyxSelectedBg
import com.example.ui.theme.OnyxTextMuted
import com.example.ui.theme.OnyxTextPrimary
import com.example.ui.theme.OnyxTopBar
import com.example.ui.theme.PlusJakartaSansFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class ReflowPalette(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val divider: Color,
    val quoteBar: Color,
    val quoteBg: Color
)

private fun ReadingMode.toReflowPalette(): ReflowPalette = when (this) {
    ReadingMode.NIGHT -> ReflowPalette(
        background = BookNightBg,
        ink = BookNightInk,
        muted = BookNightMuted,
        divider = OnyxHairline,
        quoteBar = OnyxAccentBlue,
        quoteBg = OnyxBadgeBg.copy(alpha = 0.65f)
    )
    ReadingMode.SEPIA -> ReflowPalette(
        background = BookSepiaBg,
        ink = BookSepiaInk,
        muted = BookSepiaMuted,
        divider = BookSepiaMuted.copy(alpha = 0.25f),
        quoteBar = Color(0xFF8C6B43),
        quoteBg = Color(0xFFE9DCC2)
    )
    ReadingMode.PAPER -> ReflowPalette(
        background = BookPaperBg,
        ink = BookPaperInk,
        muted = BookPaperMuted,
        divider = Color(0xFFE0DDD5),
        quoteBar = Color(0xFF476A8A),
        quoteBg = Color(0xFFF1EEE8)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReflowableReaderViewport(
    book: ReflowableBook,
    initialBlockIndex: Int,
    uiState: ReaderUiState,
    hudVisible: Boolean,
    onToggleHud: () -> Unit,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onCycleReadingMode: () -> Unit,
    onToggleLayoutMode: () -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onToggleFontFamily: () -> Unit,
    onPositionChanged: (blockIndex: Int, totalBlocks: Int) -> Unit
) {
    val palette = uiState.readingMode.toReflowPalette()
    val fontFamily = if (uiState.useSerifFont) NewsreaderFontFamily else PlusJakartaSansFontFamily
    val coroutineScope = rememberCoroutineScope()
    var showTocSheet by remember { mutableStateOf(false) }

    val blocks = remember(book) { book.flattenedBlocks }
    val totalBlocks = blocks.size.coerceAtLeast(1)

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialBlockIndex.coerceIn(0, totalBlocks - 1)
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        val pageWidthDp = maxWidth.value.coerceAtMost(680f)
        val pageHeightDp = (maxHeight.value - 96f).coerceAtLeast(240f)

        val pages = remember(book, pageWidthDp, pageHeightDp, uiState.fontSizeSp) {
            ReflowPaginator.paginate(
                blocks = blocks,
                viewportWidthDp = pageWidthDp,
                viewportHeightDp = pageHeightDp,
                fontSizeSp = uiState.fontSizeSp
            )
        }

        val initialPageIdx = remember(pages, initialBlockIndex) {
            ReflowPaginator.findPageForBlock(pages, initialBlockIndex)
        }
        val pagerState = rememberPagerState(
            initialPage = initialPageIdx.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            pageCount = { pages.size.coerceAtLeast(1) }
        )

        // Anchor reading position when switching Scroll <-> Paginated or changing font size
        LaunchedEffect(uiState.reflowLayoutMode, pages) {
            val anchor = uiState.currentBlockIndex.coerceIn(0, totalBlocks - 1)
            if (uiState.reflowLayoutMode == ReflowLayoutMode.PAGINATED) {
                val targetPage = ReflowPaginator.findPageForBlock(pages, anchor)
                if (targetPage in pages.indices && pagerState.currentPage != targetPage) {
                    pagerState.scrollToPage(targetPage)
                }
            } else {
                if (listState.firstVisibleItemIndex != anchor) {
                    listState.scrollToItem(anchor)
                }
            }
        }

        // Report scroll position in SCROLL mode
        LaunchedEffect(uiState.reflowLayoutMode, listState, totalBlocks) {
            if (uiState.reflowLayoutMode == ReflowLayoutMode.SCROLL) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect { idx ->
                        onPositionChanged(idx.coerceIn(0, totalBlocks - 1), totalBlocks)
                    }
            }
        }

        // Report page position in PAGINATED mode
        LaunchedEffect(uiState.reflowLayoutMode, pagerState, pages) {
            if (uiState.reflowLayoutMode == ReflowLayoutMode.PAGINATED) {
                snapshotFlow { pagerState.currentPage }
                    .distinctUntilChanged()
                    .collect { pageIdx ->
                        val page = pages.getOrNull(pageIdx)
                        if (page != null) {
                            onPositionChanged(page.firstBlockIndex, totalBlocks)
                        }
                    }
            }
        }

        val activeChapterIndex by remember(blocks, uiState.currentBlockIndex) {
            derivedStateOf {
                blocks.getOrNull(uiState.currentBlockIndex)?.chapterIndex ?: 0
            }
        }

        if (uiState.reflowLayoutMode == ReflowLayoutMode.SCROLL) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 84.dp,
                    bottom = 148.dp,
                    start = 20.dp,
                    end = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onToggleHud() })
                    }
                    .testTag("reflow_scroll_list")
            ) {
                items(
                    items = blocks,
                    key = { it.globalIndex }
                ) { indexed ->
                    Box(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                        ReflowBlockItem(
                            block = indexed.block,
                            book = book,
                            fontSizeSp = uiState.fontSizeSp,
                            fontFamily = fontFamily,
                            palette = palette
                        )
                    }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pagerState, pages.size) {
                        detectTapGestures(
                            onTap = { tap ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val ratio = tap.x / width
                                when {
                                    ratio < 0.25f && pagerState.currentPage > 0 -> {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    }
                                    ratio > 0.75f && pagerState.currentPage < pages.lastIndex -> {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                    else -> onToggleHud()
                                }
                            }
                        )
                    }
                    .testTag("reflow_horizontal_pager")
            ) { pageIndex ->
                val page = pages.getOrNull(pageIndex)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (page != null) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 680.dp)
                                .fillMaxSize()
                        ) {
                            // Subtle running chapter header
                            Text(
                                text = page.chapterTitle,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                page.items.forEach { indexed ->
                                    ReflowBlockItem(
                                        block = indexed.block,
                                        book = book,
                                        fontSizeSp = uiState.fontSizeSp,
                                        fontFamily = fontFamily,
                                        palette = palette
                                    )
                                }
                            }

                            // Subtle bottom page footer
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = book.format.badge,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = palette.muted
                                )
                                Text(
                                    text = "${pageIndex + 1} / ${pages.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = palette.muted
                                )
                            }
                        }
                    }
                }
            }
        }

        // Onyx Top Bar
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReflowTopBar(
                title = uiState.documentTitle,
                formatBadge = book.format.badge,
                readingMode = uiState.readingMode,
                isBookmarked = uiState.isBookmarked,
                onBack = onBack,
                onToggleBookmark = onToggleBookmark,
                onCycleReadingMode = onCycleReadingMode,
                onOpenToc = { showTocSheet = true }
            )
        }

        // Onyx Bottom Typography & Navigation Dock
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            var scrubValue by remember(uiState.reflowLayoutMode, pagerState.currentPage, uiState.currentBlockIndex) {
                mutableFloatStateOf(
                    if (uiState.reflowLayoutMode == ReflowLayoutMode.PAGINATED) {
                        pagerState.currentPage.toFloat()
                    } else {
                        uiState.currentBlockIndex.toFloat()
                    }
                )
            }
            val maxSlider = if (uiState.reflowLayoutMode == ReflowLayoutMode.PAGINATED) {
                (pages.size - 1).coerceAtLeast(0).toFloat()
            } else {
                (totalBlocks - 1).coerceAtLeast(0).toFloat()
            }

            ReflowBottomDock(
                layoutMode = uiState.reflowLayoutMode,
                fontSizeSp = uiState.fontSizeSp,
                useSerifFont = uiState.useSerifFont,
                progressLabel = if (uiState.reflowLayoutMode == ReflowLayoutMode.PAGINATED) {
                    "${pagerState.currentPage + 1} / ${pages.size.coerceAtLeast(1)}  (${uiState.reflowProgressPercent}%)"
                } else {
                    "${uiState.reflowProgressPercent}%"
                },
                sliderValue = scrubValue,
                sliderMax = maxSlider,
                onSliderChange = { value ->
                    scrubValue = value
                    val target = value.roundToInt()
                    coroutineScope.launch {
                        if (uiState.reflowLayoutMode == ReflowLayoutMode.PAGINATED) {
                            val clampedPage = target.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                            pagerState.scrollToPage(clampedPage)
                        } else {
                            val clampedBlock = target.coerceIn(0, totalBlocks - 1)
                            listState.scrollToItem(clampedBlock)
                        }
                    }
                },
                onToggleLayoutMode = onToggleLayoutMode,
                onDecreaseFont = { onAdjustFontSize(-1f) },
                onIncreaseFont = { onAdjustFontSize(1f) },
                onToggleFontFamily = onToggleFontFamily,
                onOpenToc = { showTocSheet = true }
            )
        }

        if (showTocSheet) {
            ModalBottomSheet(
                onDismissRequest = { showTocSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = OnyxTopBar,
                contentColor = OnyxTextPrimary,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            ) {
                TableOfContentsSheetContent(
                    chapters = book.chapters,
                    activeChapterIndex = activeChapterIndex,
                    onSelectChapter = { chapter ->
                        showTocSheet = false
                        val targetBlock = chapter.startGlobalBlockIndex.coerceIn(0, totalBlocks - 1)
                        onPositionChanged(targetBlock, totalBlocks)
                        coroutineScope.launch {
                            if (uiState.reflowLayoutMode == ReflowLayoutMode.PAGINATED) {
                                val targetPage = ReflowPaginator.findPageForBlock(pages, targetBlock)
                                pagerState.scrollToPage(targetPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                            } else {
                                listState.scrollToItem(targetBlock)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ReflowBlockItem(
    block: BookBlock,
    book: ReflowableBook,
    fontSizeSp: Float,
    fontFamily: FontFamily,
    palette: ReflowPalette
) {
    when (block) {
        is BookBlock.Heading -> {
            val scale = when (block.level) {
                1 -> 1.42f
                2 -> 1.24f
                3 -> 1.12f
                else -> 1.04f
            }
            Text(
                text = block.text,
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (fontSizeSp * scale).sp,
                lineHeight = (fontSizeSp * scale * 1.28f).sp,
                color = palette.ink,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
        }

        is BookBlock.Paragraph -> {
            Text(
                text = block.text,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.58f).sp,
                color = palette.ink
            )
        }

        is BookBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.quoteBg)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .background(palette.quoteBar)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = block.text,
                    fontFamily = fontFamily,
                    fontStyle = FontStyle.Italic,
                    fontSize = (fontSizeSp * 0.94f).sp,
                    lineHeight = (fontSizeSp * 1.48f).sp,
                    color = palette.ink.copy(alpha = 0.9f),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is BookBlock.ListItem -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = block.bullet,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.55f).sp,
                    color = palette.quoteBar,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = block.text,
                    fontFamily = fontFamily,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.55f).sp,
                    color = palette.ink,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is BookBlock.TableRow -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.divider, RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                block.cells.forEach { cell ->
                    Text(
                        text = cell,
                        fontFamily = fontFamily,
                        fontSize = (fontSizeSp * 0.88f).sp,
                        lineHeight = (fontSizeSp * 1.32f).sp,
                        color = palette.ink,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        is BookBlock.Divider -> {
            HorizontalDivider(
                thickness = 1.dp,
                color = palette.divider,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        is BookBlock.ImageBlock -> {
            val imageBytes = book.images[block.imageKey]
            val decodedBitmap by produceState<Bitmap?>(initialValue = null, key1 = block.imageKey) {
                value = if (imageBytes == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
                            var sample = 1
                            while (opts.outWidth / (sample * 2) >= 1200) sample *= 2
                            BitmapFactory.decodeByteArray(
                                imageBytes,
                                0,
                                imageBytes.size,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                }
                            )
                        }.getOrNull()
                    }
                }
            }

            if (decodedBitmap != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = decodedBitmap!!.asImageBitmap(),
                        contentDescription = block.caption ?: "Book illustration",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                    )
                    if (!block.caption.isNullOrBlank()) {
                        Text(
                            text = block.caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReflowTopBar(
    title: String,
    formatBadge: String,
    readingMode: ReadingMode,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onCycleReadingMode: () -> Unit,
    onOpenToc: () -> Unit
) {
    Surface(
        color = OnyxTopBar.copy(alpha = 0.96f),
        contentColor = OnyxTextPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CompactIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back_to_library),
                    onClick = onBack,
                    containerColor = OnyxBadgeBg,
                    contentColor = OnyxTextPrimary,
                    border = BorderStroke(1.dp, OnyxHairline),
                    visualSize = 32.dp,
                    iconSize = 17.dp,
                    modifier = Modifier.testTag("reader_back_button")
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        color = OnyxTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = OnyxAccentBlue
                    )
                }

                CompactIconButton(
                    icon = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = stringResource(
                        if (isBookmarked) R.string.action_unpin else R.string.action_pin
                    ),
                    onClick = onToggleBookmark,
                    containerColor = if (isBookmarked) OnyxSelectedBg else OnyxBadgeBg,
                    contentColor = if (isBookmarked) OnyxAccentBlue else OnyxTextMuted,
                    border = BorderStroke(1.dp, OnyxHairline),
                    visualSize = 30.dp,
                    iconSize = 16.dp,
                    modifier = Modifier.testTag("reader_bookmark_button")
                )

                CompactPillButton(
                    text = stringResource(
                        when (readingMode) {
                            ReadingMode.NIGHT -> R.string.reading_mode_night
                            ReadingMode.SEPIA -> R.string.reading_mode_sepia
                            ReadingMode.PAPER -> R.string.reading_mode_paper
                        }
                    ),
                    icon = Icons.Rounded.Brightness4,
                    iconContentDescription = stringResource(R.string.cd_reading_mode),
                    onClick = onCycleReadingMode,
                    visualHeight = 30.dp,
                    horizontalPadding = 8.dp,
                    modifier = Modifier.testTag("reading_mode_button")
                )

                CompactIconButton(
                    icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                    contentDescription = stringResource(R.string.cd_table_of_contents),
                    onClick = onOpenToc,
                    containerColor = OnyxBadgeBg,
                    contentColor = OnyxTextPrimary,
                    border = BorderStroke(1.dp, OnyxHairline),
                    visualSize = 30.dp,
                    iconSize = 16.dp,
                    modifier = Modifier.testTag("toc_button")
                )
            }
            HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
        }
    }
}

@Composable
private fun ReflowBottomDock(
    layoutMode: ReflowLayoutMode,
    fontSizeSp: Float,
    useSerifFont: Boolean,
    progressLabel: String,
    sliderValue: Float,
    sliderMax: Float,
    onSliderChange: (Float) -> Unit,
    onToggleLayoutMode: () -> Unit,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit,
    onToggleFontFamily: () -> Unit,
    onOpenToc: () -> Unit
) {
    Surface(
        color = OnyxTopBar.copy(alpha = 0.97f),
        contentColor = OnyxTextPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (sliderMax > 0f) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Slider(
                            value = sliderValue.coerceIn(0f, sliderMax),
                            onValueChange = onSliderChange,
                            valueRange = 0f..sliderMax,
                            colors = SliderDefaults.colors(
                                thumbColor = OnyxAccentBlue,
                                activeTrackColor = OnyxAccentBlue,
                                inactiveTrackColor = OnyxBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .testTag("reflow_progress_slider")
                        )
                        Text(
                            text = progressLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = OnyxTextMuted,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dual reading mode toggle: Scroll vs Pages
                    CompactPillButton(
                        text = stringResource(
                            if (layoutMode == ReflowLayoutMode.SCROLL) {
                                R.string.layout_mode_scroll
                            } else {
                                R.string.layout_mode_pages
                            }
                        ),
                        icon = if (layoutMode == ReflowLayoutMode.SCROLL) {
                            Icons.Rounded.SwapVert
                        } else {
                            Icons.AutoMirrored.Rounded.MenuBook
                        },
                        iconContentDescription = stringResource(R.string.cd_layout_mode),
                        selected = layoutMode == ReflowLayoutMode.PAGINATED,
                        onClick = onToggleLayoutMode,
                        visualHeight = 30.dp,
                        horizontalPadding = 8.dp,
                        modifier = Modifier.testTag("reflow_layout_toggle")
                    )

                    // Font size controls (A- / 17sp / A+)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        CompactIconButton(
                            icon = Icons.Rounded.TextDecrease,
                            contentDescription = stringResource(R.string.cd_font_smaller),
                            onClick = onDecreaseFont,
                            enabled = fontSizeSp > 12f,
                            containerColor = OnyxBadgeBg,
                            contentColor = OnyxTextPrimary,
                            border = BorderStroke(1.dp, OnyxHairline),
                            visualSize = 30.dp,
                            iconSize = 16.dp,
                            modifier = Modifier.testTag("font_smaller_button")
                        )
                        Text(
                            text = stringResource(R.string.font_size_format, fontSizeSp.roundToInt()),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = OnyxTextPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        CompactIconButton(
                            icon = Icons.Rounded.TextIncrease,
                            contentDescription = stringResource(R.string.cd_font_larger),
                            onClick = onIncreaseFont,
                            enabled = fontSizeSp < 30f,
                            containerColor = OnyxBadgeBg,
                            contentColor = OnyxTextPrimary,
                            border = BorderStroke(1.dp, OnyxHairline),
                            visualSize = 30.dp,
                            iconSize = 16.dp,
                            modifier = Modifier.testTag("font_larger_button")
                        )
                    }

                    // Serif / Sans toggle
                    CompactPillButton(
                        text = stringResource(
                            if (useSerifFont) R.string.font_family_serif else R.string.font_family_sans
                        ),
                        onClick = onToggleFontFamily,
                        selected = useSerifFont,
                        visualHeight = 30.dp,
                        horizontalPadding = 9.dp,
                        modifier = Modifier.testTag("font_family_toggle")
                    )
                }
            }
        }
    }
}

@Composable
private fun TableOfContentsSheetContent(
    chapters: List<BookChapter>,
    activeChapterIndex: Int,
    onSelectChapter: (BookChapter) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.toc_title),
            style = MaterialTheme.typography.titleMedium,
            color = OnyxTextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        HorizontalDivider(thickness = 1.dp, color = OnyxHairline)

        if (chapters.isEmpty()) {
            Text(
                text = stringResource(R.string.toc_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = OnyxTextMuted,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .testTag("toc_chapter_list")
            ) {
                items(
                    items = chapters,
                    key = { it.index }
                ) { chapter ->
                    val isActive = chapter.index == activeChapterIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isActive) OnyxSelectedBg else Color.Transparent)
                            .clickable { onSelectChapter(chapter) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${chapter.index + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) OnyxAccentBlue else OnyxTextMuted
                            )
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = if (isActive) OnyxAccentBlue else OnyxTextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
                    }
                }
            }
        }
    }
}
