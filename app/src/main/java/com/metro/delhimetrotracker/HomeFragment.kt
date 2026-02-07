package com.metro.delhimetrotracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import com.metro.delhimetrotracker.R
import android.content.Context
import com.metro.delhimetrotracker.service.JourneyTrackingService

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var mainActionButton: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainActionButton = view.findViewById(R.id.btnMainAction)
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    private fun updateButtonState() {
        if (isTripActive()) {
            mainActionButton.text = "Go to Active Trip"
            mainActionButton.setOnClickListener {
                startActivity(
                    Intent(requireContext(), TrackingActivity::class.java)
                )
            }
        } else {
            mainActionButton.text = "Start Your Journey"
            mainActionButton.setOnClickListener {
                (activity as MainActivity).openStationSelector()
            }
        }
    }
    private fun isTripActive(): Boolean {
        return requireContext()
            .getSharedPreferences("trip_state", Context.MODE_PRIVATE)
            .getBoolean("is_trip_active", false)
    }

}
