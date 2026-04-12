package tech.vvs.vvs_launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject
import java.util.Arrays

class HelloService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private const val TAG = "HelloService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "hello_service_channel"
        
        // Dynamic parameter
        // private const val PLAYER_ID = "cGxheWVyMDAwMQ=="
        // private const val CHALLENGE = "bGF6eWZveCZicm93bmRvZw=="
        private const val AUTH_ID = "123"
        private const val SECRET = "8lBevKSaLmaTwmSujtxcUUwWmsnqwaBmT3d8p0Ga"
        // 1 hour interval
        private const val PING_INTERVAL_MS = 60 * 60 * 1000L
        //private const val PING_INTERVAL_MS = 2 * 60 * 1000L
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HelloService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HelloService started")
        
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        startPingLoop()

        // START_STICKY tells OS to recreate the service if it gets killed
        return START_STICKY
    }

    private fun startPingLoop() {
        serviceScope.launch {
            while (isActive) {
                val success = pingIBS()
                if (!success) {
                    Log.w(TAG, "Ping failed. content-type: triggering discovery service...")
                    try {
                         val discoveryIntent = Intent(applicationContext, IBSDiscoveryService::class.java)
                         startService(discoveryIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start discovery service", e)
                    }
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun pingIBS(): Boolean {
        val prefs = getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("ibs_ip", null)
        val port = prefs.getInt("ibs_port", -1)

        if (ip.isNullOrEmpty() || port <= 0) {
            Log.e(TAG, "Cannot ping: IBS IP/Port not set")
            return false
        }

        val deviceId = DeviceManager.getOrCreateDeviceId(applicationContext)
        Log.d(TAG, "Pinging IBS with ID $deviceId")
        
        // Generate random 16-byte challenge
        val random = SecureRandom()
        val challengeBytes = ByteArray(16)
        random.nextBytes(challengeBytes)
        
        // Base64 Encode (URL_SAFE | NO_WRAP matches Python's urlsafe_b64encode)
        val deviceIdEncoded = Base64.encodeToString(deviceId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val challengeEncoded = Base64.encodeToString(challengeBytes, Base64.URL_SAFE or Base64.NO_WRAP)
        
        val urlString = "http://$ip:$port/player/hello?playerid=$deviceIdEncoded&challenge=$challengeEncoded&authid=$AUTH_ID"
        Log.d(TAG, "Pinging IBS with ID $deviceId: $urlString")

        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            val responseText = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "Error reading response: ${e.message}"
            }
            Log.d(TAG, "Hello response: $responseCode, Body: $responseText")
            
            if (responseCode == 200) {
                try {
                    val jsonResponse = JSONObject(responseText)
                    val serverRandomB64 = jsonResponse.getString("server_random")
                    val serverResponseB64 = jsonResponse.getString("response")
                    
                    val serverRandomBytes = Base64.decode(serverRandomB64, Base64.DEFAULT)
                    val serverResponseBytes = Base64.decode(serverResponseB64, Base64.DEFAULT)
                    
                    val secretBytes = SECRET.toByteArray()
                    val deviceIdBytes = deviceId.toByteArray()
                    
                    // Concatenate: deviceId + challenge + serverRandom + secret
                    val inputBytes = ByteArray(deviceIdBytes.size + challengeBytes.size + serverRandomBytes.size + secretBytes.size)
                    var offset = 0
                    
                    System.arraycopy(deviceIdBytes, 0, inputBytes, offset, deviceIdBytes.size)
                    offset += deviceIdBytes.size
                    
                    System.arraycopy(challengeBytes, 0, inputBytes, offset, challengeBytes.size)
                    offset += challengeBytes.size
                    
                    System.arraycopy(serverRandomBytes, 0, inputBytes, offset, serverRandomBytes.size)
                    offset += serverRandomBytes.size
                    
                    System.arraycopy(secretBytes, 0, inputBytes, offset, secretBytes.size)
                    
                    // SHA-256 Hash
                    val digest = MessageDigest.getInstance("SHA-256")
                    val hash = digest.digest(inputBytes)
                    
                    // Verify first 16 bytes
                    val expectedResponse = Arrays.copyOfRange(hash, 0, 16)
                    
                    if (Arrays.equals(expectedResponse, serverResponseBytes)) {
                        Log.d(TAG, "Authentication successful")
                    } else {
                         Log.e(TAG, "Authentication failed")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in authentication verification", e)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pinging IBS", e)
            false
        } finally {
            // connection.disconnect() handled by stream closure or GC usually, 
            // but explicit disconnect is good if scope allows. 
            // Here variable 'connection' is local to try block, so consistent disconnect is tricky without re-structure.
            // Leaving as is since original code didn't rigorously disconnect in finally.
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VVS Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VVS TV Service")
            .setContentText("Maintaining connection to IBS")
            .setSmallIcon(R.drawable.v_logo) 
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HelloService destroyed")
        serviceScope.cancel()
    }
}
