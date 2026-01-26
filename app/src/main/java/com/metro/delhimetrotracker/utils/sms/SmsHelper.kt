package com.metro.delhimetrotracker.utils.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class SmsHelper(private val context: Context) {

    companion object {
        private const val TAG = "SmsHelper"
        const val ACTION_SMS_SENT = "SMS_SENT"
        const val ACTION_SMS_DELIVERED = "SMS_DELIVERED"
        private val dateFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    }

    /**
     * Retrieves the appropriate SmsManager instance based on Android version.
     * Note: Android 12 (API 31) deprecated getDefault() in favor of getSystemService.
     */
    private fun getSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /**
     * Sends a generic custom text message.
     */
    fun sendSms(phoneNumber: String, message: String) {
        if (phoneNumber.isBlank()) return
        sendSmsInternal(phoneNumber, message)
    }

    /**
     * Formats and sends a station-reached update.
     */
    fun sendStationAlert(
        phoneNumber: String,
        stationName: String,
        stationOrder: Int,
        totalStations: Int,
        nextStationName: String?,
        isDestination: Boolean = false
    ): Boolean {
        if (phoneNumber.isBlank()) return false
        return try {
            val time = dateFormat.format(Date())
            val message = if (isDestination) {
                "🎯 Destination Reached!\nStation: $stationName\nTime: $time"
            } else {
                "📍 Station Update ($stationOrder/$totalStations)\nCurrent: $stationName\nTime: $time${nextStationName?.let { "\nNext: $it" } ?: ""}"
            }
            sendSmsInternal(phoneNumber, message)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send station alert", e)
            false
        }
    }

    /**
     * Sends a notification when the journey officially begins.
     */
    fun sendJourneyStartAlert(
        phoneNumber: String,
        source: String,
        dest: String,
        estDuration: Int
    ): Boolean {
        if (phoneNumber.isBlank()) return false
        val time = dateFormat.format(Date())
        val message = "🚇 Metro Journey Started\nFrom: $source\nTo: $dest\nEst: $estDuration mins\nStarted: $time"
        return try {
            sendSmsInternal(phoneNumber, message)
            true
        } catch (e: Exception) { false }
    }

    /**
     * Core internal logic for formatting numbers and dispatching SMS.
     */
    private fun sendSmsInternal(phoneNumber: String, message: String) {
        // 1. Verify Permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denied for SEND_SMS")
            return
        }

        try {
            // 2. Clean and format the phone number
            var formattedNumber = phoneNumber.replace("\\s".toRegex(), "")
            if (formattedNumber.length == 10 && !formattedNumber.startsWith("+")) {
                formattedNumber = "+91$formattedNumber" // Defaulting to India code
            }

            val smsManager = getSmsManager()

            // 3. Create Intent for delivery tracking (Optional tracking)
            val sentIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_SMS_SENT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 4. Handle long messages (Standard SMS limit is 160 characters)
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(formattedNumber, null, message, sentIntent, null)
            } else {
                val sentIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sentIntent) } }
                smsManager.sendMultipartTextMessage(formattedNumber, null, parts, sentIntents, null)
            }
            Log.d(TAG, "SMS attempt successful: $formattedNumber")
        } catch (e: Exception) {
            Log.e(TAG, "SMS Dispatch Failure: ${e.message}")
        }
    }
}