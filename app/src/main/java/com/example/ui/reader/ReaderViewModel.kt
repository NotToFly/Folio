package com.example.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DocumentRepository
import com.example.data.FolioDatabase
import com.example.pdf.PdfDocumentEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ReaderLoadState {
    data object Loading : ReaderLoadState
    data class Ready(
        val totalPages: Int,
        val initialPage: Int,
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
    val loadState: ReaderLoadState = ReaderLoadState.Loading,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val readingMode: ReadingMode = ReadingMode.PAPER,
    val showResumedBanner: Boolean = false,
    val isBookmarked: Boolean = false
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DocumentRepository(
        context = application.applicationContext,
        dao = FolioDatabase.getInstance(application).recentDocumentDao()
    )

    private val engine = PdfDocumentEngine(application.applicationContext)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var savePositionJob: Job? = null
    private var openJob: Job? = null
    private var activeUriString: String? = null
    private var lastSavedPage: Int = -1
    private var lastZoomValue: Float = 1f

    fun openDocument(uriString: String) {
        if (uriString.isBlank()) return
        if (activeUriString == uriString && _uiState.value.loadState is ReaderLoadState.Ready) {
            return
        }

        openJob?.cancel()
        activeUriString = uriString
        lastSavedPage = -1

        openJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    uriString = uriString,
                    loadState = ReaderLoadState.Loading,
                    showResumedBanner = false
                )
            }

            try {
                val uri = Uri.parse(uriString)
                repository.takePersistableReadPermission(uri)

                val existingRecord = repository.getDocumentByUri(uriString)
                val meta = repository.queryMetadata(uri)
                _uiState.update { it.copy(documentTitle = meta.displayName) }

                val openInfo = engine.open(uri)
                val savedEntity = repository.recordDocumentOpened(
                    uri = uri,
                    totalPages = openInfo.pageCount,
                    thumbnailBitmap = openInfo.coverThumbnail
                )

                // Release cover thumbnail reference if not needed beyond saving
                val initialPage = (existingRecord?.lastPage ?: savedEntity.lastPage)
                    .coerceIn(0, (openInfo.pageCount - 1).coerceAtLeast(0))
                val wasResumed = initialPage > 0

                lastSavedPage = initialPage

                _uiState.update {
                    it.copy(
                        documentTitle = savedEntity.displayName,
                        totalPages = openInfo.pageCount,
                        currentPage = initialPage,
                        isBookmarked = savedEntity.isPinned,
                        loadState = ReaderLoadState.Ready(
                            totalPages = openInfo.pageCount,
                            initialPage = initialPage,
                            wasResumed = wasResumed
                        ),
                        showResumedBanner = wasResumed
                    )
                }

                if (wasResumed) {
                    launch {
                        delay(2600L)
                        _uiState.update { it.copy(showResumedBanner = false) }
                    }
                }
            } catch (se: SecurityException) {
                _uiState.update {
                    it.copy(
                        loadState = ReaderLoadState.Error(
                            message = se.localizedMessage ?: "Permission denied",
                            isPermissionOrMissing = true
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadState = ReaderLoadState.Error(
                            message = e.localizedMessage ?: "Unable to read PDF file",
                            isPermissionOrMissing = true
                        )
                    )
                }
            }
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
        if (clamped == lastSavedPage) return

        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch {
            delay(250L)
            lastSavedPage = clamped
            repository.updateReadingPosition(
                uriString = uri,
                lastPage = clamped,
                totalPages = total,
                lastZoom = lastZoomValue
            )
        }
    }

    fun persistPositionImmediately(currentZoom: Float = lastZoomValue) {
        val uri = activeUriString ?: return
        val state = _uiState.value
        if (state.loadState !is ReaderLoadState.Ready || state.totalPages <= 0) return

        savePositionJob?.cancel()
        val pageToSave = state.currentPage.coerceIn(0, state.totalPages - 1)
        lastSavedPage = pageToSave
        viewModelScope.launch {
            repository.updateReadingPosition(
                uriString = uri,
                lastPage = pageToSave,
                totalPages = state.totalPages,
                lastZoom = currentZoom
            )
        }
    }

    fun cycleReadingMode() {
        _uiState.update { state ->
            val nextMode = when (state.readingMode) {
                ReadingMode.PAPER -> ReadingMode.SEPIA
                ReadingMode.SEPIA -> ReadingMode.NIGHT
                ReadingMode.NIGHT -> ReadingMode.PAPER
            }
            state.copy(readingMode = nextMode)
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

    fun getCachedAspectRatio(pageIndex: Int): Float = engine.getCachedAspectRatio(pageIndex)

    suspend fun ensureAspectRatio(pageIndex: Int): Float = engine.ensureAspectRatio(pageIndex)

    fun getBestCachedBitmap(pageIndex: Int, targetBucketWidthPx: Int): Bitmap? {
        return engine.getCachedBitmap(pageIndex, targetBucketWidthPx)
            ?: engine.getAnyCachedBitmapForPage(pageIndex)
    }

    fun getExactCachedBitmap(pageIndex: Int, targetBucketWidthPx: Int): Bitmap? {
        return engine.getCachedBitmap(pageIndex, targetBucketWidthPx)
    }

    suspend fun renderPage(pageIndex: Int, targetBucketWidthPx: Int): Bitmap? {
        return engine.renderPage(pageIndex, targetBucketWidthPx)
    }

    override fun onCleared() {
        persistPositionImmediately()
        viewModelScope.launch {
            engine.close()
        }
        super.onCleared()
    }
}
