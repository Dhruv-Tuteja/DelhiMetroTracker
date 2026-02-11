package com.metro.delhimetrotracker.dashboard

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.text.SimpleDateFormat
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.MetroTrackerApplication
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.model.DashboardUiState
import com.metro.delhimetrotracker.data.repository.DashboardRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent
import android.graphics.Typeface
import java.util.Locale
import android.widget.ImageView
import java.util.Date
import com.metro.delhimetrotracker.MainActivity
import com.metro.delhimetrotracker.data.model.TripCardData
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import androidx.core.graphics.toColorInt
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout


class DashboardFragment : Fragment() {

    private lateinit var viewModel: DashboardViewModel
    private lateinit var viewPager: ViewPager2

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout


    // UI References
    private lateinit var tvTabHistory: TextView
    private lateinit var tvTabScheduled: TextView
    private lateinit var tvTotalTrips: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvCarbonSaved: TextView
    private lateinit var tvStationsVisited: TextView

    // Frequent Route Card references
    private lateinit var cardFrequentRoute: View
    private lateinit var tvFrequentRoute: TextView
    private lateinit var tvFrequentPattern: TextView


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Initialize Database & Repository
        val database = AppDatabase.getDatabase(requireContext())
        val repository = DashboardRepository(database)

        // 2. Initialize ViewModel using the Factory (FIXES THE CRASH)
        // This connects the Repository to the ViewModel correctly.
        val factory = DashboardViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[DashboardViewModel::class.java]

        // 3. Initialize Views
        initializeViews(view)
        setupViewPager()
        setupClickListeners(view)

