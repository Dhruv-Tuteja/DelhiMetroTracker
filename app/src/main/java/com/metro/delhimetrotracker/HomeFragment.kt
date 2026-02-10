//package com.metro.delhimetrotracker.ui
//
//import android.content.Intent
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.fragment.app.Fragment
//import androidx.lifecycle.lifecycleScope
//import com.google.android.material.button.MaterialButton
//import com.metro.delhimetrotracker.R
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import com.metro.delhimetrotracker.data.local.database.AppDatabase
//
//class HomeFragment : Fragment() {
//
//    private lateinit var btnStartJourney: MaterialButton
//    private lateinit var database: AppDatabase
//
//    override fun onCreateView(
//        inflater: LayoutInflater,
//        container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View? {
//        val view = inflater.inflate(R.layout.fragment_home, container, false)
//
//        // Initialize views
//        btnStartJourney = view.findViewById(R.id.btnStartJourney)
//
//        // Initialize database
//        database = AppDatabase.getDatabase(requireContext())
//
//        // Update button text based on active trip status
//        updateStartJourneyButton()
//
//        // Set click listener
//        btnStartJourney.setOnClickListener {
//            handleStartJourneyClick()
//        }
//
//        return view
//    }
//
//    override fun onResume() {
//        super.onResume()
//        // Update button every time fragment becomes visible
//        updateStartJourneyButton()
//    }
//
//    private fun updateStartJourneyButton() {
//        lifecycleScope.launch(Dispatchers.IO) {
//            val activeTrip = database.tripDao().getActiveTrip()
//
//            withContext(Dispatchers.Main) {
//                if (activeTrip != null) {
//                    // Active trip exists - change to "Go to Active Trip"
//                    btnStartJourney.text = "Go to Active Trip"
//                } else {
//                    // No active trip - show "Start Your Journey"
//                    btnStartJourney.text = "Start Your Journey"
//                }
//            }
//        }
//    }
//
//    private fun handleStartJourneyClick() {
//        lifecycleScope.launch(Dispatchers.IO) {
//            val activeTrip = database.tripDao().getActiveTrip()
//
//            withContext(Dispatchers.Main) {
//                if (activeTrip != null) {
//                    // Open existing active trip
//                    val intent = Intent(requireContext(), TrackingActivity::class.java)
//                    intent.putExtra("EXTRA_TRIP_ID", activeTrip.id)
//                    startActivity(intent)
//                } else {
//                    // Start new journey - open station selector or tracking activity
//                    // TODO: Replace this with your actual start journey logic
//                    // Example: Open station selector dialog or directly start tracking
//                    startNewJourney()
//                }
//            }
//        }
//    }
//
//    private fun startNewJourney() {
//        // TODO: Implement your journey start logic here
//        // This could be:
//        // 1. Show station selector dialog
//        // 2. Directly open TrackingActivity
//        // 3. Whatever your app currently does
//
//        // Example - if you have a station selector:
//        // val dialog = StationSelectorDialog()
//        // dialog.show(parentFragmentManager, "StationSelector")
//
//        // OR directly open tracking:
//        val intent = Intent(requireContext(), TrackingActivity::class.java)
//        startActivity(intent)
//    }
//}