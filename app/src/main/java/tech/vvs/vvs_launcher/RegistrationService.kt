package tech.vvs.vvs_launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject

object RegistrationService {
    private const val TAG = "RegistrationService"

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

        // Build JSON type payload with device info
        val json = JSONObject()
        try {
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

            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            json.put("app_version", pInfo.versionName)
        } catch (e: Exception) {
            Log.e(TAG, "Error building JSON info", e)
        }

        val jsonString = json.toString()
        val encodedType = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)

        val deviceId = DeviceManager.getOrCreateDeviceId(context)
        val deviceIdEncoded = Base64.encodeToString(deviceId.toByteArray(), Base64.NO_WRAP)
        val urlString = "http://$ip:$port/player/registerplayer?playerid=$deviceIdEncoded&type=$encodedType&room=$roomNumber"
        Log.d(TAG, "Registering player with ID $deviceId: $urlString")

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

                if (responseCode == 200) {
                    // Parse hotel name from response.
                    // IBS may return plain text (hotel name) or JSON {"hotel_name": "..."}
                    val hotelName = parseHotelName(responseText)
                    Log.d(TAG, "Parsed hotel name: $hotelName")

                    // Persist hotel name for HelloService pings
                    prefs.edit().putString("hotel_name", hotelName).apply()

                    // Write player data to Firestore
                    writeToFirestore(
                        context = context,
                        hotelName = hotelName,
                        deviceId = deviceId,
                        roomNumber = roomNumber,
                        ibsIp = ip,
                        ibsPort = port,
                        deviceInfo = json
                    )
                }

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

    /**
     * Parses the hotel name from the IBS registration response.
     * Handles two formats:
     *  - JSON: {"hotel_name": "Grand Hotel"} or {"hotel": "Grand Hotel"}
     *  - Plain text: "Grand Hotel"
     */
    private fun parseHotelName(response: String): String {
        val trimmed = response.trim()
        return try {
            val jsonObj = JSONObject(trimmed)
            jsonObj.optString("hotel_name")
                .ifEmpty { jsonObj.optString("hotel", trimmed) }
        } catch (e: Exception) {
            // Not JSON — treat the entire response text as the hotel name
            trimmed.ifEmpty { "Unknown Hotel" }
        }
    }

    /**
     * Writes hotel metadata and player record to Firestore.
     * Uses merge so existing fields (e.g. other players) are preserved.
     *
     * Structure:
     *   hotels/{hotelName}                    ← hotel doc
     *   hotels/{hotelName}/players/{deviceId} ← player doc
     */
    private fun writeToFirestore(
        context: Context,
        hotelName: String,
        deviceId: String,
        roomNumber: String,
        ibsIp: String,
        ibsPort: Int,
        deviceInfo: JSONObject
    ) {
        try {
            val db = FirebaseFirestore.getInstance()
            val timestamp = com.google.firebase.Timestamp.now()

            // --- Hotel document ---
            val hotelData = hashMapOf(
                "name" to hotelName,
                "ibs_ip" to ibsIp,
                "ibs_port" to ibsPort,
                "last_updated" to timestamp
            )
            db.collection("hotels")
                .document(hotelName)
                .set(hotelData, SetOptions.merge())
                .addOnSuccessListener { Log.d(TAG, "Hotel doc written: $hotelName") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to write hotel doc", e) }

            // --- Player document ---
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) { "unknown" }

            val playerData = hashMapOf(
                "device_id" to deviceId,
                "room" to roomNumber,
                "hotel_name" to hotelName,
                "ibs_ip" to ibsIp,
                "ibs_port" to ibsPort,
                "is_alive" to true,
                "last_seen" to timestamp,
                "last_registration" to timestamp,
                "app_version" to appVersion,
                "model" to deviceInfo.optString("model", ""),
                "manufacturer" to deviceInfo.optString("manufacturer", ""),
                "brand" to deviceInfo.optString("brand", ""),
                "sdk" to deviceInfo.optInt("sdk", 0),
                "release" to deviceInfo.optString("release", "")
            )
            db.collection("hotels")
                .document(hotelName)
                .collection("players")
                .document(deviceId)
                .set(playerData, SetOptions.merge())
                .addOnSuccessListener { Log.d(TAG, "Player doc written: $deviceId in $hotelName") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to write player doc", e) }

        } catch (e: Exception) {
            Log.e(TAG, "Firestore write failed", e)
        }
    }
}
