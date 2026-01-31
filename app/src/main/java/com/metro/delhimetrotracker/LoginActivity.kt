package com.metro.delhimetrotracker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    // UI Elements
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnAction: MaterialButton
    private lateinit var imgProfile: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Init Firebase & Google
        auth = FirebaseAuth.getInstance()
        setupGoogleSignIn()

        // Init Views
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnAction = findViewById(R.id.btnAction)
        imgProfile = findViewById(R.id.imgProfile)

        // Close/Cancel Buttons
        findViewById<ImageView>(R.id.btnClose).setOnClickListener { finishWithAnimation() }
        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { finishWithAnimation() }

        // Update UI based on current state
        updateUI()
    }

    private fun updateUI() {
        val user = auth.currentUser
        if (user != null) {
            // --- STATE: SIGNED IN ---
            tvTitle.text = user.displayName ?: "User"
            tvSubtitle.text = user.email
            btnAction.text = "Sign Out"
            btnAction.setBackgroundColor(getColor(android.R.color.holo_red_dark))

            // Logic for Sign Out
            btnAction.setOnClickListener { performSignOut() }
        } else {
            // --- STATE: SIGNED OUT ---
            tvTitle.text = "Not Signed In"
            tvSubtitle.text = "Sign in to sync your trips and preferences."
            btnAction.text = "Sign In with Google"
// Use your existing blue color
            btnAction.setBackgroundColor(getColor(R.color.accent_blue))
            // Logic for Sign In
            btnAction.setOnClickListener { launchGoogleSignIn() }
        }
    }

    // --- SIGN IN LOGIC ---
    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account.idToken!!)
        } catch (e: ApiException) {
            Toast.makeText(this, "Google Sign-In failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGoogleSignIn() {
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Welcome back!", Toast.LENGTH_SHORT).show()
                    updateUI() // Refresh the page to show "Sign Out" button
                } else {
                    Toast.makeText(this, "Authentication Failed.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // --- SIGN OUT LOGIC ---
    private fun performSignOut() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Clear Local DB
            val db = (application as MetroTrackerApplication).database
            db.tripDao().deleteAllTrips()
            getSharedPreferences("MetroPrefs", Context.MODE_PRIVATE).edit().clear().apply()

            withContext(Dispatchers.Main) {
                // 2. Clear Cloud Auth
                auth.signOut()
                googleSignInClient.signOut()
                Toast.makeText(this@LoginActivity, "Signed Out", Toast.LENGTH_SHORT).show()
                updateUI() // Refresh page to show "Sign In" button
            }
        }
    }

    private fun setupGoogleSignIn() {
        // COPY THIS FROM YOUR MAIN ACTIVITY if it differs
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun finishWithAnimation() {
        finish()
        // Slide down animation when closing
        overridePendingTransition(0, R.anim.slide_out_bottom)
    }

    // Handle Back Button press too
    override fun onBackPressed() {
        super.onBackPressed()
        finishWithAnimation()
    }
}