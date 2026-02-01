package com.metro.delhimetrotracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metro.delhimetrotracker.data.model.DashboardUiState
import com.metro.delhimetrotracker.data.repository.DashboardRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ViewModelProvider
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel for Dashboard Fragment
 */
class DashboardViewModel(
    private val repository: DashboardRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _scheduledTrips = MutableStateFlow<List<ScheduledTrip>>(emptyList())
    val scheduledTrips: Flow<List<ScheduledTrip>> = repository.getAllScheduledTrips()
    init {
        loadDashboardData()
        loadScheduledTrips()
    }

    private fun loadDashboardData() {
        viewModelScope.launch {
            try {
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
                }.catch { error ->
                    _uiState.value = DashboardUiState.Error(
                        error.message ?: "Failed to load dashboard"
                    )
                }.collect { state ->
                    _uiState.value = state
                }
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(e.message ?: "Unknown error")
            }
        }
    }


    /**
     * Format duration into human-readable string
     */
    fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours == 0 -> "${mins}m"
            mins == 0 -> "${hours}h"
            else -> "${hours}h ${mins}m"
        }
    }

    /**
     * Format carbon savings with context
     */
    fun formatCarbonSavings(kg: Double): String {
        return when {
            kg < 1 -> "${(kg * 1000).toInt()}g CO₂"
            kg < 10 -> "${"%.1f".format(kg)} kg CO₂"
            else -> "${kg.toInt()} kg CO₂"
        }
    }

    fun getDayName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            1 -> "Sunday"
            2 -> "Monday"
            3 -> "Tuesday"
            4 -> "Wednesday"
            5 -> "Thursday"
            6 -> "Friday"
            7 -> "Saturday"
            else -> "Unknown"
        }
    }

    fun formatTimePeriod(dayOfWeek: Int, hourOfDay: Int): String {
        val day = getDayName(dayOfWeek)
        val period = when (hourOfDay) {
            in 0..5 -> "late night"
            in 6..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
        return "$day $period"
    }

    fun refresh() {
        _uiState.value = DashboardUiState.Loading
        loadDashboardData()
        loadScheduledTrips()
    }
    private fun loadScheduledTrips() {
        viewModelScope.launch {
            repository.getScheduledTrips().collectLatest { trips ->
                _scheduledTrips.value = trips
            }
        }
    }
    fun getAllStationNames(onResult: (List<String>) -> Unit) {
        viewModelScope.launch {
            val names = repository.getAllStationNames()
            onResult(names)
        }
    }
    fun deleteTripById(tripId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTrip(tripId)
        }
    }
    // Add this function
    fun undoDelete(tripId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.restoreTrip(tripId)
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