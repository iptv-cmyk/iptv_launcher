package tech.vvs.vvs_launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dadb.Dadb
import dadb.AdbKeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore

class NetflixResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "NetflixResetReceiver triggered")

        CoroutineScope(Dispatchers.IO).launch {
            val prefs     = context.getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
            val hotelName = prefs.getString("hotel_name", null)
            val deviceId  = DeviceManager.getOrCreateDeviceId(context)
            val now       = Timestamp.now()

            // Record the attempt time immediately
            writeNetflixStatus(hotelName, deviceId, mapOf("netflix_reset_last_attempt" to now))

            try {
                val keyFile    = File(context.filesDir, "adbkey")
                val pubKeyFile = File(context.filesDir, "adbkey.pub")

                val keyPair = if (keyFile.exists() && pubKeyFile.exists()) {
                    AdbKeyPair.read(keyFile, pubKeyFile)
                } else {
                    AdbKeyPair.generate(privateKeyFile = keyFile, publicKeyFile = pubKeyFile)
                    AdbKeyPair.read(keyFile, pubKeyFile)
                }

                Log.d(TAG, "Connecting to local ADB...")
                val adb = Dadb.create("127.0.0.1", 5555, keyPair)

                Log.d(TAG, "Testing ADB connection...")
                val testResponse = adb.shell("echo 'ADB Connection OK'")
                Log.d(TAG, "Test Output: ${testResponse.output} ExitCode: ${testResponse.exitCode}")

                Log.d(TAG, "Executing: pm clear com.netflix.ninja")
                val response = adb.shell("pm clear com.netflix.ninja")
                Log.d(TAG, "Reset Output: '${response.output}' ExitCode: ${response.exitCode}")

                val success = response.exitCode == 0
                val resultTs = Timestamp.now()
                if (success) {
                    writeNetflixStatus(hotelName, deviceId, mapOf(
                        "netflix_reset_last_success" to resultTs,
                        "netflix_reset_success"      to true
                    ))
                    Log.d(TAG, "Netflix reset succeeded")
                } else {
                    writeNetflixStatus(hotelName, deviceId, mapOf(
                        "netflix_reset_last_failure" to resultTs,
                        "netflix_reset_success"      to false
                    ))
                    Log.w(TAG, "Netflix reset returned non-zero exit code")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error executing Netflix reset: ${e.message}", e)
                writeNetflixStatus(hotelName, deviceId, mapOf(
                    "netflix_reset_last_failure" to Timestamp.now(),
                    "netflix_reset_success"      to false
                ))
            }
        }
    }

    /**
     * Writes netflix reset status fields to the player document.
     * Works for both registered (hotels/{hotel}/players/{id}) and
     * unregistered (unregistered_players/{id}) players.
     */
    private fun writeNetflixStatus(hotelName: String?, deviceId: String, data: Map<String, Any>) {
        try {
            val db  = FirebaseFirestore.getInstance()
            val ref = if (!hotelName.isNullOrEmpty()) {
                db.collection("hotels").document(hotelName)
                    .collection("players").document(deviceId)
            } else {
                db.collection("unregistered_players").document(deviceId)
            }
            ref.update(data)
                .addOnFailureListener { e -> Log.e(TAG, "Netflix status write failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Netflix status write exception", e)
        }
    }

    companion object {
        private const val TAG = "NetflixResetReceiver"
    }
}
