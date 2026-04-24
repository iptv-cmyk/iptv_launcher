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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject
import java.util.Arrays
import com.google.firebase.firestore.ListenerRegistration

class HelloService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var commandListenerRegistration: ListenerRegistration? = null

    /** Result of a single IBS ping attempt. */
    private enum class PingResult {
        /** IBS responded with HTTP 200 and valid auth. */
        SUCCESS,
        /** Network timeout or connection refused — IBS is down, player is fine. */
        IBS_UNREACHABLE,
        /** IBS responded but with a non-200 status or bad auth. */
        IBS_ERROR
    }

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
        startCommandListener()

        // START_STICKY tells OS to recreate the service if it gets killed
        return START_STICKY
    }

    private fun startPingLoop() {
        serviceScope.launch {
            while (isActive) {
                val result = pingIBS()
                updateFirestoreHeartbeat(result)
                syncChannelsToFirestore()            // keep channel list fresh
                if (result == PingResult.IBS_UNREACHABLE || result == PingResult.IBS_ERROR) {
                    Log.w(TAG, "Ping failed ($result). Triggering discovery service...")
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

    /**
     * Fetches the channel list URL from SharedPrefs, parses channel names,
     * and writes them to the player's Firestore document so the dashboard
     * can show which channels this player has.
     */
    private fun syncChannelsToFirestore() {
        try {
            val prefs      = getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
            val channelUrl = prefs.getString("channel_list_url", null)
            if (channelUrl.isNullOrEmpty()) return

            val hotelName = prefs.getString("hotel_name", null)
            val deviceId  = DeviceManager.getOrCreateDeviceId(applicationContext)
            val db        = FirebaseFirestore.getInstance()

            // Fetch the .u38 / m3u file
            val content = try {
                val conn = java.net.URL(channelUrl).openConnection()
                conn.connectTimeout = 5_000
                conn.readTimeout    = 10_000
                conn.getInputStream().bufferedReader().readText()
            } catch (e: Exception) {
                Log.w(TAG, "Channel list fetch failed: ${e.message}")
                return
            }

            // Parse channel names (same logic as ChannelViewModel)
            val names = mutableListOf<String>()
            var pendingName: String? = null
            var index = 1
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#")) {
                    if (trimmed.startsWith("#EXTINF", ignoreCase = true)) {
                        val comma = trimmed.indexOf(',')
                        if (comma >= 0 && comma < trimmed.length - 1)
                            pendingName = trimmed.substring(comma + 1).trim()
                    }
                    continue
                }
                val protocols = listOf("udp://", "http://", "https://")
                val uriStart = protocols.mapNotNull { p ->
                    val idx = trimmed.indexOf(p, ignoreCase = true)
                    if (idx >= 0) idx else null
                }.minOrNull() ?: continue

                val namePart = trimmed.substring(0, uriStart).trim().trim(',', ';', ':', '=')
                val name = when {
                    namePart.isNotEmpty()        -> namePart
                    !pendingName.isNullOrEmpty() -> pendingName!!
                    else                         -> "Channel $index"
                }
                names.add(name)
                pendingName = null
                index++
            }

            val channelData = mapOf<String, Any>(
                "channel_list_url" to channelUrl,
                "channel_count"    to names.size,
                "channels"         to names
            )

            val ref = if (!hotelName.isNullOrEmpty()) {
                db.collection("hotels").document(hotelName)
                    .collection("players").document(deviceId)
            } else {
                db.collection("unregistered_players").document(deviceId)
            }

            ref.update(channelData)
                .addOnSuccessListener { Log.d(TAG, "Channels synced: ${names.size}") }
                .addOnFailureListener { e -> Log.e(TAG, "Channel sync failed", e) }

        } catch (e: Exception) {
            Log.e(TAG, "syncChannelsToFirestore exception", e)
        }
    }

    private fun startCommandListener() {
        try {
            val deviceId = DeviceManager.getOrCreateDeviceId(applicationContext)
            val prefs = getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
            val hotelName = prefs.getString("hotel_name", null)
            val db = FirebaseFirestore.getInstance()

            val docRef = if (!hotelName.isNullOrEmpty()) {
                db.collection("hotels").document(hotelName).collection("players").document(deviceId)
            } else {
                db.collection("unregistered_players").document(deviceId)
            }

            commandListenerRegistration?.remove()
            commandListenerRegistration = docRef.addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Command listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val command = snapshot.getString("command")
                    val commandId = snapshot.getString("command_id")
                    
                    if (!commandId.isNullOrEmpty()) {
                        val lastExecuted = prefs.getString("last_executed_command_id", null)
                        if (commandId != lastExecuted) {
                            Log.d(TAG, "Executing command: $command ($commandId)")
                            prefs.edit().putString("last_executed_command_id", commandId).apply()
                            
                            when (command) {
                                "reset_netflix" -> {
                                    val intent = Intent(applicationContext, NetflixResetReceiver::class.java)
                                    sendBroadcast(intent)
                                }
                                "reboot" -> {
                                    val intent = Intent(applicationContext, RebootReceiver::class.java)
                                    sendBroadcast(intent)
                                }
                                else -> {
                                    Log.w(TAG, "Unknown command: $command")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startCommandListener exception", e)
        }
    }

    /**
     * Updates the player's heartbeat in Firestore after each hourly ping.
     *
     * Key distinction:
     *  - The player is ALWAYS marked is_alive=true here — because if this code
     *    is running, the player is clearly alive.
     *  - ibs_reachable reflects whether the IBS responded successfully.
     *    A timeout means the IBS is down, NOT the player.
     *
     * hotel_name is persisted by RegistrationService into shared prefs.
     */
    private fun updateFirestoreHeartbeat(result: PingResult) {
        try {
            val prefs     = getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
            val hotelName = prefs.getString("hotel_name", null)
            val deviceId  = DeviceManager.getOrCreateDeviceId(applicationContext)
            val db        = FirebaseFirestore.getInstance()
            val ibsReachable = result == PingResult.SUCCESS
            val now       = Timestamp.now()

            if (hotelName.isNullOrEmpty()) {
                // Player is alive but hasn't registered with any IBS yet.
                // Write to the top-level unregistered_players collection so the
                // dashboard can still see it.
                val appVersion: String = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                } catch (e: Exception) { "unknown" }

                val data = hashMapOf<String, Any>(
                    "device_id"     to deviceId,
                    "is_alive"      to true,
                    "last_seen"     to now,
                    "ibs_reachable" to ibsReachable,
                    "app_version"   to appVersion,
                    "model"         to (prefs.getString("device_model", "") ?: ""),
                    "brand"         to (prefs.getString("device_brand", "") ?: ""),
                    "ibs_ip"        to (prefs.getString("ibs_ip", "") ?: ""),
                    "ibs_port"      to prefs.getInt("ibs_port", 0)
                )
                if (!ibsReachable) data["last_ibs_failure"] = now

                db.collection("unregistered_players")
                    .document(deviceId)
                    .set(data, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "Unregistered heartbeat written for $deviceId")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Unregistered heartbeat failed", e)
                    }
                return
            }

            // Registered player — update the hotel sub-collection.
            val updates = hashMapOf<String, Any>(
                // The player is alive — it's running this code right now.
                "is_alive"      to true,
                "last_seen"     to now,
                // IBS health is tracked separately.
                "ibs_reachable" to ibsReachable
            )
            if (!ibsReachable) {
                updates["last_ibs_failure"] = now
            }

            db.collection("hotels")
                .document(hotelName)
                .collection("players")
                .document(deviceId)
                .update(updates)
                .addOnSuccessListener {
                    Log.d(TAG, "Firestore heartbeat: is_alive=true, ibs_reachable=$ibsReachable (ping=$result)")
                    // Defensive cleanup: remove from unregistered list if we are now registered
                    db.collection("unregistered_players").document(deviceId).delete()
                    
                    // Restart command listener in case we just transitioned from unregistered to registered
                    startCommandListener()
                }
                .addOnFailureListener { e -> Log.e(TAG, "Firestore heartbeat update failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore heartbeat exception", e)
        }
    }

    private suspend fun pingIBS(): PingResult {
        val prefs = getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("ibs_ip", null)
        val port = prefs.getInt("ibs_port", -1)

        if (ip.isNullOrEmpty() || port <= 0) {
            Log.e(TAG, "Cannot ping: IBS IP/Port not set")
            return PingResult.IBS_ERROR
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
            connection.disconnect()
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
                PingResult.SUCCESS
            } else {
                Log.w(TAG, "IBS returned non-200: $responseCode")
                PingResult.IBS_ERROR
            }
        } catch (e: java.net.SocketTimeoutException) {
            // Timeout = IBS is unreachable. The player is alive — it made the attempt.
            Log.e(TAG, "IBS unreachable (timeout) — IBS is down, not the player", e)
            PingResult.IBS_UNREACHABLE
        } catch (e: Exception) {
            Log.e(TAG, "Error pinging IBS", e)
            PingResult.IBS_UNREACHABLE
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
        commandListenerRegistration?.remove()
        serviceScope.cancel()
    }
}
