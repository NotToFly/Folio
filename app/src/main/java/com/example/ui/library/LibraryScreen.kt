package com.example.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.books.BookFormat
import com.example.data.RecentDocumentEntity
import com.example.ui.components.CompactButton
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatRelativeTime
import com.example.ui.theme.OnyxAccentBlue
import com.example.ui.theme.OnyxBadgeBg
import com.example.ui.theme.OnyxBlack
import com.example.ui.theme.OnyxBorder
import com.example.ui.theme.OnyxCardSurface
import com.example.ui.theme.OnyxElevated
import com.example.ui.theme.OnyxFabBg
import com.example.ui.theme.OnyxFabInk
import com.example.ui.theme.OnyxHairline
import com.example.ui.theme.OnyxSelectedBg
import com.example.ui.theme.OnyxSuccess
import com.example.ui.theme.OnyxTextMuted
import com.example.ui.theme.OnyxTextPrimary
import com.example.ui.theme.OnyxTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onOpenPdfPicker: () -> Unit,
    onOpenDocument: (RecentDocumentEntity) -> Unit,
    onOpenSampleGuide: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onToggleGridView: () -> Unit,
    onFilterSelected: (LibraryFilter) -> Unit,
    onClearFilters: () -> Unit,
    onTogglePin: (RecentDocumentEntity) -> Unit,
    onRemoveDocument: (RecentDocumentEntity) -> Unit,
    onUndoRemove: () -> Unit,
    onRequestClearHistory: () -> Unit,
    onDismissClearHistory: () -> Unit,
    onConfirmClearHistory: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val handleRemoveWithUndo: (RecentDocumentEntity) -> Unit = { doc ->
        onRemoveDocument(doc)
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.document_removed_snackbar, doc.displayName),
                actionLabel = context.getString(R.string.action_undo),
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                onUndoRemove()
            }
        }
    }

    Scaffold(
        containerColor = OnyxBlack,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            )
        },
        floatingActionButton = {
            if (!uiState.isLibraryEmpty) {
                OnyxFab(
                    onClick = onOpenPdfPicker,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("open_pdf_fab")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OnyxBlack)
                .padding(innerPadding)
        ) {
            OnyxLibraryTopBar(
                isSearchExpanded = uiState.isSearchExpanded,
                searchQuery = uiState.searchQuery,
                isGridView = uiState.isGridView,
                hasClearableHistory = uiState.hasClearableHistory,
                isGeneratingSample = uiState.isGeneratingSample,
                onSearchQueryChange = onSearchQueryChange,
                onOpenSearch = onOpenSearch,
                onCloseSearch = onCloseSearch,
                onToggleGridView = onToggleGridView,
                onOpenSampleGuide = onOpenSampleGuide,
                onRequestClearHistory = onRequestClearHistory
            )

            if (!uiState.isLibraryEmpty) {
                OnyxFilterStrip(
                    selectedFilter = uiState.selectedFilter,
                    onFilterSelected = onFilterSelected
                )
                HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                val isWide = maxWidth >= 600.dp

                when {
                    uiState.isLibraryEmpty -> {
                        OnyxEmptyLibraryState(
                            isGeneratingSample = uiState.isGeneratingSample,
                            onOpenPicker = onOpenPdfPicker,
                            onOpenSampleGuide = onOpenSampleGuide
                        )
                    }

                    uiState.isFilterResultEmpty -> {
                        OnyxEmptyFilterState(onClearFilters = onClearFilters)
                    }

                    uiState.isGridView -> {
                        val columns = if (maxWidth >= 840.dp) 4 else if (isWide) 3 else 2
                        OnyxGridContent(
                            uiState = uiState,
                            columns = columns,
                            onOpenDocument = onOpenDocument,
                            onTogglePin = onTogglePin,
                            onRemoveDocument = handleRemoveWithUndo
                        )
                    }

                    else -> {
                        OnyxListContent(
                            uiState = uiState,
                            onOpenDocument = onOpenDocument,
                            onTogglePin = onTogglePin,
                            onRemoveDocument = handleRemoveWithUndo
                        )
                    }
                }
            }
        }
    }

    if (uiState.showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = onDismissClearHistory,
            containerColor = OnyxTopBar,
            titleContentColor = OnyxTextPrimary,
            textContentColor = OnyxTextMuted,
            shape = RoundedCornerShape(6.dp),
            title = {
                Text(
                    text = stringResource(R.string.clear_history_title),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.clear_history_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                CompactButton(
                    text = stringResource(R.string.action_clear),
                    onClick = onConfirmClearHistory,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    visualHeight = 32.dp,
                    modifier = Modifier.testTag("confirm_clear_history_button")
                )
            },
            dismissButton = {
                CompactButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismissClearHistory,
                    containerColor = OnyxBadgeBg,
                    contentColor = OnyxTextPrimary,
                    border = BorderStroke(1.dp, OnyxBorder),
                    visualHeight = 32.dp
                )
            }
        )
    }
}

