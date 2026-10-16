package com.safebrowse.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebrowse.database.entity.BlockedDomain
import com.safebrowse.repository.BlocklistRepository
import com.safebrowse.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class BlocklistViewModel @Inject constructor(
    private val repository: BlocklistRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val domains: StateFlow<List<BlockedDomain>> = _searchQuery
        .debounce(250)
        .flatMapLatest { q -> if (q.isBlank()) repository.observeAll() else repository.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage.asSharedFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    fun onSearchQueryChanged(q: String) { _searchQuery.value = q }
    fun showAddDialog() { _showAddDialog.value = true }
    fun dismissAddDialog() { _showAddDialog.value = false }

    fun addDomain(input: String) {
        viewModelScope.launch {
            when (val r = repository.addDomain(input)) {
                is BlocklistRepository.AddResult.Success -> {
                    _uiMessage.emit("${r.domain} is blocked now.")
                    _showAddDialog.value = false
                }
                BlocklistRepository.AddResult.InvalidDomain ->
                    _uiMessage.emit("That does not look like a site. Try example.com")
                BlocklistRepository.AddResult.AlreadyExists ->
                    _uiMessage.emit("Already on the list.")
            }
        }
    }

    /**
     * Reached only from the Romans 6 gate, after the whole chapter and the
     * word check. There is no ungated delete anywhere in this ViewModel.
     */
    fun confirmUnblock(id: Long, domain: String) {
        viewModelScope.launch {
            repository.removeDomainById(id)
            preferences.incrementReadings()
            _uiMessage.emit("$domain is unblocked.")
        }
    }
}
