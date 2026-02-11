@file:OptIn(ExperimentalCoroutinesApi::class)

package com.metro.delhimetrotracker.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metro.delhimetrotracker.data.model.DashboardUiState
import com.metro.delhimetrotracker.data.repository.DashboardRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * ViewModel for Dashboard Fragment
 */
class DashboardViewModel(
    private val repository: DashboardRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var hasLoadedOnce = false
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 0)

    val scheduledTrips: Flow<List<ScheduledTrip>> = repository.getAllScheduledTrips()
    init {
        startDashboardCollector()
        loadScheduledTrips()
    }

    private fun startDashboardCollector() {
        if (hasLoadedOnce) return   // 🔥 THIS IS THE REAL FIX
        hasLoadedOnce = true

        viewModelScope.launch {
            refreshTrigger
                .onStart { emit(Unit) } // first app launch
                .flatMapLatest {
                    combine(
                        repository.getDashboardStats(),
                        repository.getEnrichedTripHistory()
                    ) { stats, trips ->
                        val frequentRoutes = repository.getFrequentRoutes()
                        DashboardUiState.Success(
                            stats = stats,
                            frequentRoutes = frequentRoutes,
                            recentTrips = trips
                        )
                    }
                }
                .catch {
                    _uiState.value = DashboardUiState.Error("Failed to refresh dashboard")
                }
                .collect {
                    _uiState.value = it
                }
        }

    }

    fun refresh() {
        viewModelScope.launch {
            refreshTrigger.emit(Unit)
        }
    }


    private fun loadScheduledTrips() {
        viewModelScope.launch {
            repository.getScheduledTrips().collectLatest {
            }
        }
    }
}
class DashboardViewModelFactory(private val repository: DashboardRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}