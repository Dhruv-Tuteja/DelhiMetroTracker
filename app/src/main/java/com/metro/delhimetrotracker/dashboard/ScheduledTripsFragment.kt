package com.metro.delhimetrotracker.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.metro.delhimetrotracker.MainActivity
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import com.metro.delhimetrotracker.ScheduledTripAlarmManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.lifecycle.ViewModelProvider

class ScheduledTripsPageFragment : Fragment() {

    private lateinit var adapter: ScheduledTripAdapter
    private lateinit var emptyView: TextView
    private lateinit var viewModel: DashboardViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_scheduled_trips_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireParentFragment())[DashboardViewModel::class.java]

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvScheduledTrips)
        emptyView = view.findViewById(R.id.tvEmptyScheduled)

        adapter = ScheduledTripAdapter(
            onDoubleTap = { trip ->
                (activity as? MainActivity)?.showStationSelectionDialog(
                    trip.sourceStationName,
                    trip.destinationStationName
                )
            },
            onDelete = { trip ->
                deleteTripWithUndo(trip)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        adapter.getSwipeHelper().attachToRecyclerView(recyclerView)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scheduledTrips.collectLatest { trips ->
                val upcomingTrips = filterUpcomingTrips(trips)

                if (upcomingTrips.isNotEmpty()) {
                    // Fetch line colors for each trip
                    val db = (requireActivity().application as MetroTrackerApplication).database
                    val tripsWithColors = upcomingTrips.map { trip ->
                        val lineColors = withContext(Dispatchers.IO) {
                            val sourceStation = db.metroStationDao().getStationById(trip.sourceStationId)
                            val destStation = db.metroStationDao().getStationById(trip.destinationStationId)

                            val colors = mutableSetOf<String>()
                            sourceStation?.let { colors.add(it.lineColor) }
                            destStation?.let { colors.add(it.lineColor) }
                            colors.toList()
                        }
                        trip to lineColors
                    }

                    adapter.setTripsWithColors(tripsWithColors)
                    emptyView.visibility = View.GONE
                } else {
                    adapter.setTrips(emptyList())
                    emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun filterUpcomingTrips(trips: List<ScheduledTrip>): List<ScheduledTrip> {
        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return trips.filter { trip ->
            if (trip.isRecurring) {
                // For recurring trips: always show them (they recur weekly)
                // Just filter out if they're not active
                trip.isActive
            } else {
                // For one-time trips: check if scheduledDate is in the future
                val tripDate = trip.scheduledDate ?: return@filter false
                tripDate >= todayStart // Show if it's today or in the future
            }
        }.sortedWith(compareBy(
            // Sort by: is it today? then by time
            { trip ->
                if (trip.isRecurring) {
                    val days = trip.recurringDays?.split(",") ?: emptyList()
                    val dayString = when (currentDay) {
                        Calendar.SUNDAY -> "SUN"
                        Calendar.MONDAY -> "MON"
                        Calendar.TUESDAY -> "TUE"
                        Calendar.WEDNESDAY -> "WED"
                        Calendar.THURSDAY -> "THU"
                        Calendar.FRIDAY -> "FRI"
                        Calendar.SATURDAY -> "SAT"
                        else -> ""
                    }
                    val tripMinutes = trip.scheduledTimeHour * 60 + trip.scheduledTimeMinute
                    // Prioritize: today's trips that haven't passed yet
                    when (dayString) {
                        in days if tripMinutes > currentMinutes -> {
                            0 // Today, upcoming
                        }
                        in days -> {
                            2 // Today, but passed
                        }
                        else -> {
                            1 // Other days
                        }
                    }
                } else {
                    val tripDate = trip.scheduledDate ?: Long.MAX_VALUE
                    if (tripDate >= todayStart && tripDate < todayStart + 86400000) {
                        val tripMinutes = trip.scheduledTimeHour * 60 + trip.scheduledTimeMinute
                        if (tripMinutes > currentMinutes) 0 else 2 // Today's trips
                    } else {
                        1 // Future trips
                    }
                }
            },
            { it.scheduledTimeHour * 60 + it.scheduledTimeMinute }
        ))
    }

    private fun deleteTripWithUndo(trip: ScheduledTrip) {
        // Call MainActivity's deleteScheduledTrip which handles sync
        (requireActivity() as? MainActivity)?.deleteScheduledTrip(trip.id)

        // Show Snackbar with Undo
        view?.let { v ->
            Snackbar.make(v, "Scheduled trip deleted", Snackbar.LENGTH_LONG)
                .setAction("UNDO") {
                    // Restore trip
                    lifecycleScope.launch {
                        val db = (requireActivity().application as MetroTrackerApplication).database

                        // Restore the trip (mark as not deleted)
                        val restoredTrip = trip.copy(
                            isDeleted = false,
                            isActive = true,
                            syncState = "PENDING",
                            lastModified = System.currentTimeMillis()
                        )
                        db.scheduledTripDao().update(restoredTrip)

                        // Re-schedule the alarm
                        ScheduledTripAlarmManager.scheduleTrip(requireContext(), restoredTrip)

                        // Sync the restoration to cloud
                        (requireActivity() as? MainActivity)?.performManualSync {}

                        Toast.makeText(context, "Trip restored", Toast.LENGTH_SHORT).show()
                    }
                }
                .setDuration(4000)
                .show()
        }
    }
}