package tech.vvs.vvs_launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

class IBSDiscoveryService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isListening = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            acquireMulticastLock()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "IBSDiscoveryService started")
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isListening) {
            startListening()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VVS Discovery Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VVS TV Discovery")
            .setContentText("Listening for IBS updates")
            .setSmallIcon(R.drawable.v_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("IBSDiscoveryLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } else {
            Log.e(TAG, "WifiManager is null, cannot acquire multicast lock")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
    }

    private fun startListening() {
        isListening = true
        serviceScope.launch {
            while (isActive) {
                var socket: MulticastSocket? = null
                try {
                    val group = InetAddress.getByName(MULTICAST_IP)
                    socket = MulticastSocket(MULTICAST_PORT)
                    socket.joinGroup(group)
                    //socket.networkInterface = NetworkInterface.getByName("wlan0") // Optional: depending on device

                    val buffer = ByteArray(4096)
                    
                    // Inner loop for packet reception
                    // We want to break this inner loop if we successfully get a valid packet,
                    // so we can delay before the next attempt.
                    while (isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        //Log.d(TAG, "Listening for multicast packets on $MULTICAST_IP:$MULTICAST_PORT")
                        socket.receive(packet)

                        val receivedText = String(packet.data, 0, packet.length)
                        //Log.d(TAG, "Received packet: $receivedText")

                        try {
                            val json = JSONObject(receivedText)
                            var playerUrl = json.optString("playerURL")

                            if (playerUrl.isNotEmpty()) {
                                //Log.d(TAG, "Discovered player URL: $playerUrl")
                                playerUrl = playerUrl.trim() + "/getplaylist"
                                savePlayerUrl(playerUrl)
                                // Continuous listening: do NOT break.
                                // break
                            }

                            var channelLineupUpdate = json.optString("name")
                            if (channelLineupUpdate.isNotEmpty() && channelLineupUpdate == "Channel lineup update") {
                                Log.d(TAG, "Discovered channel lineup update")
                                IBSClient.sendReport(applicationContext, "Got channel lineup update")
                                // Continuous listening: do NOT break

                                // Trigger shared preference update listener, that calls the getPlayerlist on IBS
//                                val prefs = getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
//                                val url = prefs.getString("channel_list_url", null)
//                                prefs.edit().putString("channel_list_url", url).apply()
//                                prefs.edit().commit()

                                savePlayerUrl(playerUrl)

                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON", e)
                            val deviceId = DeviceManager.getOrCreateDeviceId(applicationContext)
                            AnalyticsManager.logDiscoveryFail(applicationContext, deviceId, "discovery failed")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in multicast listener", e)
                    // Retry logic could go here, but for now we just log
                } finally {
                    socket?.close()
                }
                
                // If the inner loop exits (e.g. error), restart after a short delay
                if (isActive) {
                    Log.d(TAG, "Multicast listener restarted...")
                    delay(5000)
                }
            }
            isListening = false
        }
    }

    private fun savePlayerUrl(url: String) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isManual = prefs.getBoolean(PREF_KEY_MANUAL_OVERRIDE, false)
        val currentUrl = prefs.getString(PREF_KEY_CHANNEL_LIST_URL, "")

        // Always save IP/Port regardless of manual override, so dynamic background works
        try {
            // playerURL usually comes as "http://ip:port/player"
            // We want to parse the base connection info
            val uri = android.net.Uri.parse(url)
            val host = uri.host
            val port = uri.port
            
            if (!host.isNullOrEmpty() && port > 0) {
                val oldIp = prefs.getString("ibs_ip", "")
                val oldPort = prefs.getInt("ibs_port", -1)
                
                if (host != oldIp || port != oldPort) {
                    prefs.edit()
                        .putString("ibs_ip", host)
                        .putInt("ibs_port", port)
                        .apply()
                    Log.d(TAG, "Saved IBS Info: $host:$port")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse IBS URL for IP/Port", e)
        }

        if (isManual && !currentUrl.isNullOrEmpty() && !url.equals("0.0.0.0")) {
            Log.d(TAG, "Ignoring discovered URL because manual override is enabled (current: $currentUrl).")
            return
        }

        // Sanitize URL: remove trailing slash if present
        val sanitizedUrl = url.trim().removeSuffix("/")

        // If not manual, or empty, we update.
        // Even if it's the same, we write it to ensure it's set.
        if (currentUrl != sanitizedUrl) {
            prefs.edit()
                .putString(PREF_KEY_CHANNEL_LIST_URL, sanitizedUrl)
                // Ensure we don't accidentally set manual flag here; it remains false/undefined
                .apply()
            Log.d(TAG, "Saved discovered player URL: $sanitizedUrl")
            showToast("Player URL received: $sanitizedUrl")
        } else {
             //Log.d(TAG, "Discovered URL matches current URL. Forcing update via timestamp.")
             prefs.edit().putLong("last_discovery_time", System.currentTimeMillis()).apply()
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseMulticastLock()
        Log.d(TAG, "IBSDiscoveryService destroyed")
    }

    companion object {
        private const val TAG = "IBSDiscoveryService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "ibs_discovery_channel"
        private const val MULTICAST_IP = "239.254.13.13"
        private const val MULTICAST_PORT = 51313
        const val PREF_NAME = "vvs_prefs"
        const val PREF_KEY_CHANNEL_LIST_URL = "channel_list_url"
        const val PREF_KEY_MANUAL_OVERRIDE = "is_channel_url_manual_override"
    }
}
