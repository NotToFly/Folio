package com.example.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.books.BookFormat
import com.example.ui.components.CompactButton
import com.example.ui.components.CompactIconButton
import com.example.ui.components.CompactPillButton
import com.example.ui.theme.OnyxAccentBlue
import com.example.ui.theme.OnyxBadgeBg
import com.example.ui.theme.OnyxBorder
import com.example.ui.theme.OnyxCardSurface
import com.example.ui.theme.OnyxFabBg
import com.example.ui.theme.OnyxFabInk
import com.example.ui.theme.OnyxHairline
import com.example.ui.theme.OnyxSelectedBg
import com.example.ui.theme.OnyxTextMuted
import com.example.ui.theme.OnyxTextPrimary
import com.example.ui.theme.OnyxTopBar
import com.example.ui.theme.ReaderMatDark
import com.example.ui.theme.ReaderMatLight
import com.example.ui.theme.ReaderMatSepia
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenAnotherFile: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var hudVisible by remember { mutableStateOf(true) }
    var showJumpDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.persistPositionImmediately(scale)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.persistPositionImmediately(scale)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler {
        viewModel.persistPositionImmediately(scale)
        onBack()
    }

    val canvasBackground = when (uiState.readingMode) {
        ReadingMode.PAPER -> ReaderMatLight
        ReadingMode.SEPIA -> ReaderMatSepia
        ReadingMode.NIGHT -> ReaderMatDark
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(canvasBackground)
    ) {
        when (val loadState = uiState.loadState) {
            is ReaderLoadState.Loading -> {
                LoadingDocumentOverlay(
                    title = uiState.documentTitle,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is ReaderLoadState.Error -> {
                ReaderErrorCard(
                    message = loadState.message,
                    onBack = onBack,
                    onOpenAnotherFile = onOpenAnotherFile,
                    onRemoveEntry = { viewModel.removeCurrentFromRecent(onBack) },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }

            is ReaderLoadState.ReadyFixed -> {
                FixedDocumentViewport(
                    format = loadState.format,
                    totalPages = loadState.totalPages,
                    initialPage = loadState.initialPage,
                    readingMode = uiState.readingMode,
                    viewModel = viewModel,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    onTransformChange = { newScale, newX, newY ->
                        scale = newScale
                        offsetX = newX
                        offsetY = newY
                    },
                    onToggleHud = { hudVisible = !hudVisible },
                    onCurrentPageChanged = { page ->
                        viewModel.onVisiblePageChanged(page, scale)
                    },
                    hudVisible = hudVisible,
                    uiState = uiState,
                    onBack = {
                        viewModel.persistPositionImmediately(scale)
                        onBack()
                    },
                    onShowJumpDialog = { showJumpDialog = true },
                    showJumpDialog = showJumpDialog,
                    onDismissJumpDialog = { showJumpDialog = false }
                )
            }

            is ReaderLoadState.ReadyReflowable -> {
                ReflowableReaderViewport(
                    book = loadState.book,
                    initialBlockIndex = loadState.initialBlockIndex,
                    uiState = uiState,
                    hudVisible = hudVisible,
                    onToggleHud = { hudVisible = !hudVisible },
                    onBack = {
                        viewModel.persistPositionImmediately()
                        onBack()
                    },
                    onToggleBookmark = { viewModel.toggleBookmark() },
                    onCycleReadingMode = { viewModel.cycleReadingMode() },
                    onToggleLayoutMode = { viewModel.toggleReflowLayoutMode() },
                    onAdjustFontSize = { delta -> viewModel.adjustFontSize(delta) },
                    onToggleFontFamily = { viewModel.toggleFontFamily() },
                    onPositionChanged = { blockIdx, totalBlocks ->
                        viewModel.onReflowPositionChanged(blockIdx, totalBlocks)
                    }
                )
            }
        }

        // Non-intrusive "Resumed" banner
        AnimatedVisibility(
            visible = uiState.showResumedBanner,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 64.dp)
        ) {
            Surface(
                color = OnyxSelectedBg,
                contentColor = OnyxAccentBlue,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, OnyxAccentBlue.copy(alpha = 0.4f)),
                shadowElevation = 4.dp,
                modifier = Modifier.testTag("resumed_page_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BookmarkAdded,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (uiState.loadState is ReaderLoadState.ReadyReflowable) {
                            stringResource(R.string.resumed_percent_toast, uiState.reflowProgressPercent)
                        } else {
                            stringResource(
                                R.string.resumed_page_toast,
                                uiState.currentPage + 1,
                                uiState.totalPages
                            )
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun FixedDocumentViewport(
    format: BookFormat,
    totalPages: Int,
    initialPage: Int,
    readingMode: ReadingMode,
    viewModel: ReaderViewModel,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransformChange: (Float, Float, Float) -> Unit,
    onToggleHud: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    hudVisible: Boolean,
    uiState: ReaderUiState,
    onBack: () -> Unit,
    onShowJumpDialog: () -> Unit,
    showJumpDialog: Boolean,
    onDismissJumpDialog: () -> Unit
) {
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPagePreview by remember { mutableFloatStateOf(initialPage.toFloat()) }

    val currentScale by rememberUpdatedState(scale)
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOffsetY by rememberUpdatedState(offsetY)
    val currentViewportSize by rememberUpdatedState(viewportSize)

    LaunchedEffect(uiState.uriString, initialPage) {
        if (initialPage in 0 until totalPages && lazyListState.firstVisibleItemIndex != initialPage) {
            lazyListState.scrollToItem(initialPage)
        }
    }

    val visiblePageIndex by remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                lazyListState.firstVisibleItemIndex
            } else {
                val viewportMid = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visibleItems.minByOrNull { item ->
                    val itemMid = item.offset + (item.size / 2)
                    abs(itemMid - viewportMid)
                }?.index ?: lazyListState.firstVisibleItemIndex
            }
        }
    }

    LaunchedEffect(visiblePageIndex) {
        onCurrentPageChanged(visiblePageIndex)
        if (!isScrubbing) {
            scrubPagePreview = visiblePageIndex.toFloat()
        }
    }

    fun animateZoomTo(targetScale: Float, targetX: Float, targetY: Float) {
        animJob?.cancel()
        animJob = coroutineScope.launch {
            val startScale = currentScale
            val startX = currentOffsetX
            val startY = currentOffsetY
            val progress = Animatable(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) {
                val t = value
                val s = startScale + (targetScale - startScale) * t
                val x = startX + (targetX - startX) * t
                val y = startY + (targetY - startY) * t
                onTransformChange(s, x, y)
            }
        }
    }

    fun zoomByStep(delta: Float) {
        val w = viewportSize.width.toFloat().coerceAtLeast(1f)
        val h = viewportSize.height.toFloat().coerceAtLeast(1f)
        val targetScale = (currentScale + delta).coerceIn(PdfZoomMath.MIN_SCALE, PdfZoomMath.MAX_SCALE)
        if (targetScale <= PdfZoomMath.ZOOM_EPSILON) {
            animateZoomTo(1f, 0f, 0f)
        } else {
            val ratio = targetScale / currentScale
            val nextX = PdfZoomMath.clampOffset(currentOffsetX * ratio, w, targetScale)
            val nextY = PdfZoomMath.clampOffset(currentOffsetY * ratio, h, targetScale)
            animateZoomTo(targetScale, nextX, nextY)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageContainerMaxWidth = minOf(maxWidth, 880.dp)
        val baseWidthPx = with(density) { (pageContainerMaxWidth - 20.dp).toPx().roundToInt() }
            .coerceIn(480, 1440)
        val renderBucketWidthPx = PdfZoomMath.computeRenderBucketWidth(baseWidthPx, scale)

        LaunchedEffect(visiblePageIndex, renderBucketWidthPx, totalPages) {
            for (offset in listOf(1, -1, 2)) {
                val target = visiblePageIndex + offset
                if (target in 0 until totalPages) {
                    launch {
                        viewModel.renderPage(target, renderBucketWidthPx)
                    }
                }
            }
        }

        // Preserve original full-color comic artwork in CBZ; apply tint/invert on PDFs
        val colorFilter = if (format == BookFormat.CBZ) null else readingMode.toColorFilter()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onToggleHud() },
                        onDoubleTap = { tapOffset ->
                            val w = currentViewportSize.width.toFloat().coerceAtLeast(1f)
                            val h = currentViewportSize.height.toFloat().coerceAtLeast(1f)
                            val target = PdfZoomMath.computeDoubleTapTarget(
                                currentScale = currentScale,
                                currentOffsetX = currentOffsetX,
                                currentOffsetY = currentOffsetY,
                                tapOffset = tapOffset,
                                viewportWidth = w,
                                viewportHeight = h
                            )
                            animateZoomTo(target.scale, target.offsetX, target.offsetY)
                        }
                    )
                }
                .pointerInput(lazyListState) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var isZoomedPanActive = false
                        val velocityTracker = VelocityTracker()
                        var totalPanDistance = 0f

                        do {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val pressedCount = event.changes.count { it.pressed }
                            val w = currentViewportSize.width.toFloat().coerceAtLeast(1f)
                            val h = currentViewportSize.height.toFloat().coerceAtLeast(1f)

                            if (pressedCount >= 2) {
                                animJob?.cancel()
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)

                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val updated = PdfZoomMath.computePinchTransform(
                                        currentScale = currentScale,
                                        currentOffsetX = currentOffsetX,
                                        currentOffsetY = currentOffsetY,
                                        zoomFactor = zoomChange,
                                        pan = panChange,
                                        centroid = centroid,
                                        viewportWidth = w,
                                        viewportHeight = h
                                    )
                                    onTransformChange(updated.scale, updated.offsetX, updated.offsetY)
                                    if (abs(updated.verticalOverflowPx) > 0.5f) {
                                        coroutineScope.launch {
                                            lazyListState.scrollBy(-updated.verticalOverflowPx / updated.scale)
                                        }
                                    }
                                }
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                                isZoomedPanActive = true
                            } else if (pressedCount == 1 && currentScale > PdfZoomMath.ZOOM_EPSILON) {
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null) {
                                    val delta = change.position - change.previousPosition
                                    totalPanDistance += delta.getDistance()
                                    if (!isZoomedPanActive && totalPanDistance > touchSlop) {
                                        animJob?.cancel()
                                        isZoomedPanActive = true
                                    }
                                    if (isZoomedPanActive) {
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        val desiredX = currentOffsetX + delta.x
                                        val desiredY = currentOffsetY + delta.y
                                        val clampedX = PdfZoomMath.clampOffset(desiredX, w, currentScale)
                                        val clampedY = PdfZoomMath.clampOffset(desiredY, h, currentScale)
                                        val overflowY = desiredY - clampedY

                                        onTransformChange(currentScale, clampedX, clampedY)

                                        if (abs(overflowY) > 0.5f) {
                                            coroutineScope.launch {
                                                lazyListState.scrollBy(-overflowY / currentScale)
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (isZoomedPanActive && currentScale > PdfZoomMath.ZOOM_EPSILON) {
                            val velocity = velocityTracker.calculateVelocity()
                            val speed = Offset(velocity.x, velocity.y).getDistance()
                            if (speed > 120f) {
                                animJob?.cancel()
                                animJob = coroutineScope.launch {
                                    flingZoomedViewport(
                                        initialVelocity = Offset(velocity.x, velocity.y),
                                        getScale = { currentScale },
                                        getOffset = { Offset(currentOffsetX, currentOffsetY) },
                                        viewportSize = {
                                            val sz = currentViewportSize
                                            Offset(sz.width.toFloat(), sz.height.toFloat())
                                        },
                                        onUpdate = { x, y ->
                                            onTransformChange(currentScale, x, y)
                                        },
                                        onVerticalOverflow = { overflowY ->
                                            lazyListState.scrollBy(-overflowY / currentScale)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
        ) {
            LazyColumn(
                state = lazyListState,
                userScrollEnabled = scale <= PdfZoomMath.ZOOM_EPSILON,
                contentPadding = PaddingValues(
                    top = 84.dp,
                    bottom = 132.dp,
                    start = 10.dp,
                    end = 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .testTag("pdf_pages_list")
            ) {
                items(
                    count = totalPages,
                    key = { it }
                ) { pageIndex ->
                    PdfPageCard(
                        pageIndex = pageIndex,
                        totalPages = totalPages,
                        renderBucketWidthPx = renderBucketWidthPx,
                        readingMode = readingMode,
                        colorFilter = colorFilter,
                        viewModel = viewModel,
                        modifier = Modifier.widthIn(max = pageContainerMaxWidth)
                    )
                }
            }
        }

        // Top Reader Bar
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                title = uiState.documentTitle,
                formatBadge = format.badge,
                readingMode = readingMode,
                isBookmarked = uiState.isBookmarked,
                onBack = onBack,
                onToggleBookmark = { viewModel.toggleBookmark() },
                onCycleMode = { viewModel.cycleReadingMode() },
                onJumpToPage = onShowJumpDialog
            )
        }

        // Floating Scrub Preview Pill
        AnimatedVisibility(
            visible = isScrubbing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 112.dp)
        ) {
            Surface(
                color = OnyxSelectedBg,
                contentColor = OnyxAccentBlue,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, OnyxAccentBlue.copy(alpha = 0.4f)),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = stringResource(
                        R.string.page_progress_format,
                        scrubPagePreview.roundToInt().coerceIn(0, totalPages - 1) + 1,
                        totalPages
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // Bottom Reader Dock
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomDock(
                currentPage = if (isScrubbing) scrubPagePreview.roundToInt() else visiblePageIndex,
                totalPages = totalPages,
                scale = scale,
                onScrubChange = { targetPageFloat ->
                    isScrubbing = true
                    scrubPagePreview = targetPageFloat
                    val targetIdx = targetPageFloat.roundToInt().coerceIn(0, totalPages - 1)
                    coroutineScope.launch {
                        lazyListState.scrollToItem(targetIdx)
                    }
                },
                onScrubFinished = {
                    val targetIdx = scrubPagePreview.roundToInt().coerceIn(0, totalPages - 1)
                    isScrubbing = false
                    coroutineScope.launch {
                        lazyListState.scrollToItem(targetIdx)
                    }
                },
                onPageBadgeClick = onShowJumpDialog,
                onZoomOut = { zoomByStep(-0.5f) },
                onResetZoom = { animateZoomTo(1f, 0f, 0f) },
                onZoomIn = { zoomByStep(0.5f) }
            )
        }
    }

    if (showJumpDialog) {
        JumpToPageDialog(
            currentPageOneBased = visiblePageIndex + 1,
            totalPages = totalPages,
            onDismiss = onDismissJumpDialog,
            onConfirmPage = { oneBasedPage ->
                onDismissJumpDialog()
                coroutineScope.launch {
                    lazyListState.scrollToItem((oneBasedPage - 1).coerceIn(0, totalPages - 1))
                }
            }
        )
    }
}

private suspend fun flingZoomedViewport(
    initialVelocity: Offset,
    getScale: () -> Float,
    getOffset: () -> Offset,
    viewportSize: () -> Offset,
    onUpdate: (Float, Float) -> Unit,
    onVerticalOverflow: suspend (Float) -> Unit
) = coroutineScope {
    var previousValue = Offset.Zero
    val state = AnimationState(
        typeConverter = Offset.VectorConverter,
        initialValue = Offset.Zero,
        initialVelocity = initialVelocity
    )
    state.animateDecay(exponentialDecay(frictionMultiplier = 2.2f)) {
        val delta = value - previousValue
        previousValue = value
        val scale = getScale()
        if (scale <= PdfZoomMath.ZOOM_EPSILON) {
            cancelAnimation()
            return@animateDecay
        }
        val size = viewportSize()
        val current = getOffset()
        val desiredX = current.x + delta.x
        val desiredY = current.y + delta.y
        val clampedX = PdfZoomMath.clampOffset(desiredX, size.x, scale)
        val clampedY = PdfZoomMath.clampOffset(desiredY, size.y, scale)
        val overflowY = desiredY - clampedY

        onUpdate(clampedX, clampedY)

        if (abs(overflowY) > 0.5f) {
            launch {
                onVerticalOverflow(overflowY)
            }
        }
    }
}

@Composable
private fun PdfPageCard(
    pageIndex: Int,
    totalPages: Int,
    renderBucketWidthPx: Int,
    readingMode: ReadingMode,
    colorFilter: ColorFilter?,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    var aspectRatio by remember(pageIndex) {
        mutableFloatStateOf(viewModel.getCachedAspectRatio(pageIndex))
    }
    var bitmap by remember(pageIndex) {
        mutableStateOf(viewModel.getBestCachedBitmap(pageIndex, renderBucketWidthPx))
    }

    LaunchedEffect(pageIndex) {
        aspectRatio = viewModel.ensureAspectRatio(pageIndex)
    }

    LaunchedEffect(pageIndex, renderBucketWidthPx) {
        val exact = viewModel.getExactCachedBitmap(pageIndex, renderBucketWidthPx)
        if (exact != null) {
            bitmap = exact
        } else {
            val rendered = viewModel.renderPage(pageIndex, renderBucketWidthPx)
            if (rendered != null) {
                bitmap = rendered
            }
        }
    }

    val paperColor = when (readingMode) {
        ReadingMode.PAPER -> Color.White
        ReadingMode.SEPIA -> Color(0xFFF5ECD8)
        ReadingMode.NIGHT -> Color(0xFF14161A)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(3.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(paperColor),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_pdf_page, pageIndex + 1),
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = OnyxAccentBlue
                )
                Text(
                    text = "${pageIndex + 1} / $totalPages",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnyxTextMuted
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    formatBadge: String,
    readingMode: ReadingMode,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onCycleMode: () -> Unit,
    onJumpToPage: () -> Unit
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
                            ReadingMode.PAPER -> R.string.reading_mode_paper
                            ReadingMode.SEPIA -> R.string.reading_mode_sepia
                            ReadingMode.NIGHT -> R.string.reading_mode_night
                        }
                    ),
                    icon = Icons.Rounded.Brightness4,
                    iconContentDescription = stringResource(R.string.cd_reading_mode),
                    onClick = onCycleMode,
                    visualHeight = 30.dp,
                    horizontalPadding = 8.dp,
                    modifier = Modifier.testTag("reading_mode_button")
                )

                CompactIconButton(
                    icon = Icons.Rounded.Numbers,
                    contentDescription = stringResource(R.string.cd_jump_to_page),
                    onClick = onJumpToPage,
                    containerColor = OnyxBadgeBg,
                    contentColor = OnyxTextPrimary,
                    border = BorderStroke(1.dp, OnyxHairline),
                    visualSize = 30.dp,
                    iconSize = 16.dp,
                    modifier = Modifier.testTag("jump_to_page_icon_button")
                )
            }
            HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
        }
    }
}

@Composable
private fun ReaderBottomDock(
    currentPage: Int,
    totalPages: Int,
    scale: Float,
    onScrubChange: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onPageBadgeClick: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onZoomIn: () -> Unit
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
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (totalPages > 1) {
                    Slider(
                        value = currentPage.toFloat().coerceIn(0f, (totalPages - 1).toFloat()),
                        onValueChange = onScrubChange,
                        onValueChangeFinished = onScrubFinished,
                        valueRange = 0f..(totalPages - 1).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = OnyxAccentBlue,
                            activeTrackColor = OnyxAccentBlue,
                            inactiveTrackColor = OnyxBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .testTag("page_scrubber_slider")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactPillButton(
                        text = "${currentPage + 1} / $totalPages",
                        onClick = onPageBadgeClick,
                        visualHeight = 28.dp,
                        horizontalPadding = 10.dp,
                        modifier = Modifier.testTag("page_indicator_button")
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        CompactIconButton(
                            icon = Icons.Rounded.Remove,
                            contentDescription = stringResource(R.string.cd_zoom_out),
                            onClick = onZoomOut,
                            enabled = scale > PdfZoomMath.MIN_SCALE + 0.02f,
                            containerColor = OnyxBadgeBg,
                            contentColor = OnyxTextPrimary,
                            border = BorderStroke(1.dp, OnyxHairline),
                            visualSize = 28.dp,
                            iconSize = 15.dp,
                            modifier = Modifier.testTag("zoom_out_button")
                        )

                        CompactPillButton(
                            text = stringResource(
                                R.string.zoom_percent_format,
                                (scale * 100f).roundToInt()
                            ),
                            selected = scale > PdfZoomMath.ZOOM_EPSILON,
                            onClick = onResetZoom,
                            visualHeight = 28.dp,
                            horizontalPadding = 8.dp,
                            modifier = Modifier.testTag("zoom_reset_button")
                        )

                        CompactIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.cd_zoom_in),
                            onClick = onZoomIn,
                            enabled = scale < PdfZoomMath.MAX_SCALE - 0.05f,
                            containerColor = OnyxBadgeBg,
                            contentColor = OnyxTextPrimary,
                            border = BorderStroke(1.dp, OnyxHairline),
                            visualSize = 28.dp,
                            iconSize = 15.dp,
                            modifier = Modifier.testTag("zoom_in_button")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpToPageDialog(
    currentPageOneBased: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onConfirmPage: (Int) -> Unit
) {
    var input by remember { mutableStateOf(currentPageOneBased.toString()) }
    val parsed = input.trim().toIntOrNull()
    val isValid = parsed != null && parsed in 1..totalPages

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OnyxTopBar,
        titleContentColor = OnyxTextPrimary,
        textContentColor = OnyxTextMuted,
        shape = RoundedCornerShape(6.dp),
        title = {
            Text(
                text = stringResource(R.string.goto_page_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all { it.isDigit() }) {
                            input = value
                        }
                    },
                    label = { Text(stringResource(R.string.goto_page_hint, totalPages)) },
                    isError = input.isNotEmpty() && !isValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    keyboardActions = KeyboardActions(
                        onDone = { if (isValid && parsed != null) onConfirmPage(parsed) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("goto_page_input")
                )
                if (input.isNotEmpty() && !isValid) {
                    Text(
                        text = stringResource(R.string.goto_page_error, totalPages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            CompactButton(
                text = stringResource(R.string.action_go),
                onClick = { if (parsed != null) onConfirmPage(parsed) },
                enabled = isValid,
                containerColor = OnyxFabBg,
                contentColor = OnyxFabInk,
                visualHeight = 32.dp,
                modifier = Modifier.testTag("confirm_goto_page_button")
            )
        },
        dismissButton = {
            CompactButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                containerColor = OnyxBadgeBg,
                contentColor = OnyxTextPrimary,
                border = BorderStroke(1.dp, OnyxBorder),
                visualHeight = 32.dp
            )
        }
    )
}

@Composable
private fun LoadingDocumentOverlay(
    title: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = OnyxCardSurface,
        border = BorderStroke(1.dp, OnyxHairline),
        modifier = modifier.padding(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.4.dp,
                color = OnyxAccentBlue
            )
            Text(
                text = stringResource(R.string.loading_document),
                style = MaterialTheme.typography.titleSmall,
                color = OnyxTextPrimary
            )
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnyxTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ReaderErrorCard(
    message: String,
    onBack: () -> Unit,
    onOpenAnotherFile: () -> Unit,
    onRemoveEntry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = OnyxCardSurface,
        border = BorderStroke(1.dp, OnyxBorder),
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = stringResource(R.string.reader_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = OnyxTextPrimary
            )
            Text(
                text = message.ifBlank { stringResource(R.string.reader_error_permission) },
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = OnyxTextMuted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                CompactButton(
                    text = stringResource(R.string.action_relocate_file),
                    icon = Icons.Rounded.FolderOpen,
                    onClick = onOpenAnotherFile,
                    containerColor = OnyxFabBg,
                    contentColor = OnyxFabInk,
                    visualHeight = 34.dp,
                    modifier = Modifier.testTag("error_open_another_button")
                )
                CompactButton(
                    text = stringResource(R.string.action_remove_missing),
                    onClick = onRemoveEntry,
                    containerColor = OnyxBadgeBg,
                    contentColor = MaterialTheme.colorScheme.error,
                    border = BorderStroke(1.dp, OnyxBorder),
                    visualHeight = 34.dp,
                    modifier = Modifier.testTag("error_remove_entry_button")
                )
            }
            CompactButton(
                text = stringResource(R.string.cd_back_to_library),
                onClick = onBack,
                containerColor = OnyxTopBar,
                contentColor = OnyxTextMuted,
                border = BorderStroke(1.dp, OnyxHairline),
                visualHeight = 32.dp
            )
        }
    }
}
