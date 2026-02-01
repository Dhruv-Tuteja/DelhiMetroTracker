package com.metro.delhimetrotracker.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.model.TripCardData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.metro.delhimetrotracker.data.model.DashboardUiState
import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.metro.delhimetrotracker.MetroTrackerApplication
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator

class TripHistoryPageFragment : Fragment(), TripHistoryAdapter.OnTripDoubleTapListener {

    private lateinit var viewModel: DashboardViewModel
    private lateinit var adapter: TripHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_trip_history_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireParentFragment())[DashboardViewModel::class.java]

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvTripHistory)
        adapter = TripHistoryAdapter(this)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Setup swipe-to-delete with undo
        setupSwipeToDelete(recyclerView)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                if (state is DashboardUiState.Success) {
                    // Fetch line colors for each trip
                    val tripsWithColors = state.recentTrips.map { trip ->
                        val db = (requireActivity().application as MetroTrackerApplication).database
                        val lineColors = withContext(Dispatchers.IO) {
                            val fullTrip = db.tripDao().getTripById(trip.tripId)
                            fullTrip?.visitedStations?.mapNotNull { stationId ->
                                db.metroStationDao().getStationById(stationId)?.lineColor
                            }?.distinct() ?: emptyList()
                        }
                        trip.copy(lineColors = lineColors)
                    }

                    adapter.submitList(tripsWithColors)

                    val emptyView = view.findViewById<View>(R.id.tvEmptyState)
                    val rv = view.findViewById<View>(R.id.rvTripHistory)

                    val isEmpty = tripsWithColors.isEmpty()
                    emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    rv?.visibility = if (isEmpty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun setupSwipeToDelete(recyclerView: RecyclerView) {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val trip = adapter.currentList[position]

                deleteTripWithUndo(trip)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                RecyclerViewSwipeDecorator.Builder(
                    c,
                    recyclerView,
                    viewHolder,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
                    .addSwipeLeftBackgroundColor(
                        ContextCompat.getColor(recyclerView.context, android.R.color.holo_red_dark)
                    )
                    .addSwipeLeftActionIcon(R.drawable.ic_delete)
                    .addSwipeRightBackgroundColor(
                        ContextCompat.getColor(recyclerView.context, android.R.color.holo_red_dark)
                    )
                    .addSwipeRightActionIcon(R.drawable.ic_delete)
                    .create()
                    .decorate()

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })

        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun deleteTripWithUndo(trip: TripCardData) {
        // Call MainActivity's deleteTrip which handles sync
        (requireActivity() as? com.metro.delhimetrotracker.ui.MainActivity)?.deleteTrip(trip.tripId)

        // Show Snackbar with Undo
        view?.let { v ->
            Snackbar.make(v, "Trip deleted", Snackbar.LENGTH_LONG)
                .setAction("UNDO") {
                    // Restore trip
                    lifecycleScope.launch {
                        val db = (requireActivity().application as MetroTrackerApplication).database
                        db.tripDao().restoreTrip(trip.tripId)

                        // Sync the restoration to cloud
                        (requireActivity() as? com.metro.delhimetrotracker.ui.MainActivity)?.performManualSync {
                            // Refresh done
                        }

                        Toast.makeText(context, "Trip restored", Toast.LENGTH_SHORT).show()
                    }
                }
                .setDuration(4000)
                .show()
        }
    }

    override fun onTripDoubleTap(source: String, destination: String) {
        (parentFragment as? DashboardFragment)?.onTripDoubleTap(source, destination)
    }

    override fun onTripLongPress(trip: TripCardData) {
        (parentFragment as? DashboardFragment)?.onTripLongPress(trip)
    }
}