@Composable
private fun OnyxLibraryTopBar(
    isSearchExpanded: Boolean,
    searchQuery: String,
    isGridView: Boolean,
    hasClearableHistory: Boolean,
    isGeneratingSample: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onToggleGridView: () -> Unit,
    onOpenSampleGuide: () -> Unit,
    onRequestClearHistory: () -> Unit
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Surface(
        color = OnyxTopBar,
        contentColor = OnyxTextPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(52.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearchExpanded) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = OnyxAccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_placeholder),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                color = OnyxTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = OnyxTextPrimary,
                                fontSize = 15.sp
                            ),
                            cursorBrush = SolidColor(OnyxAccentBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag("library_search_input")
                        )
                    }
                    IconButton(
                        onClick = onCloseSearch,
                        modifier = Modifier.testTag("close_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close_search),
                            tint = OnyxTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.library_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = OnyxTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier.testTag("search_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            tint = OnyxTextPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    IconButton(
                        onClick = onToggleGridView,
                        modifier = Modifier.testTag("view_mode_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                            contentDescription = stringResource(R.string.cd_toggle_view),
                            tint = OnyxTextPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            modifier = Modifier.testTag("library_overflow_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.cd_more_options),
                                tint = OnyxTextPrimary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                            containerColor = OnyxElevated
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.action_try_sample),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnyxTextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoStories,
                                        contentDescription = null,
                                        tint = OnyxAccentBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                enabled = !isGeneratingSample,
                                onClick = {
                                    overflowExpanded = false
                                    onOpenSampleGuide()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.action_clear_all_recent),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (hasClearableHistory) MaterialTheme.colorScheme.error else OnyxTextMuted
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = if (hasClearableHistory) MaterialTheme.colorScheme.error else OnyxTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                enabled = hasClearableHistory,
                                onClick = {
                                    overflowExpanded = false
                                    onRequestClearHistory()
                                },
                                modifier = Modifier.testTag("clear_history_menu_item")
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
        }
    }
}

@Composable
private fun OnyxFilterStrip(
    selectedFilter: LibraryFilter,
    onFilterSelected: (LibraryFilter) -> Unit
) {
    val filters = listOf(
        LibraryFilter.ALL to stringResource(R.string.filter_all),
        LibraryFilter.PINNED to stringResource(R.string.filter_pinned),
        LibraryFilter.IN_PROGRESS to stringResource(R.string.filter_in_progress),
        LibraryFilter.FINISHED to stringResource(R.string.filter_finished)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OnyxBlack)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filters.forEach { (filter, label) ->
            val selected = selectedFilter == filter
            Surface(
                color = if (selected) OnyxSelectedBg else OnyxBlack,
                contentColor = if (selected) OnyxAccentBlue else OnyxTextMuted,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) OnyxAccentBlue.copy(alpha = 0.45f) else OnyxHairline
                ),
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onFilterSelected(filter) }
                    .testTag("filter_chip_${filter.name.lowercase()}")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun OnyxListContent(
    uiState: LibraryUiState,
    onOpenDocument: (RecentDocumentEntity) -> Unit,
    onTogglePin: (RecentDocumentEntity) -> Unit,
    onRemoveDocument: (RecentDocumentEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_document_list"),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        if (uiState.bookmarkedDocuments.isNotEmpty()) {
            item(key = "header_bookmarked") {
                OnyxSectionHeader(
                    title = stringResource(R.string.section_bookmarked),
                    count = uiState.bookmarkedDocuments.size
                )
            }
            items(
                items = uiState.bookmarkedDocuments,
                key = { "bm_${it.uriString}" }
            ) { doc ->
                OnyxDenseFileRow(
                    document = doc,
                    onOpen = { onOpenDocument(doc) },
                    onTogglePin = { onTogglePin(doc) },
                    onRemove = { onRemoveDocument(doc) }
                )
            }
        }

        if (uiState.recentDocuments.isNotEmpty()) {
            if (uiState.bookmarkedDocuments.isNotEmpty()) {
                item(key = "header_recent") {
                    OnyxSectionHeader(
                        title = stringResource(R.string.section_recent_files),
                        count = uiState.recentDocuments.size
                    )
                }
            }
            items(
                items = uiState.recentDocuments,
                key = { "rec_${it.uriString}" }
            ) { doc ->
                OnyxDenseFileRow(
                    document = doc,
                    onOpen = { onOpenDocument(doc) },
                    onTogglePin = { onTogglePin(doc) },
                    onRemove = { onRemoveDocument(doc) }
                )
            }
        }
    }
}

