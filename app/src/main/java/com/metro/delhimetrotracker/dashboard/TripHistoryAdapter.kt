package com.metro.delhimetrotracker.ui.dashboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.model.TripCardData
import java.text.SimpleDateFormat
import java.util.*
import android.view.GestureDetector
import android.view.MotionEvent
import com.google.android.material.card.MaterialCardView


class TripHistoryAdapter(private val doubleTapListener: OnTripDoubleTapListener) : ListAdapter<TripCardData, TripHistoryAdapter.TripViewHolder>(TripDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip_card, parent, false)
        return TripViewHolder(view)
    }
    interface OnTripDoubleTapListener {
        fun onTripDoubleTap(source: String, destination: String)
        fun onTripShare(trip: TripCardData)
    }
    override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    interface TripItemListener {
        fun onTripDoubleTap(source: String, destination: String)
        fun onTripShare(trip: TripCardData) // New method for sharing
    }



    inner class TripViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)

        private val tvStationCount: TextView = itemView.findViewById(R.id.tvStationCount)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tvDateTime)
        private val tvDelayBadge: TextView = itemView.findViewById(R.id.tvDelayBadge)
        private val viewLineIndicator: View = itemView.findViewById(R.id.viewLineIndicator)

        private val dateFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

        fun bind(trip: TripCardData) {
            itemView.alpha = 1.0f
            itemView.translationX = 0f

            // Get reference to the card
            val card = itemView as MaterialCardView

            if (trip.hadSosAlert == true) {
                card.setCardBackgroundColor(Color.parseColor("#4D1F1F")) // Dark red tint
                itemView.findViewById<View>(R.id.sosIndicator)?.visibility = View.VISIBLE
            } else {
                card.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.background_card))
                itemView.findViewById<View>(R.id.sosIndicator)?.visibility = View.GONE
            }

            tvSource.text = "${trip.sourceStationName} → ${trip.destinationStationName}"
            tvDuration.text = formatDuration(trip.durationMinutes)
            tvStationCount.text = "${trip.stationCount} stations"
            tvDateTime.text = dateFormat.format(trip.startTime)

            // Show SOS badge if triggered
            if (trip.hadSosAlert == true && trip.sosStationName != null) {
                tvDelayBadge.visibility = View.VISIBLE
                tvDelayBadge.text = "🆘 SOS at ${trip.sosStationName}"
                tvDelayBadge.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                )
                tvDelayBadge.setTextColor(Color.WHITE)
            } else if (trip.isDelayed) {
                tvDelayBadge.visibility = View.VISIBLE
                tvDelayBadge.text = "+${trip.delayMinutes}m delay"
                tvDelayBadge.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.delay_warning)
                )
            } else {
                tvDelayBadge.visibility = View.GONE
            }

            if (trip.isDelayed) {
                tvDelayBadge.visibility = View.VISIBLE
                tvDelayBadge.text = "+${trip.delayMinutes}m delay"
                tvDelayBadge.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.delay_warning)
                )
            } else {
                tvDelayBadge.visibility = View.GONE
            }
            val gestureDetector = GestureDetector(itemView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    doubleTapListener.onTripDoubleTap(trip.sourceStationName, trip.destinationStationName)
                    return true
                }
            })
            itemView.setOnLongClickListener {
                doubleTapListener.onTripShare(trip)
                true // This tells Android we "consumed" the click
            }
            itemView.setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
            setLineColorIndicator(trip.lineColors)
        }

        private fun setLineColorIndicator(colors: List<String>) {
            if (colors.isEmpty()) {
                viewLineIndicator.setBackgroundColor(Color.GRAY)
                return
            }

            if (colors.size == 1) {
                viewLineIndicator.setBackgroundColor(parseColor(colors[0]))
            } else {
                val gradientColors = colors.map { parseColor(it) }.toIntArray()
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    gradientColors
                )
                gradient.cornerRadius = 8f
                viewLineIndicator.background = gradient
            }
        }

        private fun parseColor(colorString: String): Int {
            return try {
                Color.parseColor(colorString)
            } catch (e: Exception) {
                Color.GRAY
            }
        }

        private fun formatDuration(minutes: Int): String {
            val hours = minutes / 60
            val mins = minutes % 60
            return when {
                hours == 0 -> "${mins}m"
                mins == 0 -> "${hours}h"
                else -> "${hours}h ${mins}m"
            }
        }
    }
}

class TripDiffCallback : DiffUtil.ItemCallback<TripCardData>() {
    override fun areItemsTheSame(oldItem: TripCardData, newItem: TripCardData): Boolean {
        return oldItem.tripId == newItem.tripId
    }

    override fun areContentsTheSame(oldItem: TripCardData, newItem: TripCardData): Boolean {
        return oldItem == newItem
    }
}