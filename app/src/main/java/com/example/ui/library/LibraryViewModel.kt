package com.example.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DocumentRepository
import com.example.data.FolioDatabase
import com.example.data.RecentDocumentEntity
import com.example.pdf.SampleGuideGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryFilter {
    ALL,
    PINNED,
    IN_PROGRESS,
    FINISHED
}

data class LibraryUiState(
    val allDocuments: List<RecentDocumentEntity> = emptyList(),
    val bookmarkedDocuments: List<RecentDocumentEntity> = emptyList(),
    val recentDocuments: List<RecentDocumentEntity> = emptyList(),
    val searchQuery: String = "",
    val isSearchExpanded: Boolean = false,
    val isGridView: Boolean = false,
    val selectedFilter: LibraryFilter = LibraryFilter.ALL,
    val showClearHistoryDialog: Boolean = false,
    val isGeneratingSample: Boolean = false
) {
    val isLibraryEmpty: Boolean get() = allDocuments.isEmpty()
    val hasClearableHistory: Boolean get() = allDocuments.any { !it.isPinned }
    val isFilterResultEmpty: Boolean
        get() = allDocuments.isNotEmpty() &&
            bookmarkedDocuments.isEmpty() &&
            recentDocuments.isEmpty()
}

private data class LibraryControlsState(
    val searchQuery: String = "",
    val isSearchExpanded: Boolean = false,
    val isGridView: Boolean = false,
    val selectedFilter: LibraryFilter = LibraryFilter.ALL,
    val showClearHistoryDialog: Boolean = false,
    val isGeneratingSample: Boolean = false
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DocumentRepository = DocumentRepository(
        context = application.applicationContext,
        dao = FolioDatabase.getInstance(application).recentDocumentDao()
    )

    private val controlsFlow = MutableStateFlow(LibraryControlsState())
    private var lastRemovedDocument: RecentDocumentEntity? = null

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.recentDocuments,
        controlsFlow
    ) { documents, controls ->
        val trimmedQuery = controls.searchQuery.trim()
        val filtered = documents
            .filter { doc ->
                val matchesQuery = trimmedQuery.isEmpty() ||
                    doc.displayName.contains(trimmedQuery, ignoreCase = true) ||
                    doc.bookFormat.badge.contains(trimmedQuery, ignoreCase = true)
                val matchesFilter = when (controls.selectedFilter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.PINNED -> doc.isPinned
                    LibraryFilter.IN_PROGRESS -> !doc.isFinished
                    LibraryFilter.FINISHED -> doc.isFinished
                }
                matchesQuery && matchesFilter
            }
            .sortedWith(
                compareByDescending<RecentDocumentEntity> { it.isPinned }
                    .thenByDescending { it.lastOpenedAt }
            )

        val isDefaultUnfiltered = trimmedQuery.isEmpty() && controls.selectedFilter == LibraryFilter.ALL
        val bookmarked = if (isDefaultUnfiltered) {
            filtered.filter { it.isPinned }
        } else {
            emptyList()
        }
        val recent = if (isDefaultUnfiltered) {
            filtered.filterNot { it.isPinned }
        } else {
            filtered
        }

        LibraryUiState(
            allDocuments = documents,
            bookmarkedDocuments = bookmarked,
            recentDocuments = recent,
            searchQuery = controls.searchQuery,
            isSearchExpanded = controls.isSearchExpanded,
            isGridView = controls.isGridView,
            selectedFilter = controls.selectedFilter,
            showClearHistoryDialog = controls.showClearHistoryDialog,
            isGeneratingSample = controls.isGeneratingSample
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun onSearchQueryChange(query: String) {
        controlsFlow.update { it.copy(searchQuery = query) }
    }

    fun openSearch() {
        controlsFlow.update { it.copy(isSearchExpanded = true) }
    }

    fun closeSearch() {
        controlsFlow.update { it.copy(isSearchExpanded = false, searchQuery = "") }
    }

    fun toggleGridView() {
        controlsFlow.update { it.copy(isGridView = !it.isGridView) }
    }

    fun onFilterSelected(filter: LibraryFilter) {
        controlsFlow.update { it.copy(selectedFilter = filter) }
    }

    fun clearFilters() {
        controlsFlow.update {
            it.copy(
                searchQuery = "",
                isSearchExpanded = false,
                selectedFilter = LibraryFilter.ALL
            )
        }
    }

    fun togglePin(document: RecentDocumentEntity) {
        viewModelScope.launch {
            repository.setPinned(document.uriString, !document.isPinned)
        }
    }

    fun removeDocument(document: RecentDocumentEntity) {
        lastRemovedDocument = document
        viewModelScope.launch {
            repository.removeDocument(document.uriString)
        }
    }

    fun undoLastRemove() {
        val removed = lastRemovedDocument ?: return
        lastRemovedDocument = null
        viewModelScope.launch {
            repository.restoreDocument(removed)
        }
    }

    fun requestClearHistory() {
        controlsFlow.update { it.copy(showClearHistoryDialog = true) }
    }

    fun dismissClearHistory() {
        controlsFlow.update { it.copy(showClearHistoryDialog = false) }
    }

    fun confirmClearHistory() {
        controlsFlow.update { it.copy(showClearHistoryDialog = false) }
        viewModelScope.launch {
            repository.clearUnpinnedHistory()
        }
    }

    fun takePersistablePermission(uri: Uri) {
        repository.takePersistableReadPermission(uri)
    }

    fun openSampleGuide(onReady: (Uri) -> Unit) {
        if (controlsFlow.value.isGeneratingSample) return
        viewModelScope.launch {
            controlsFlow.update { it.copy(isGeneratingSample = true) }
            try {
                val uri = SampleGuideGenerator.getOrCreateSampleGuideUri(getApplication())
                onReady(uri)
            } finally {
                controlsFlow.update { it.copy(isGeneratingSample = false) }
            }
        }
    }
}
