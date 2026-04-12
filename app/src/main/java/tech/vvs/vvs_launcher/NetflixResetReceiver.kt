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

class NetflixResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "NetflixResetReceiver triggered")
        
        // Execute the reset in a background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Reuse the same key pair location as MainActivity
                val keyFile = File(context.filesDir, "adbkey")
                val pubKeyFile = File(context.filesDir, "adbkey.pub")
                
                val keyPair = if (keyFile.exists() && pubKeyFile.exists()) {
                    AdbKeyPair.read(keyFile, pubKeyFile)
                } else {
                    // Start fresh if keys are missing (though unlikely if app was used)
                    AdbKeyPair.generate(privateKeyFile = keyFile, publicKeyFile = pubKeyFile)
                    AdbKeyPair.read(keyFile, pubKeyFile)
                }

                Log.d(TAG, "Connecting to local ADB...")
                val adb = Dadb.create("127.0.0.1", 5555, keyPair)
                
                // Test connection first
                Log.d(TAG, "Testing ADB connection...")
                val testResponse = adb.shell("echo 'ADB Connection OK'")
                Log.d(TAG, "Test Output: ${testResponse.output} ExitCode: ${testResponse.exitCode}")

                Log.d(TAG, "Executing: pm clear com.netflix.ninja")
                val response = adb.shell("pm clear com.netflix.ninja")
                Log.d(TAG, "Reset Output: '${response.output}' ExitCode: ${response.exitCode}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error executing Netflix reset: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "NetflixResetReceiver"
    }
}
