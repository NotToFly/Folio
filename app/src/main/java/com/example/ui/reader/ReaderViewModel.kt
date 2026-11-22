package com.example.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.books.BookFormat
import com.example.books.BookLoader
import com.example.books.BookParseException
import com.example.books.FixedPageDocumentEngine
import com.example.books.LoadedBook
import com.example.books.ReflowableBook
import com.example.data.DocumentRepository
import com.example.data.FolioDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReflowLayoutMode {
    SCROLL,
    PAGINATED
}

sealed interface ReaderLoadState {
    data object Loading : ReaderLoadState

    data class ReadyFixed(
        val format: BookFormat,
        val totalPages: Int,
        val initialPage: Int,
        val wasResumed: Boolean
    ) : ReaderLoadState

    data class ReadyReflowable(
        val book: ReflowableBook,
        val initialBlockIndex: Int,
        val wasResumed: Boolean
    ) : ReaderLoadState

    data class Error(
        val message: String,
        val isPermissionOrMissing: Boolean
    ) : ReaderLoadState
}

data class ReaderUiState(
    val uriString: String = "",
    val documentTitle: String = "",
    val format: BookFormat = BookFormat.PDF,
    val loadState: ReaderLoadState = ReaderLoadState.Loading,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val currentBlockIndex: Int = 0,
    val totalBlocks: Int = 0,
    val readingMode: ReadingMode = ReadingMode.NIGHT,
    val reflowLayoutMode: ReflowLayoutMode = ReflowLayoutMode.SCROLL,
    val fontSizeSp: Float = 17f,
    val useSerifFont: Boolean = true,
    val showResumedBanner: Boolean = false,
    val isBookmarked: Boolean = false
) {
    val reflowProgressPercent: Int
        get() {
            if (totalBlocks <= 1) return if (totalBlocks == 1) 100 else 0
            return (((currentBlockIndex + 1).toFloat() / totalBlocks.toFloat()) * 100f)
                .toInt()
                .coerceIn(0, 100)
        }
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DocumentRepository(
        context = application.applicationContext,
        dao = FolioDatabase.getInstance(application).recentDocumentDao()
    )

    private val bookLoader = BookLoader(application.applicationContext)
    private var fixedEngine: FixedPageDocumentEngine? = null

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var savePositionJob: Job? = null
    private var openJob: Job? = null
    private var activeUriString: String? = null
    private var lastSavedIndex: Int = -1
    private var lastZoomValue: Float = 1f

    fun openDocument(uriString: String) {
        if (uriString.isBlank()) return
        val currentState = _uiState.value.loadState
        if (activeUriString == uriString &&
            (currentState is ReaderLoadState.ReadyFixed || currentState is ReaderLoadState.ReadyReflowable)
        ) {
            return
        }

        openJob?.cancel()
        activeUriString = uriString
        lastSavedIndex = -1

        openJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    uriString = uriString,
                    loadState = ReaderLoadState.Loading,
                    showResumedBanner = false
                )
            }

            try {
                fixedEngine?.close()
                fixedEngine = null

                val uri = Uri.parse(uriString)
                repository.takePersistableReadPermission(uri)

                val existingRecord = repository.getDocumentByUri(uriString)
                val meta = repository.queryMetadata(uri)
                val mimeType = repository.queryMimeType(uri)
                _uiState.update { it.copy(documentTitle = meta.displayName) }

                when (val loaded = bookLoader.load(uri, meta.displayName, mimeType)) {
                    is LoadedBook.Fixed -> {
                        fixedEngine = loaded.engine
                        val savedEntity = repository.recordDocumentOpened(
                            uri = uri,
                            format = loaded.format,
                            totalPages = loaded.openInfo.pageCount,
                            thumbnailBitmap = loaded.openInfo.coverThumbnail
                        )

                        val initialPage = (existingRecord?.lastPage ?: savedEntity.lastPage)
                            .coerceIn(0, (loaded.openInfo.pageCount - 1).coerceAtLeast(0))
                        val wasResumed = initialPage > 0
                        lastSavedIndex = initialPage

                        _uiState.update {
                            it.copy(
                                documentTitle = savedEntity.displayName,
                                format = loaded.format,
                                totalPages = loaded.openInfo.pageCount,
                                currentPage = initialPage,
                                isBookmarked = savedEntity.isPinned,
                                loadState = ReaderLoadState.ReadyFixed(
                                    format = loaded.format,
                                    totalPages = loaded.openInfo.pageCount,
                                    initialPage = initialPage,
                                    wasResumed = wasResumed
                                ),
                                showResumedBanner = wasResumed
                            )
                        }
                        scheduleResumedBannerDismiss(wasResumed)
                    }

                    is LoadedBook.Reflowable -> {
                        val book = loaded.book
                        val totalBlocks = book.totalBlocks
                        val savedEntity = repository.recordDocumentOpened(
                            uri = uri,
                            format = book.format,
                            totalPages = totalBlocks,
                            thumbnailBitmap = book.coverBitmap,
                            preferredTitle = book.title
                        )

                        val initialBlock = (existingRecord?.lastCharOffset ?: savedEntity.lastCharOffset)
                            .coerceIn(0, (totalBlocks - 1).coerceAtLeast(0))
                        val wasResumed = initialBlock > 0
                        lastSavedIndex = initialBlock

                        _uiState.update {
                            it.copy(
                                documentTitle = book.title.ifBlank { savedEntity.displayName },
                                format = book.format,
                                currentBlockIndex = initialBlock,
                                totalBlocks = totalBlocks,
                                isBookmarked = savedEntity.isPinned,
                                loadState = ReaderLoadState.ReadyReflowable(
                                    book = book,
                                    initialBlockIndex = initialBlock,
                                    wasResumed = wasResumed
                                ),
                                showResumedBanner = wasResumed
                            )
                        }
                        scheduleResumedBannerDismiss(wasResumed)
                    }
                }
            } catch (se: SecurityException) {
                _uiState.update {
                    it.copy(
                        loadState = ReaderLoadState.Error(
                            message = se.localizedMessage ?: "Permission denied for this file.",
                            isPermissionOrMissing = true
                        )
                    )
                }
            } catch (pe: BookParseException) {
                _uiState.update {
                    it.copy(
                        loadState = ReaderLoadState.Error(
                            message = pe.message ?: "Unable to parse this e-book file.",
                            isPermissionOrMissing = false
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadState = ReaderLoadState.Error(
                            message = e.localizedMessage ?: "Unable to read this file.",
                            isPermissionOrMissing = true
                        )
                    )
                }
            }
        }
    }

    private fun scheduleResumedBannerDismiss(wasResumed: Boolean) {
        if (!wasResumed) return
        viewModelScope.launch {
            delay(2600L)
            _uiState.update { it.copy(showResumedBanner = false) }
        }
    }

    fun onVisiblePageChanged(pageIndex: Int, currentZoom: Float) {
        val total = _uiState.value.totalPages
        if (total <= 0) return
        val clamped = pageIndex.coerceIn(0, total - 1)
        lastZoomValue = currentZoom

        if (_uiState.value.currentPage != clamped) {
            _uiState.update { it.copy(currentPage = clamped) }
        }

        val uri = activeUriString ?: return
        if (clamped == lastSavedIndex) return

        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch {
            delay(250L)
            lastSavedIndex = clamped
            repository.updateReadingPosition(
                uriString = uri,
                lastPage = clamped,
                totalPages = total,
                lastZoom = lastZoomValue,
                lastCharOffset = clamped
            )
        }
    }

    fun onReflowPositionChanged(blockIndex: Int, totalBlocks: Int) {
        if (totalBlocks <= 0) return
        val clamped = blockIndex.coerceIn(0, totalBlocks - 1)

        if (_uiState.value.currentBlockIndex != clamped || _uiState.value.totalBlocks != totalBlocks) {
            _uiState.update {
                it.copy(
                    currentBlockIndex = clamped,
                    totalBlocks = totalBlocks
                )
            }
        }

        val uri = activeUriString ?: return
        if (clamped == lastSavedIndex) return

        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch {
            delay(300L)
            lastSavedIndex = clamped
            repository.updateReadingPosition(
                uriString = uri,
                lastPage = clamped,
                totalPages = totalBlocks,
                lastZoom = 1f,
                lastCharOffset = clamped
            )
        }
    }

    fun persistPositionImmediately(currentZoom: Float = lastZoomValue) {
        val uri = activeUriString ?: return
        val state = _uiState.value

        when (state.loadState) {
            is ReaderLoadState.ReadyFixed -> {
                if (state.totalPages <= 0) return
                savePositionJob?.cancel()
                val pageToSave = state.currentPage.coerceIn(0, state.totalPages - 1)
                lastSavedIndex = pageToSave
                viewModelScope.launch {
                    repository.updateReadingPosition(
                        uriString = uri,
                        lastPage = pageToSave,
                        totalPages = state.totalPages,
                        lastZoom = currentZoom,
                        lastCharOffset = pageToSave
                    )
                }
            }

            is ReaderLoadState.ReadyReflowable -> {
                if (state.totalBlocks <= 0) return
                savePositionJob?.cancel()
                val blockToSave = state.currentBlockIndex.coerceIn(0, state.totalBlocks - 1)
                lastSavedIndex = blockToSave
                viewModelScope.launch {
                    repository.updateReadingPosition(
                        uriString = uri,
                        lastPage = blockToSave,
                        totalPages = state.totalBlocks,
                        lastZoom = 1f,
                        lastCharOffset = blockToSave
                    )
                }
            }

            else -> Unit
        }
    }

    fun cycleReadingMode() {
        _uiState.update { state ->
            val nextMode = when (state.readingMode) {
                ReadingMode.NIGHT -> ReadingMode.SEPIA
                ReadingMode.SEPIA -> ReadingMode.PAPER
                ReadingMode.PAPER -> ReadingMode.NIGHT
            }
            state.copy(readingMode = nextMode)
        }
    }

    fun toggleReflowLayoutMode() {
        _uiState.update { state ->
            val next = if (state.reflowLayoutMode == ReflowLayoutMode.SCROLL) {
                ReflowLayoutMode.PAGINATED
            } else {
                ReflowLayoutMode.SCROLL
            }
            state.copy(reflowLayoutMode = next)
        }
    }

    fun adjustFontSize(deltaSp: Float) {
        _uiState.update { state ->
            state.copy(fontSizeSp = (state.fontSizeSp + deltaSp).coerceIn(12f, 30f))
        }
    }

    fun toggleFontFamily() {
        _uiState.update { state ->
            state.copy(useSerifFont = !state.useSerifFont)
        }
    }

    fun toggleBookmark() {
        val uri = activeUriString ?: return
        val nextValue = !_uiState.value.isBookmarked
        _uiState.update { it.copy(isBookmarked = nextValue) }
        viewModelScope.launch {
            repository.setPinned(uri, nextValue)
        }
    }

    fun removeCurrentFromRecent(onRemoved: () -> Unit) {
        val uri = activeUriString ?: return
        viewModelScope.launch {
            repository.removeDocument(uri)
            onRemoved()
        }
    }

    fun getCachedAspectRatio(pageIndex: Int): Float =
        fixedEngine?.getCachedAspectRatio(pageIndex) ?: (1f / 1.414f)

    suspend fun ensureAspectRatio(pageIndex: Int): Float =
        fixedEngine?.ensureAspectRatio(pageIndex) ?: (1f / 1.414f)

    fun getBestCachedBitmap(pageIndex: Int, targetBucketWidthPx: Int): Bitmap? {
        val engine = fixedEngine ?: return null
        return engine.getCachedBitmap(pageIndex, targetBucketWidthPx)
            ?: engine.getAnyCachedBitmapForPage(pageIndex)
    }

    fun getExactCachedBitmap(pageIndex: Int, targetBucketWidthPx: Int): Bitmap? {
        return fixedEngine?.getCachedBitmap(pageIndex, targetBucketWidthPx)
    }

    suspend fun renderPage(pageIndex: Int, targetBucketWidthPx: Int): Bitmap? {
        return fixedEngine?.renderPage(pageIndex, targetBucketWidthPx)
    }

    override fun onCleared() {
        persistPositionImmediately()
        val engineToClose = fixedEngine
        fixedEngine = null
        viewModelScope.launch {
            engineToClose?.close()
        }
        super.onCleared()
    }
}
