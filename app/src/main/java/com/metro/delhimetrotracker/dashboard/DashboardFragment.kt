package com.metro.delhimetrotracker.ui.dashboard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.model.DashboardUiState
import com.metro.delhimetrotracker.data.model.TripCardData
import com.metro.delhimetrotracker.data.repository.DashboardRepository
import com.metro.delhimetrotracker.ui.MainActivity
import com.metro.delhimetrotracker.worker.SyncWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dashboard Fragment - Metro Life Analytics
 */
class DashboardFragment : Fragment() {

    private lateinit var viewModel: DashboardViewModel
    private lateinit var tripAdapter: TripHistoryAdapter
    private lateinit var btnReturnJourney: MaterialButton

    // Stats Views
    private lateinit var tvTotalTrips: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvCarbonSaved: TextView
    private lateinit var tvStationsVisited: TextView
    private lateinit var progressStations: LinearProgressIndicator

    // Frequent Routes
    private lateinit var cardFrequentRoute: MaterialCardView
    private lateinit var tvFrequentRoute: TextView
    private lateinit var tvFrequentPattern: TextView
    private lateinit var btnQuickStart: MaterialButton

    // Trip History
    private lateinit var rvTripHistory: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var progressLoading: ProgressBar

    // Swipe Refresh
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Initialize Views FIRST to avoid UninitializedPropertyAccessException
        initViews(view)
        scheduleSyncAfterTripEnd()

        // 2. Setup ViewModel
        val database = (requireActivity().application as MetroTrackerApplication).database
        val repository = DashboardRepository(database)
        viewModel = DashboardViewModel(repository)

        // 3. Setup Adapter
        tripAdapter = TripHistoryAdapter(object : TripHistoryAdapter.OnTripDoubleTapListener {
            override fun onTripDoubleTap(source: String, destination: String) {
                (activity as? MainActivity)?.showStationSelectionDialog(source, destination)
            }
            override fun onTripShare(trip: TripCardData) {
                shareTripDetails(trip)
            }
        })

