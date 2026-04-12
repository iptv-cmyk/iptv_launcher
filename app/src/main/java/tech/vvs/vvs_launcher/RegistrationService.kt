package tech.vvs.vvs_launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import org.json.JSONObject

object RegistrationService {
    private const val TAG = "RegistrationService"
    // Hardcoded as per requirements
    // private const val PLAYER_ID = "cGxheWVyMDAwMQ=="

    fun register(context: Context, roomNumber: String) {
        val prefs = context.getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("ibs_ip", null)
        val port = prefs.getInt("ibs_port", -1)

        if (ip.isNullOrEmpty() || port <= 0) {
            Log.e(TAG, "Cannot register: IBS IP/Port not set")
            val deviceId = DeviceManager.getOrCreateDeviceId(context)
            AnalyticsManager.logRegistration(context, deviceId, "registration failed no ibs ip")
            return
        }

        // Create JSON payload for TYPE
        val json = JSONObject()
        try {
            // Add Device Info
            json.put("sdk", prefs.getInt("device_sdk_version", 0))
            json.put("release", prefs.getString("device_release", ""))
            json.put("model", prefs.getString("device_model", ""))
            json.put("manufacturer", prefs.getString("device_manufacturer", ""))
            json.put("product", prefs.getString("device_product", ""))
            json.put("hardware", prefs.getString("device_hardware", ""))
            json.put("board", prefs.getString("device_board", ""))
            json.put("brand", prefs.getString("device_brand", ""))
            json.put("display", prefs.getString("device_display", ""))
            json.put("fingerprint", prefs.getString("device_fingerprint", ""))
            
            // Add App Version
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = pInfo.versionName
            json.put("app_version", version)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error building JSON info", e)
        }
        
        val jsonString = json.toString()
        Log.d(TAG, "Registration Info JSON: $jsonString")
        
        val encodedType = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)

        // URL format: /player/registerplayer?playerid=...&type=...&room=...
        val deviceId = DeviceManager.getOrCreateDeviceId(context)
        val deviceIdEncoded = Base64.encodeToString(deviceId.toByteArray(), Base64.NO_WRAP)
        val urlString = "http://$ip:$port/player/registerplayer?playerid=$deviceIdEncoded&type=$encodedType&room=$roomNumber"
        Log.d(TAG, "Registering player with ID $deviceId: $urlString")
        
        // Log Analytics Event
        AnalyticsManager.logRegistration(context, deviceId, "regular")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val responseCode = connection.responseCode
                Log.d(TAG, "Registration response code: $responseCode")

                val responseText = try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "Error reading response: ${e.message}"
                }

                connection.disconnect()
                Log.d(TAG, "Registration response text: $responseText")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Registration: $responseText", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering player", e)
                AnalyticsManager.logRegistration(context, deviceId, "registration failed")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Registration Failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