@Composable
private fun OnyxGridContent(
    uiState: LibraryUiState,
    columns: Int,
    onOpenDocument: (RecentDocumentEntity) -> Unit,
    onTogglePin: (RecentDocumentEntity) -> Unit,
    onRemoveDocument: (RecentDocumentEntity) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_document_grid"),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 84.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uiState.bookmarkedDocuments.isNotEmpty()) {
            item(
                key = "grid_header_bookmarked",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                OnyxSectionHeader(
                    title = stringResource(R.string.section_bookmarked),
                    count = uiState.bookmarkedDocuments.size,
                    showBottomDivider = false
                )
            }
            items(
                items = uiState.bookmarkedDocuments,
                key = { "gbm_${it.uriString}" }
            ) { doc ->
                OnyxGridFileTile(
                    document = doc,
                    onOpen = { onOpenDocument(doc) },
                    onTogglePin = { onTogglePin(doc) },
                    onRemove = { onRemoveDocument(doc) }
                )
            }
        }

        if (uiState.recentDocuments.isNotEmpty()) {
            if (uiState.bookmarkedDocuments.isNotEmpty()) {
                item(
                    key = "grid_header_recent",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    OnyxSectionHeader(
                        title = stringResource(R.string.section_recent_files),
                        count = uiState.recentDocuments.size,
                        showBottomDivider = false
                    )
                }
            }
            items(
                items = uiState.recentDocuments,
                key = { "grec_${it.uriString}" }
            ) { doc ->
                OnyxGridFileTile(
                    document = doc,
                    onOpen = { onOpenDocument(doc) },
                    onTogglePin = { onTogglePin(doc) },
                    onRemove = { onRemoveDocument(doc) }
                )
            }
        }
    }
}

@Composable
private fun OnyxSectionHeader(
    title: String,
    count: Int,
    showBottomDivider: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OnyxBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = OnyxTextMuted
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = OnyxTextMuted
            )
        }
        if (showBottomDivider) {
            HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
        }
    }
}