        // 4. Configure RecyclerView
        rvTripHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = tripAdapter
        }

        // 5. Setup Header & Buttons
        view.findViewById<ImageButton>(R.id.btnCloseDashboard).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnQuickStart.setOnClickListener {
            val routeText = tvFrequentRoute.text.toString()
            val stations = routeText.split(" → ")
            if (stations.size == 2) {
                (activity as? MainActivity)?.showStationSelectionDialog(stations[0], stations[1])
            }
        }

        btnReturnJourney.setOnClickListener {
            val routeText = tvFrequentRoute.text.toString()
            val stations = routeText.split(" → ")
            if (stations.size == 2) {
                (activity as? MainActivity)?.showStationSelectionDialog(stations[1], stations[0])
            }
        }

        // 6. Setup Swipe to Delete
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position < 0 || position >= tripAdapter.currentList.size) return

                val tripCard = tripAdapter.currentList[position]

                viewModel.deleteTripById(tripCard.tripId)

                val snackbar = Snackbar.make(requireView(), "Trip deleted", Snackbar.LENGTH_LONG)
                snackbar.setAction("UNDO") {
                    viewModel.undoDelete(tripCard.tripId)
                }
                snackbar.show()
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(rvTripHistory)

        // 7. Setup Swipe to Refresh
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )

        swipeRefreshLayout.setOnRefreshListener {
            (activity as? MainActivity)?.performManualSync {
                if (isAdded) { // Safety check
                    swipeRefreshLayout.isRefreshing = false
                }
            } ?: run {
                swipeRefreshLayout.isRefreshing = false
            }
        }

        // 8. Observe Data
        observeUiState()
    }

    private fun initViews(view: View) {
        tvTotalTrips = view.findViewById(R.id.tvTotalTrips)
        tvTotalTime = view.findViewById(R.id.tvTotalTime)
        tvCarbonSaved = view.findViewById(R.id.tvCarbonSaved)
        tvStationsVisited = view.findViewById(R.id.tvStationsVisited)
        progressStations = view.findViewById(R.id.progressStations)

        cardFrequentRoute = view.findViewById(R.id.cardFrequentRoute)
        tvFrequentRoute = view.findViewById(R.id.tvFrequentRoute)
        tvFrequentPattern = view.findViewById(R.id.tvFrequentPattern)

        btnQuickStart = view.findViewById(R.id.btnQuickStart)
        btnReturnJourney = view.findViewById(R.id.btnReturnJourney)

        // FIX: Matched with XML ID
        rvTripHistory = view.findViewById(R.id.recyclerTripHistory)

        tvEmptyState = view.findViewById(R.id.tvEmptyState)
        progressLoading = view.findViewById(R.id.progressLoading)

        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
    }

    private fun scheduleSyncAfterTripEnd() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            "TripSync",
            ExistingWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun shareTripDetails(trip: TripCardData) {
        showTripDetailsDialog(trip)
    }

    private fun showTripDetailsDialog(trip: TripCardData) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_trip_details, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvDialogRoute = dialogView.findViewById<TextView>(R.id.tvDialogRoute)
        val tvDialogDateTime = dialogView.findViewById<TextView>(R.id.tvDialogDateTime)
        val tvDialogDuration = dialogView.findViewById<TextView>(R.id.tvDialogDuration)
        val tvDialogStations = dialogView.findViewById<TextView>(R.id.tvDialogStations)
        val rvDialogStations = dialogView.findViewById<RecyclerView>(R.id.rvDialogStations)
        val btnShareDialog = dialogView.findViewById<MaterialButton>(R.id.btnShareDialog)

        tvDialogRoute.text = "${trip.sourceStationName} → ${trip.destinationStationName}"
        val dateFormat = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        tvDialogDateTime.text = dateFormat.format(trip.startTime)
        tvDialogDuration.text = "Duration: ${trip.durationMinutes} mins"
        tvDialogStations.text = "${trip.stationCount} stations"

        rvDialogStations.layoutManager = LinearLayoutManager(requireContext())

        lifecycleScope.launch {
            val db = (requireActivity().application as MetroTrackerApplication).database
            val fullTrip = db.tripDao().getTripById(trip.tripId)

            if (fullTrip != null) {
                val stationIds = fullTrip.visitedStations
                val stations = stationIds.mapNotNull { stationId ->
                    db.metroStationDao().getStationById(stationId)
                }

                val timePerStation = if (stations.size > 1) {
                    (trip.durationMinutes * 60000L) / (stations.size - 1)
                } else {
                    0L
                }

                // CHANGED: Using new class name 'TimelineItem'
                val timelineItems = stations.mapIndexed { index, station ->
                    TimelineItem(
                        stationName = station.stationName,
                        arrivalTime = Date(trip.startTime.time + (index * timePerStation)),
                        lineColor = station.lineColor
                    )
                }

                // CHANGED: Using new adapter name 'TimelineAdapter'
                val adapter = TimelineAdapter(timelineItems)
                rvDialogStations.adapter = adapter
            }
        }

        btnShareDialog.setOnClickListener {
            shareDetailedTripInfo(trip)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun shareDetailedTripInfo(trip: TripCardData) {
        lifecycleScope.launch {
            val db = (requireActivity().application as MetroTrackerApplication).database
            val fullTrip = db.tripDao().getTripById(trip.tripId)

            if (fullTrip != null) {
                val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(trip.startTime)
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(trip.startTime)

                val stationIds = fullTrip.visitedStations
                val stations = stationIds.mapNotNull { stationId ->
                    db.metroStationDao().getStationById(stationId)
                }

                val timePerStation = if (stations.size > 1) {
                    (trip.durationMinutes * 60000L) / (stations.size - 1)
                } else {
                    0L
                }

                val stationList = stations.mapIndexed { index, station ->
                    val stationTime = Date(trip.startTime.time + (index * timePerStation))
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    "${index + 1}. ${station.stationName} - ${timeFormat.format(stationTime)}"
                }.joinToString("\n")

                val sosInfo = if (trip.hadSosAlert == true && trip.sosStationName != null) {
                    val sosTime = trip.sosTimestamp?.let {
                        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(it))
                    } ?: "Unknown time"
                    "\n\n🆘 SOS Alert: ${trip.sosStationName} at $sosTime"
                } else {
                    ""
                }

                val shareBody = """
My Delhi Metro Journey

From: ${trip.sourceStationName}
To: ${trip.destinationStationName}
Date: $dateStr at $timeStr
Duration: ${trip.durationMinutes} mins

Journey Path:
$stationList$sosInfo

— Shared via Delhi Metro Tracker
            """.trimIndent()

                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareBody)
                    type = "text/plain"
                }

                val shareIntent = Intent.createChooser(sendIntent, "Share Trip Details")
                startActivity(shareIntent)
            }
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is DashboardUiState.Loading -> showLoading()
                    is DashboardUiState.Success -> showSuccess(state)
                    is DashboardUiState.Error -> showError(state.message)
                }
            }
        }
    }

    private fun showLoading() {
        progressLoading.visibility = View.VISIBLE
        rvTripHistory.visibility = View.GONE
        tvEmptyState.visibility = View.GONE
    }

    private fun showSuccess(state: DashboardUiState.Success) {
        progressLoading.visibility = View.GONE

        updateStatsCard(state)

        if (state.frequentRoutes.isNotEmpty()) {
            updateFrequentRouteCard(state)
            cardFrequentRoute.visibility = View.VISIBLE
        } else {
            cardFrequentRoute.visibility = View.GONE
        }

        if (state.recentTrips.isEmpty()) {
            rvTripHistory.visibility = View.GONE
            tvEmptyState.visibility = View.VISIBLE
        } else {
            rvTripHistory.visibility = View.VISIBLE
            tvEmptyState.visibility = View.GONE
            tripAdapter.submitList(state.recentTrips)
        }
    }

    private fun updateStatsCard(state: DashboardUiState.Success) {
        val stats = state.stats

        tvTotalTrips.text = stats.totalTrips.toString()
        tvTotalTime.text = viewModel.formatDuration(stats.totalMinutes)
        tvCarbonSaved.text = viewModel.formatCarbonSavings(stats.carbonSavedKg)

        val progress = if (stats.totalStationsInNetwork > 0) {
            (stats.uniqueStationsVisited.toFloat() / stats.totalStationsInNetwork * 100).toInt()
        } else 0

        tvStationsVisited.text = "${stats.uniqueStationsVisited} / ${stats.totalStationsInNetwork}"
        progressStations.setProgressCompat(progress, true)
    }

    private fun updateFrequentRouteCard(state: DashboardUiState.Success) {
        if (state.frequentRoutes.isEmpty()) {
            cardFrequentRoute.visibility = View.GONE
            return
        }

        val topRoute = state.frequentRoutes.first()
        tvFrequentRoute.text = "${topRoute.sourceStationName} → ${topRoute.destinationStationName}"
        val pattern = viewModel.formatTimePeriod(topRoute.commonDayOfWeek, topRoute.commonHourOfDay)
        tvFrequentPattern.text = "Usually on $pattern • ${topRoute.tripCount} trips"
    }

    private fun showError(message: String) {
        progressLoading.visibility = View.GONE
        tvEmptyState.visibility = View.VISIBLE
        tvEmptyState.text = "Error: $message"
    }
}

