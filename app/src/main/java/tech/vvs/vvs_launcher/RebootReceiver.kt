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

class RebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "RebootReceiver triggered")

        CoroutineScope(Dispatchers.IO).launch {
            val prefs     = context.getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
            val hotelName = prefs.getString("hotel_name", null)
            val deviceId  = DeviceManager.getOrCreateDeviceId(context)
            val now       = Timestamp.now()

            // Record the attempt time immediately
            writeRebootStatus(hotelName, deviceId, mapOf("reboot_last_attempt" to now))

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

                // Write success BEFORE rebooting, because we won't be able to afterwards!
                writeRebootStatus(hotelName, deviceId, mapOf(
                    "reboot_last_success" to Timestamp.now(),
                    "reboot_success"      to true
                ))
                
                // Allow a tiny bit of time for Firestore to send the update before the network dies
                Thread.sleep(1000)

                Log.d(TAG, "Executing: reboot")
                adb.shell("reboot")
                Log.d(TAG, "Reboot command sent")

            } catch (e: Exception) {
                // If it's EOFException or Socket error during reboot, it actually succeeded in rebooting.
                // But if it happens before, it's a failure.
                Log.e(TAG, "Error executing reboot: ${e.message}", e)
                writeRebootStatus(hotelName, deviceId, mapOf(
                    "reboot_last_failure" to Timestamp.now(),
                    "reboot_success"      to false
                ))
            }
        }
    }

    private fun writeRebootStatus(hotelName: String?, deviceId: String, data: Map<String, Any>) {
        try {
            val db  = FirebaseFirestore.getInstance()
            val ref = if (!hotelName.isNullOrEmpty()) {
                db.collection("hotels").document(hotelName)
                    .collection("players").document(deviceId)
            } else {
                db.collection("unregistered_players").document(deviceId)
            }
            ref.update(data)
                .addOnFailureListener { e -> Log.e(TAG, "Reboot status write failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Reboot status write exception", e)
        }
    }

    companion object {
        private const val TAG = "RebootReceiver"
    }
}