@Composable
private fun OnyxDenseFileRow(
    document: RecentDocumentEntity,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OnyxBlack)
            .clickable(onClick = onOpen)
            .testTag("document_card_${document.displayName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OnyxFileBadge(
                format = document.bookFormat,
                thumbnailPath = document.thumbnailPath,
                title = document.displayName,
                sizeDp = 40
            )

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp
                    ),
                    color = OnyxTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = buildMetadataSubtitle(document),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    ),
                    color = OnyxTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(2.dp)
                        .background(OnyxHairline)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(document.progressFraction.coerceIn(0.02f, 1f))
                            .background(if (document.isFinished) OnyxSuccess else OnyxAccentBlue)
                    )
                }
            }

            if (document.isPinned) {
                Icon(
                    imageVector = Icons.Rounded.Bookmark,
                    contentDescription = stringResource(R.string.filter_pinned),
                    tint = OnyxAccentBlue,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(14.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("doc_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = OnyxTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DocumentRowDropdownMenu(
                    expanded = menuExpanded,
                    isPinned = document.isPinned,
                    onDismiss = { menuExpanded = false },
                    onTogglePin = {
                        menuExpanded = false
                        onTogglePin()
                    },
                    onRemove = {
                        menuExpanded = false
                        onRemove()
                    }
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = OnyxHairline)
    }
}

@Composable
private fun OnyxGridFileTile(
    document: RecentDocumentEntity,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = OnyxCardSurface,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, OnyxHairline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onOpen)
            .testTag("document_card_${document.displayName}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .background(OnyxBadgeBg),
                contentAlignment = Alignment.Center
            ) {
                // Top accent line echoing Onyx badge style
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(0.32f)
                        .height(2.dp)
                        .background(OnyxAccentBlue)
                )

                val thumb = rememberThumbnailBitmap(document.thumbnailPath)
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_document_cover, document.displayName),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = formatIcon(document.bookFormat),
                            contentDescription = null,
                            tint = OnyxAccentBlue,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = document.bookFormat.badge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = OnyxTextMuted
                        )
                    }
                }

                // Format pill overlay
                Surface(
                    color = OnyxBlack.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = document.bookFormat.badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = OnyxAccentBlue,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                if (document.isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.Bookmark,
                        contentDescription = stringResource(R.string.filter_pinned),
                        tint = OnyxAccentBlue,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = document.displayName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = OnyxTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.cd_more_options),
                                tint = OnyxTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DocumentRowDropdownMenu(
                            expanded = menuExpanded,
                            isPinned = document.isPinned,
                            onDismiss = { menuExpanded = false },
                            onTogglePin = {
                                menuExpanded = false
                                onTogglePin()
                            },
                            onRemove = {
                                menuExpanded = false
                                onRemove()
                            }
                        )
                    }
                }
                Text(
                    text = "${document.progressPercent}% · ${formatRelativeTime(document.lastOpenedAt)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = OnyxTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OnyxFileBadge(
    format: BookFormat,
    thumbnailPath: String?,
    title: String,
    sizeDp: Int
) {
    val thumb = rememberThumbnailBitmap(thumbnailPath)

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(OnyxBadgeBg)
            .border(1.dp, OnyxHairline, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_document_cover, title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(OnyxBlack.copy(alpha = 0.72f))
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = format.badge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 9.sp
                    ),
                    color = OnyxAccentBlue,
                    maxLines = 1
                )
            }
        } else {
            // Subtle top highlight accent bar inspired by Onyx Files icon squares
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.45f)
                    .height(2.dp)
                    .background(OnyxFabBg.copy(alpha = 0.75f))
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = formatIcon(format),
                    contentDescription = null,
                    tint = OnyxAccentBlue,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = format.badge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 10.sp
                    ),
                    color = OnyxTextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DocumentRowDropdownMenu(
    expanded: Boolean,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = OnyxElevated
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnyxTextPrimary
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (isPinned) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = OnyxAccentBlue,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onTogglePin
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.action_remove_recent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onRemove
        )
    }
}