data class TimelineItem(
    val stationName: String,
    val arrivalTime: java.util.Date,
    val lineColor: String // Accepts Hex Codes (e.g., "#D32F2F")
)

/**
 * Adapter for the Trip Details Timeline (Connect-the-Dots Style)
 */
class TimelineAdapter(private val items: List<TimelineItem>) :
    RecyclerView.Adapter<TimelineAdapter.TimelineViewHolder>() {

    class TimelineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvStationName: TextView = view.findViewById(R.id.tvStationName)
        val tvTime: TextView = view.findViewById(R.id.tvTime)

        // These IDs must match your "Old Layout" XML
        val viewLineTop: View = view.findViewById(R.id.viewLineTop)
        val viewLineBottom: View = view.findViewById(R.id.viewLineBottom)
        val ivDot: android.widget.ImageView = view.findViewById(R.id.ivDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimelineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_station, parent, false)
        return TimelineViewHolder(view)
    }

    override fun onBindViewHolder(holder: TimelineViewHolder, position: Int) {
        val item = items[position]

        // 1. Set Text
        holder.tvStationName.text = item.stationName
        val timeFormat = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
        holder.tvTime.text = timeFormat.format(item.arrivalTime)

        // 2. Handle Lines Visibility (Hide top for first, bottom for last)
        holder.viewLineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.viewLineBottom.visibility = if (position == items.size - 1) View.INVISIBLE else View.VISIBLE

        // 3. COLOR LOGIC (Hex Code Support)
        val color = try {
            // Try to parse the hex code directly from DB (e.g., "#D32F2F")
            android.graphics.Color.parseColor(item.lineColor)
        } catch (e: Exception) {
            // Fallback to Blue-Grey if parsing fails
            android.graphics.Color.parseColor("#607D8B")
        }

        // 4. Apply Color to Dot and Lines
        holder.ivDot.setColorFilter(color)
        holder.viewLineTop.setBackgroundColor(color)
        holder.viewLineBottom.setBackgroundColor(color)
    }

    override fun getItemCount() = items.size
}