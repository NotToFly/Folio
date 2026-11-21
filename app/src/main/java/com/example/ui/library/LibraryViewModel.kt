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
    IN_PROGRESS,
    FINISHED,
    PINNED
}

data class LibraryUiState(
    val allDocuments: List<RecentDocumentEntity> = emptyList(),
    val featuredDocument: RecentDocumentEntity? = null,
    val remainingDocuments: List<RecentDocumentEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: LibraryFilter = LibraryFilter.ALL,
    val showClearHistoryDialog: Boolean = false,
    val isGeneratingSample: Boolean = false
) {
    val isLibraryEmpty: Boolean get() = allDocuments.isEmpty()
    val isFilterResultEmpty: Boolean
        get() = allDocuments.isNotEmpty() && featuredDocument == null && remainingDocuments.isEmpty()
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DocumentRepository = DocumentRepository(
        context = application.applicationContext,
        dao = FolioDatabase.getInstance(application).recentDocumentDao()
    )

    private val searchQueryFlow = MutableStateFlow("")
    private val selectedFilterFlow = MutableStateFlow(LibraryFilter.ALL)
    private val showClearDialogFlow = MutableStateFlow(false)
    private val isGeneratingSampleFlow = MutableStateFlow(false)

    private var lastRemovedDocument: RecentDocumentEntity? = null

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.recentDocuments,
        searchQueryFlow,
        selectedFilterFlow,
        showClearDialogFlow,
        isGeneratingSampleFlow
    ) { documents, query, filter, showClear, generatingSample ->
        val trimmedQuery = query.trim()
        val filtered = documents.filter { doc ->
            val matchesQuery = trimmedQuery.isEmpty() ||
                doc.displayName.contains(trimmedQuery, ignoreCase = true)
            val matchesFilter = when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.IN_PROGRESS -> !doc.isFinished
                LibraryFilter.FINISHED -> doc.isFinished
                LibraryFilter.PINNED -> doc.isPinned
            }
            matchesQuery && matchesFilter
        }

        val showHero = trimmedQuery.isEmpty() && filter == LibraryFilter.ALL && filtered.isNotEmpty()
        val featured = if (showHero) {
            filtered.maxByOrNull { it.lastOpenedAt }
        } else {
            null
        }
        val remaining = if (featured != null) {
            filtered.filterNot { it.uriString == featured.uriString }
        } else {
            filtered
        }

        LibraryUiState(
            allDocuments = documents,
            featuredDocument = featured,
            remainingDocuments = remaining,
            searchQuery = query,
            selectedFilter = filter,
            showClearHistoryDialog = showClear,
            isGeneratingSample = generatingSample
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.update { query }
    }

    fun onFilterSelected(filter: LibraryFilter) {
        selectedFilterFlow.update { filter }
    }

    fun clearFilters() {
        searchQueryFlow.update { "" }
        selectedFilterFlow.update { LibraryFilter.ALL }
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
        showClearDialogFlow.update { true }
    }

    fun dismissClearHistory() {
        showClearDialogFlow.update { false }
    }

    fun confirmClearHistory() {
        showClearDialogFlow.update { false }
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun takePersistablePermission(uri: Uri) {
        repository.takePersistableReadPermission(uri)
    }

    fun openSampleGuide(onReady: (Uri) -> Unit) {
        if (isGeneratingSampleFlow.value) return
        viewModelScope.launch {
            isGeneratingSampleFlow.update { true }
            try {
                val uri = SampleGuideGenerator.getOrCreateSampleGuideUri(getApplication())
                onReady(uri)
            } finally {
                isGeneratingSampleFlow.update { false }
            }
        }
    }
}