@Composable
private fun OnyxFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = OnyxFabBg,
        contentColor = OnyxFabInk,
        shape = RoundedCornerShape(4.dp),
        shadowElevation = 4.dp,
        modifier = modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.NoteAdd,
                contentDescription = stringResource(R.string.action_open_file),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun OnyxEmptyLibraryState(
    isGeneratingSample: Boolean,
    onOpenPicker: () -> Unit,
    onOpenSampleGuide: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = OnyxCardSurface,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, OnyxHairline),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(OnyxBadgeBg)
                        .border(1.dp, OnyxBorder, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = OnyxAccentBlue,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnyxTextPrimary
                )

                Text(
                    text = stringResource(R.string.empty_body),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = OnyxTextMuted
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    CompactButton(
                        text = stringResource(R.string.action_open_file),
                        icon = Icons.Rounded.NoteAdd,
                        onClick = onOpenPicker,
                        containerColor = OnyxFabBg,
                        contentColor = OnyxFabInk,
                        visualHeight = 36.dp,
                        modifier = Modifier.testTag("empty_open_pdf_button")
                    )

                    CompactButton(
                        text = stringResource(R.string.action_try_sample),
                        icon = Icons.Rounded.AutoStories,
                        loading = isGeneratingSample,
                        onClick = onOpenSampleGuide,
                        containerColor = OnyxBadgeBg,
                        contentColor = OnyxAccentBlue,
                        border = BorderStroke(1.dp, OnyxBorder),
                        visualHeight = 36.dp,
                        modifier = Modifier.testTag("try_sample_guide_button")
                    )
                }
            }
        }
    }
}

@Composable
private fun OnyxEmptyFilterState(
    onClearFilters: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.empty_filtered_title),
                style = MaterialTheme.typography.titleMedium,
                color = OnyxTextPrimary
            )
            Text(
                text = stringResource(R.string.empty_filtered_body),
                style = MaterialTheme.typography.bodySmall,
                color = OnyxTextMuted
            )
            CompactButton(
                text = stringResource(R.string.action_clear_filters),
                onClick = onClearFilters,
                containerColor = OnyxBadgeBg,
                contentColor = OnyxAccentBlue,
                border = BorderStroke(1.dp, OnyxBorder),
                visualHeight = 34.dp,
                modifier = Modifier.testTag("clear_filters_button")
            )
        }
    }
}

private fun buildMetadataSubtitle(doc: RecentDocumentEntity): String {
    val parts = mutableListOf<String>()
    parts.add(doc.bookFormat.badge)
    if (doc.bookFormat.isFixedLayout && doc.totalPages > 0) {
        parts.add("p. ${doc.lastPage + 1}/${doc.totalPages}")
    } else {
        parts.add("${doc.progressPercent}%")
    }
    val size = formatFileSize(doc.fileSizeBytes)
    if (size.isNotEmpty()) parts.add(size)
    val rel = formatRelativeTime(doc.lastOpenedAt)
    if (rel.isNotEmpty()) parts.add(rel)
    return parts.joinToString(" · ")
}

private fun formatIcon(format: BookFormat): ImageVector = when (format) {
    BookFormat.PDF -> Icons.Rounded.PictureAsPdf
    BookFormat.CBZ -> Icons.Rounded.PhotoLibrary
    BookFormat.EPUB, BookFormat.FB2, BookFormat.MOBI -> Icons.AutoMirrored.Rounded.MenuBook
    BookFormat.DOCX, BookFormat.DOC, BookFormat.RTF -> Icons.Rounded.Description
    BookFormat.HTML, BookFormat.MARKDOWN -> Icons.Rounded.Code
    BookFormat.TXT -> Icons.AutoMirrored.Rounded.Article
}

@Composable
private fun rememberThumbnailBitmap(path: String?): Bitmap? {
    val state by produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (file.exists()) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                } else {
                    null
                }
            }
        }
    }
    return state
}
