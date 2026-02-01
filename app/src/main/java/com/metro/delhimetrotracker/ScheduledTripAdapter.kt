package com.metro.delhimetrotracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip

class ScheduledTripAdapter(
    private val onStartClick: (ScheduledTrip) -> Unit, // Clicking the card starts the trip
    private val onEdit: (ScheduledTrip) -> Unit,       // Clicking "Edit" button
    private val onDelete: (ScheduledTrip) -> Unit      // Clicking "Delete" button
) : RecyclerView.Adapter<ScheduledTripAdapter.ViewHolder>() {

    private var trips = listOf<ScheduledTrip>()

    fun submitList(newTrips: List<ScheduledTrip>) {
        trips = newTrips
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scheduled_trip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val trip = trips[position]

        // 1. Format Time
        val hour12 = if (trip.scheduledTimeHour % 12 == 0) 12 else trip.scheduledTimeHour % 12
        val amPm = if (trip.scheduledTimeHour >= 12) "PM" else "AM"
        holder.tvTime.text = String.format("%d:%02d %s", hour12, trip.scheduledTimeMinute, amPm)

        // 2. Route
        holder.tvRoute.text = "${trip.sourceStationName} → ${trip.destinationStationName}"

        // 3. Frequency
        holder.tvType.text = if (trip.isRecurring) {
            trip.recurringDays ?: "Recurring"
        } else {
            "One-time"
        }

        // 4. Reminder Info
        val reminderText = when {
            trip.reminderMinutesBefore >= 60 -> "${trip.reminderMinutesBefore / 60} hour before"
            else -> "${trip.reminderMinutesBefore} min before"
        }
        holder.tvReminder.text = "⏰ Reminder: $reminderText"

        // 5. Click Listeners

        // Start Trip (Clicking the whole card)
        holder.itemView.setOnClickListener { onStartClick(trip) }

    }

    override fun getItemCount() = trips.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvScheduledTime)
        val tvRoute: TextView = view.findViewById(R.id.tvScheduledRoute)
        val tvType: TextView = view.findViewById(R.id.tvScheduleType)
        val tvReminder: TextView = view.findViewById(R.id.tvReminderInfo)

    }
}