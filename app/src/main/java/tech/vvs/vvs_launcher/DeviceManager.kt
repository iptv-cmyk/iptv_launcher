package tech.vvs.vvs_launcher

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID

object DeviceManager {
    private const val PREF_NAME = "vvs_prefs"
    private const val PREF_DEVICE_ID = "device_id"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        // Try to get existing ID from prefs
        var deviceId = prefs.getString(PREF_DEVICE_ID, null)
        
        if (deviceId.isNullOrEmpty()) {
            // Get Android ID
            try {
                deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                Log.d("DeviceManager", "got Android ID from Settings.Secure: $deviceId")


            } catch (e: Exception) {
                // Fallback if something goes wrong
                e.printStackTrace()
            }

            // Fallback to UUID if Android ID is null or empty (e.g. some emulators)
            if (deviceId.isNullOrEmpty()) {
                deviceId = UUID.randomUUID().toString().replace("-", "")
                Log.w("DeviceManager", "got Android ID from UUID: $deviceId")
            }
            
            // Save to prefs
            prefs.edit().putString(PREF_DEVICE_ID, deviceId).apply()
        }
        
        return deviceId!!
    }

    fun saveDeviceInfo(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        editor.putInt("device_sdk_version", android.os.Build.VERSION.SDK_INT)
        editor.putString("device_release", android.os.Build.VERSION.RELEASE)
        editor.putString("device_model", android.os.Build.MODEL)
        editor.putString("device_manufacturer", android.os.Build.MANUFACTURER)
        editor.putString("device_product", android.os.Build.PRODUCT)
        editor.putString("device_hardware", android.os.Build.HARDWARE)
        editor.putString("device_board", android.os.Build.BOARD)
        editor.putString("device_brand", android.os.Build.BRAND)
        editor.putString("device_display", android.os.Build.DISPLAY)
        editor.putString("device_fingerprint", android.os.Build.FINGERPRINT)
        
        editor.apply()
        
        // Log what we saved for debugging
        android.util.Log.d("DeviceManager", "Device info saved: Model=${android.os.Build.MODEL}, SDK=${android.os.Build.VERSION.SDK_INT}")
    }
}
