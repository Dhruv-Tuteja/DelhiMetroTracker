//package com.metro.delhimetrotracker.ui
//
//import android.content.Intent
//import android.os.Bundle
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import androidx.fragment.app.Fragment
//import com.google.android.material.card.MaterialCardView
//import com.metro.delhimetrotracker.R
//
//class AccountFragment : Fragment() {
//
//    private lateinit var cardSettings: MaterialCardView
//    private lateinit var cardAppInfo: MaterialCardView
//    private lateinit var cardPrivacyPolicy: MaterialCardView
//
//    override fun onCreateView(
//        inflater: LayoutInflater,
//        container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View? {
//        val view = inflater.inflate(R.layout.fragment_account, container, false)
//
//        // Initialize views
//        cardSettings = view.findViewById(R.id.cardSettings)
//        cardAppInfo = view.findViewById(R.id.cardAppInfo)
//        cardPrivacyPolicy = view.findViewById(R.id.cardPrivacyPolicy)
//
//        // Set click listeners
//        setupClickListeners()
//
//        return view
//    }
//
//    private fun setupClickListeners() {
//        cardSettings.setOnClickListener {
//            // Open Settings Activity
//            val intent = Intent(requireContext(), SettingsActivity::class.java)
//            startActivity(intent)
//        }
//
//        cardAppInfo.setOnClickListener {
//            // Open App Guide Activity
//            // TODO: Replace with your actual App Guide activity
//            // val intent = Intent(requireContext(), AppGuideActivity::class.java)
//            // startActivity(intent)
//        }
//
//        cardPrivacyPolicy.setOnClickListener {
//            // Open Privacy Policy Activity (future feature)
//            // TODO: Implement privacy policy screen
//        }
//    }
//}