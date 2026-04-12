package tech.vvs.vvs_launcher

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsManager {
    private const val TAG = "AnalyticsManager"
    private var firebaseAnalytics: FirebaseAnalytics? = null

    fun logAppStart(context: Context, deviceId: String) {
        try {
            if (firebaseAnalytics == null) {
                firebaseAnalytics = FirebaseAnalytics.getInstance(context)
            }
            
            val bundle = Bundle().apply {
                putString("device_id", deviceId)
            }
            firebaseAnalytics?.logEvent("app_start", bundle)
            Log.d(TAG, "Logged app_start event for device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log app_start", e)
        }
    }

    fun logRegistration(context: Context, deviceId: String, type: String) {
         try {
            if (firebaseAnalytics == null) {
                firebaseAnalytics = FirebaseAnalytics.getInstance(context)
            }
            
            val bundle = Bundle().apply {
                putString("device_id", deviceId)
                putString("registration_type", type)
            }
            firebaseAnalytics?.logEvent("registration", bundle)
            Log.d(TAG, "Logged registration event for device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log registration", e)
        }
    }

    fun logDiscoveryFail(context: Context, deviceId: String, msg: String) {
        try {
            if (firebaseAnalytics == null) {
                firebaseAnalytics = FirebaseAnalytics.getInstance(context)
            }

            val bundle = Bundle().apply {
                putString("device_id", deviceId)
                putString("msg", msg)
            }
            firebaseAnalytics?.logEvent("DiscoveryFail", bundle)
            Log.d(TAG, "Logged DiscoveryFail event for device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log Discovery fail", e)
        }
    }
}
