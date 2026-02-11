package com.metro.delhimetrotracker.dashboard

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator
import androidx.core.graphics.toColorInt

@Suppress("DEPRECATION")
class ScheduledTripAdapter(
    private val onDoubleTap: (ScheduledTrip) -> Unit,
    private val onDelete: (ScheduledTrip) -> Unit
) : RecyclerView.Adapter<ScheduledTripAdapter.ViewHolder>() {

    private var trips: List<Pair<ScheduledTrip, List<String>>> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun setTrips(scheduledTrips: List<ScheduledTrip>) {
        this.trips = scheduledTrips.map { it to emptyList() } // Default empty colors
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setTripsWithColors(tripsWithColors: List<Pair<ScheduledTrip, List<String>>>) {
        this.trips = tripsWithColors
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = trips.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scheduled_trip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (trip, colors) = trips[position]
        holder.bind(trip, colors)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvScheduledTime)
        private val tvRoute: TextView = itemView.findViewById(R.id.tvScheduledRoute)
        private val tvType: TextView = itemView.findViewById(R.id.tvScheduleType)
        private val tvReminder: TextView = itemView.findViewById(R.id.tvReminderInfo)

        @SuppressLint("DefaultLocale", "SetTextI18n", "ClickableViewAccessibility")
        fun bind(trip: ScheduledTrip, lineColors: List<String>) {
            val hour12 = if (trip.scheduledTimeHour % 12 == 0) 12 else trip.scheduledTimeHour % 12
            val amPm = if (trip.scheduledTimeHour >= 12) "PM" else "AM"
            tvTime.text = String.format("%d:%02d %s", hour12, trip.scheduledTimeMinute, amPm)

            tvRoute.text = "${trip.sourceStationName} → ${trip.destinationStationName}"

            tvType.text = if (trip.isRecurring) {
                trip.recurringDays ?: "Daily"
            } else {
                "One-time"
            }

            val reminderText = when {
                trip.reminderMinutesBefore >= 60 -> "${trip.reminderMinutesBefore / 60} hour before"
                else -> "${trip.reminderMinutesBefore} min before"
            }
            tvReminder.text = "⏰ $reminderText"

            val viewLineIndicator = itemView.findViewById<View>(R.id.viewLineIndicator)
            setLineColorIndicator(viewLineIndicator, lineColors)

            val gestureDetector = GestureDetector(itemView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onDoubleTap(trip)
                    return true
                }
            })

            itemView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        private fun setLineColorIndicator(view: View, colors: List<String>) {
            if (colors.isEmpty()) {
                view.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.accent_blue))
                return
            }

            if (colors.size == 1) {
                view.setBackgroundColor(parseColor(colors[0]))
            } else {
                val gradientColors = colors.map { parseColor(it) }.toIntArray()
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    gradientColors
                )
                gradient.cornerRadius = 8f
                view.background = gradient
            }
        }

        private fun parseColor(colorString: String): Int {
            return try {
                colorString.toColorInt()
            } catch (_: Exception) {
                Color.GRAY
            }
        }
    }

    fun getSwipeHelper(): ItemTouchHelper {
        return ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
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
                if (position >= 0 && position < trips.size) {
                    onDelete(trips[position].first)

                }
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
    }
}