        // 4. Observe Data (this will use cached data if available)
        observeViewModel()
    }
    private fun initializeViews(view: View) {
        viewPager = view.findViewById(R.id.viewPager)
        tvTabHistory = view.findViewById(R.id.tvTabHistory)
        tvTabScheduled = view.findViewById(R.id.tvTabScheduled)

        tvTotalTrips = view.findViewById(R.id.tvTotalTrips)
        tvTotalTime = view.findViewById(R.id.tvTotalTime)
        tvCarbonSaved = view.findViewById(R.id.tvCarbonSaved)
        tvStationsVisited = view.findViewById(R.id.tvStationsVisited)

        tvFrequentRoute = view.findViewById(R.id.tvFrequentRoute)
        tvFrequentPattern = view.findViewById(R.id.tvFrequentPattern)
        cardFrequentRoute = view.findViewById(R.id.cardFrequentRoute)

        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)

    }

    private fun setupViewPager() {
        // This Adapter manages the two tabs (History vs Scheduled)
        val adapter = DashboardPagerAdapter(this)
        viewPager.adapter = adapter

        viewPager.isUserInputEnabled = false


        // Handle Tab Clicks
        tvTabHistory.setOnClickListener {
            viewPager.currentItem = 0
            updateTabStyles(0)
        }

        tvTabScheduled.setOnClickListener {
            viewPager.currentItem = 1
            updateTabStyles(1)
        }

        // Sync Swipe with Tabs
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabStyles(position)
            }
        })
    }

    private fun updateTabStyles(position: Int) {
        val activeColor = "#6200EE".toColorInt() // Deep Purple
        val inactiveColor = Color.GRAY

        if (position == 0) {
            tvTabHistory.setTextColor(activeColor)
            tvTabHistory.setTypeface(null, Typeface.BOLD)

            tvTabScheduled.setTextColor(inactiveColor)
            tvTabScheduled.setTypeface(null, Typeface.NORMAL)
        } else {
            tvTabScheduled.setTextColor(activeColor)
            tvTabScheduled.setTypeface(null, Typeface.BOLD)

            tvTabHistory.setTextColor(inactiveColor)
            tvTabHistory.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun setupClickListeners(view: View) {

        // Quick Start button - launches station selector with frequent route pre-filled
        view.findViewById<MaterialButton>(R.id.btnQuickStart)?.setOnClickListener {
            val frequentRouteText = tvFrequentRoute.text.toString()
            if (frequentRouteText.isNotEmpty() && frequentRouteText.contains("→")) {
                val stations = frequentRouteText.split("→").map { it.trim() }
                if (stations.size == 2) {
                    (activity as? MainActivity)?.showStationSelectionDialog(stations[0], stations[1])
                }
            }
        }

        // Return Journey button - launches station selector with reversed route
        view.findViewById<MaterialButton>(R.id.btnReturnJourney)?.setOnClickListener {
            val frequentRouteText = tvFrequentRoute.text.toString()
            if (frequentRouteText.isNotEmpty() && frequentRouteText.contains("→")) {
                val stations = frequentRouteText.split("→").map { it.trim() }
                if (stations.size == 2) {
                    // Reverse the stations for return journey
                    (activity as? MainActivity)?.showStationSelectionDialog(stations[1], stations[0])
                }
            }
        }

        // Refresh logic
        swipeRefreshLayout.setOnRefreshListener {
            performSyncAndRefresh()
        }
    }

    private fun performSyncAndRefresh() {
        swipeRefreshLayout.isRefreshing = true

        // Call MainActivity's performManualSync function
        (activity as? MainActivity)?.performManualSync {
            // After sync completes, refresh the ViewModel data
            viewModel.refresh()

            // Stop the refresh animation
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is DashboardUiState.Loading -> {
                        // Optional: You could show a loading spinner here
                    }
                    is DashboardUiState.Success -> {
                        updateDashboardUI(state)
                    }
                    is DashboardUiState.Error -> {
                        Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDashboardUI(state: DashboardUiState.Success) {
        // 1. Update Top Stats
        tvTotalTrips.text = state.stats.totalTrips.toString()
        tvTotalTime.text = formatMinutesToHours(state.stats.totalMinutes)
        tvCarbonSaved.text = String.format(Locale.getDefault(), "%.1f kg", state.stats.carbonSavedKg)
        tvStationsVisited.text = "${state.stats.uniqueStationsVisited} / ${state.stats.totalStationsInNetwork}"

        // 2. Update Frequent Route Card (Option 2 Logic)
        if (state.frequentRoutes.isNotEmpty()) {
            val topRoute = state.frequentRoutes.first()
            cardFrequentRoute.visibility = View.VISIBLE

            // Combine source and destination for the single tvFrequentRoute TextView
            tvFrequentRoute.text = "${topRoute.sourceStationName} → ${topRoute.destinationStationName}"

            // Use the pattern TextView for the trip count
            tvFrequentPattern.text = "Based on ${topRoute.tripCount} recent trips"
        } else {
            cardFrequentRoute.visibility = View.GONE
        }
    }

    private fun formatMinutesToHours(minutes: Int): String {
        val hrs = minutes / 60
        val mins = minutes % 60
        return if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    }
    fun onTripDoubleTap(source: String, destination: String) {
        (activity as? MainActivity)?.showStationSelectionDialog(source, destination)
    }
    fun onTripLongPress(trip: TripCardData) {
        showTripDetailsDialog(trip)
    }

    @SuppressLint("SetTextI18n")
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

        // THIS IS THE FIX - Fetch and display journey path from old code
        lifecycleScope.launch {
            val db = (requireActivity().application as MetroTrackerApplication).database
            val fullTrip = db.tripDao().getTripById(trip.tripId)

            if (fullTrip != null) {
                val checkpoints = db.stationCheckpointDao()
                    .getCheckpointsForTrip(trip.tripId)
                    .sortedBy { it.stationOrder }

                // ✅ FIX: Get all stations first, then match by NAME instead of UUID
                val allStations = db.metroStationDao().getAllStations()
                val stationMap = allStations.associateBy { it.stationName }

                val stations = checkpoints.mapNotNull { checkpoint ->
                    stationMap[checkpoint.stationName]
                }


                val timePerStation = if (stations.size > 1) {
                    (trip.durationMinutes * 60000L) / (stations.size - 1)
                } else {
                    0L
                }

                // Create timeline items with actual station data
                val timelineItems = stations.mapIndexed { index, station ->
                    TimelineItem(
                        stationName = station.stationName,
                        arrivalTime = Date(trip.startTime.time + (index * timePerStation)),
                        lineColor = station.lineColor
                    )
                }

                // Use TimelineAdapter from old code
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

                // ✅ FIX: Use checkpoints instead of visitedStations
                val checkpoints = db.stationCheckpointDao()
                    .getCheckpointsForTrip(trip.tripId)
                    .sortedBy { it.stationOrder }

                val allStations = db.metroStationDao().getAllStations()
                val stationMap = allStations.associateBy { it.stationName }

                val stations = checkpoints.mapNotNull { checkpoint ->
                    stationMap[checkpoint.stationName]
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
}

data class TimelineItem(
    val stationName: String,
    val arrivalTime: Date,
    val lineColor: String
)

class TimelineAdapter(private val items: List<TimelineItem>) :
    RecyclerView.Adapter<TimelineAdapter.TimelineViewHolder>() {

    class TimelineViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvStationName: TextView = view.findViewById(R.id.tvStationName)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val viewLineTop: View = view.findViewById(R.id.viewLineTop)
        val viewLineBottom: View = view.findViewById(R.id.viewLineBottom)
        val ivDot: ImageView = view.findViewById(R.id.ivDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimelineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_station, parent, false)
        return TimelineViewHolder(view)
    }

    override fun onBindViewHolder(holder: TimelineViewHolder, position: Int) {
        val item = items[position]

        holder.tvStationName.text = item.stationName
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        holder.tvTime.text = timeFormat.format(item.arrivalTime)

        holder.viewLineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.viewLineBottom.visibility = if (position == items.size - 1) View.INVISIBLE else View.VISIBLE

        val color = try {
            item.lineColor.toColorInt()
        } catch (_: Exception) {
            "#607D8B".toColorInt()
        }

        holder.ivDot.setColorFilter(color)
        holder.viewLineTop.setBackgroundColor(color)
        holder.viewLineBottom.setBackgroundColor(color)
    }

    override fun getItemCount() = items.size
}