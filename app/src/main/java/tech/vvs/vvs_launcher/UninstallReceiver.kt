package tech.vvs.vvs_launcher

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class UninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "tech.vvs.vvs_launcher.CLEAR_DEVICE_OWNER") {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.d("VVS-TV", "Programmatically cleared device owner via UninstallReceiver.")
            } catch (e: Exception) {
                Log.e("VVS-TV", "Failed to clear device owner programmatically in UninstallReceiver: ${e.message}", e)
            }
        }
    }
}
