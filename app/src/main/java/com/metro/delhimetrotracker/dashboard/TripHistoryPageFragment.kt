package com.metro.delhimetrotracker.dashboard

import android.annotation.SuppressLint
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
import android.util.Log
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.metro.delhimetrotracker.MainActivity
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator

@Suppress("DEPRECATION")
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

    @SuppressLint("CutPasteId")
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

                    val trips = state.recentTrips   // 👈 single source of truth
                    Log.d(
                        "UI_VERIFY",
                        "Trips=${trips.size}, firstStations=${trips.firstOrNull()?.stationCount}"
                    )

                    adapter.submitList(trips)

                    val emptyView = view.findViewById<View>(R.id.tvEmptyState)
                    val rv = view.findViewById<View>(R.id.rvTripHistory)

                    val isEmpty = trips.isEmpty()
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
        (requireActivity() as? MainActivity)?.deleteTrip(trip.tripId)

        // Show Snackbar with Undo
        view?.let { v ->
            Snackbar.make(v, "Trip deleted", Snackbar.LENGTH_LONG)
                .setAction("UNDO") {
                    // ✅ Use MainActivity's restoreTrip for proper cloud sync
                    (requireActivity() as? MainActivity)?.restoreTrip(trip.tripId) {
                        // Trip restored and synced successfully
                    }
                }
                .setDuration(5000)  // ✅ Give users 5 seconds to undo
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