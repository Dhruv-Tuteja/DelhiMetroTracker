package com.metro.delhimetrotracker.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton // Add this
import com.google.android.material.button.MaterialButton // Add this
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.model.DashboardUiState
import com.metro.delhimetrotracker.data.model.TravelTimeContext
import com.metro.delhimetrotracker.data.repository.DashboardRepository
import com.metro.delhimetrotracker.ui.MainActivity
import kotlinx.coroutines.launch
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.recyclerview.widget.ItemTouchHelper
import java.text.SimpleDateFormat
import java.util.Locale
import com.metro.delhimetrotracker.data.model.TripCardData
import java.util.Date

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
    //private lateinit var tvTimeContext: TextView
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)

        val database = (requireActivity().application as MetroTrackerApplication).database
        val repository = DashboardRepository(database)
        viewModel = DashboardViewModel(repository)

        tripAdapter = TripHistoryAdapter(object : TripHistoryAdapter.OnTripDoubleTapListener {
            override fun onTripDoubleTap(source: String, destination: String) {
                (activity as? MainActivity)?.showStationSelectionDialog(source, destination)
            }
            override fun onTripShare(trip: TripCardData) {
                shareTripDetails(trip)
            }
        })
        rvTripHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = tripAdapter
        }
        view.findViewById<ImageButton>(R.id.btnCloseDashboard).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.btnQuickStart).setOnClickListener {
            val routeText = view.findViewById<TextView>(R.id.tvFrequentRoute).text.toString()
            val stations = routeText.split(" → ")
            if (stations.size == 2) {
                (activity as? MainActivity)?.showStationSelectionDialog(stations[0], stations[1])
            }
        }
        view.findViewById<MaterialButton>(R.id.btnReturnJourney)?.setOnClickListener {
            val routeText = view.findViewById<TextView>(R.id.tvFrequentRoute).text.toString()
            val stations = routeText.split(" → ")
            if (stations.size == 2) {
                // SWAP: Destination becomes Source, Source becomes Destination
                (activity as? MainActivity)?.showStationSelectionDialog(stations[1], stations[0])
            }
        }
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val tripCard = tripAdapter.currentList[position] // This is TripCardData

                val snackbar = Snackbar.make(requireView(), "Trip deleted", Snackbar.LENGTH_LONG)
                snackbar.setAction("UNDO") {
                    tripAdapter.notifyItemChanged(position)
                }

                snackbar.addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION) {
                            // Use the ID from your TripCardData to delete
                            viewModel.deleteTripById(tripCard.tripId)
                        }
                    }
                })
                snackbar.show()
            }
        }

        ItemTouchHelper(swipeHandler).attachToRecyclerView(rvTripHistory)
        observeUiState()
    }
    private fun shareTripDetails(trip: TripCardData) {
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(trip.startTime)
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(trip.startTime)

        val durationText = if (trip.durationMinutes == 0) {
            "Quick ride"
        } else {
            "${trip.durationMinutes} mins"
        }

        // Add SOS information if present
        val sosInfo = if (trip.hadSosAlert == true && trip.sosStationName != null) {
            val sosTime = trip.sosTimestamp?.let {
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(it))
            } ?: "Unknown time"
            "\n🆘 SOS Alert: ${trip.sosStationName} at $sosTime"
        } else {
            ""
        }

        val shareBody = """
My Delhi Metro Journey

From: ${trip.sourceStationName}
To: ${trip.destinationStationName}
Date: $dateStr at $timeStr
Duration: $durationText
Stations covered: ${trip.stationCount}$sosInfo

— Shared via Delhi Metro Tracker
    """.trimIndent()

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareBody)
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, "Share Trip")
        startActivity(shareIntent)
    }


    private fun initViews(view: View) {
        tvTotalTrips = view.findViewById(R.id.tvTotalTrips)
        tvTotalTime = view.findViewById(R.id.tvTotalTime)
        //tvTimeContext = view.findViewById(R.id.tvTimeContext)
        tvCarbonSaved = view.findViewById(R.id.tvCarbonSaved)
        tvStationsVisited = view.findViewById(R.id.tvStationsVisited)
        progressStations = view.findViewById(R.id.progressStations)

        cardFrequentRoute = view.findViewById(R.id.cardFrequentRoute)
        tvFrequentRoute = view.findViewById(R.id.tvFrequentRoute)
        tvFrequentPattern = view.findViewById(R.id.tvFrequentPattern)

        btnQuickStart = view.findViewById(R.id.btnQuickStart)
        btnReturnJourney = view.findViewById(R.id.btnReturnJourney)

        rvTripHistory = view.findViewById(R.id.rvTripHistory)
        tvEmptyState = view.findViewById(R.id.tvEmptyState)
        progressLoading = view.findViewById(R.id.progressLoading)
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
        //tvTimeContext.text = TravelTimeContext.getContextMessage(stats.totalMinutes)
        tvCarbonSaved.text = viewModel.formatCarbonSavings(stats.carbonSavedKg)

        val progress = (stats.uniqueStationsVisited.toFloat() / stats.totalStationsInNetwork * 100).toInt()
        tvStationsVisited.text = "${stats.uniqueStationsVisited} / ${stats.totalStationsInNetwork}"
        progressStations.setProgressCompat(progress, true)
    }

    private fun updateFrequentRouteCard(state: DashboardUiState.Success) {
        val topRoute = state.frequentRoutes.first()

        cardFrequentRoute.visibility = View.VISIBLE
        tvFrequentRoute.text = "${topRoute.sourceStationName} → ${topRoute.destinationStationName}"

        val pattern = viewModel.formatTimePeriod(topRoute.commonDayOfWeek, topRoute.commonHourOfDay)
        tvFrequentPattern.text = "Usually on $pattern • ${topRoute.tripCount} trips"

    }
//
//    private fun startQuickJourney(source: String, destination: String) {
//        val intent = Intent(requireContext(), MainActivity::class.java).apply {
//            putExtra("QUICK_START_SOURCE", source)
//            putExtra("QUICK_START_DEST", destination)
//        }
//        startActivity(intent)
//    }


    private fun showError(message: String) {
        progressLoading.visibility = View.GONE
        tvEmptyState.visibility = View.VISIBLE
        tvEmptyState.text = "Error: $message"
    }
}