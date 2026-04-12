package tech.vvs.vvs_launcher

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object IBSClient {
    private const val TAG = "IBSClient"
    private const val PREF_NAME = "vvs_prefs"

    fun sendReport(context: Context, report: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val ip = prefs.getString("ibs_ip", null)
                val port = prefs.getInt("ibs_port", -1)

                if (ip.isNullOrEmpty() || port <= 0) {
                    Log.e(TAG, "Cannot send report: IBS IP/Port not set")
                    return@launch
                }

                val deviceId = DeviceManager.getOrCreateDeviceId(context)
                val deviceIdEncoded = Base64.encodeToString(
                    deviceId.toByteArray(),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )
                
                val reportEncoded = URLEncoder.encode(report, "UTF-8")
                
                val urlString = "http://$ip:$port/player/statusreport?playerid=$deviceIdEncoded&report=$reportEncoded"
                Log.d(TAG, "Sending report: $urlString")

                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                Log.d(TAG, "Report response code: $responseCode")
                
                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Error sending report", e)
            }
        }
    }
}
