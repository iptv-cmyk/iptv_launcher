package tech.vvs.vvs_launcher

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import dadb.Dadb
import dadb.AdbKeyPair
import java.io.File

object DeviceManager {
    private const val PREF_NAME = "vvs_prefs"
    private const val PREF_DEVICE_ID = "device_id"

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        // Try to get existing ID from prefs
        var deviceId = prefs.getString(PREF_DEVICE_ID, null)
        
        if (deviceId.isNullOrEmpty()) {
            // Get Android ID
            try {
                deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                Log.d("DeviceManager", "got Android ID from Settings.Secure: $deviceId")


            } catch (e: Exception) {
                // Fallback if something goes wrong
                e.printStackTrace()
            }

            // Fallback to UUID if Android ID is null or empty (e.g. some emulators)
            if (deviceId.isNullOrEmpty()) {
                deviceId = UUID.randomUUID().toString().replace("-", "")
                Log.w("DeviceManager", "got Android ID from UUID: $deviceId")
            }
            
            // Save to prefs
            prefs.edit().putString(PREF_DEVICE_ID, deviceId).apply()
        }
        
        return deviceId!!
    }

    fun saveDeviceInfo(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        editor.putInt("device_sdk_version", android.os.Build.VERSION.SDK_INT)
        editor.putString("device_release", android.os.Build.VERSION.RELEASE)
        editor.putString("device_model", android.os.Build.MODEL)
        editor.putString("device_manufacturer", android.os.Build.MANUFACTURER)
        editor.putString("device_product", android.os.Build.PRODUCT)
        editor.putString("device_hardware", android.os.Build.HARDWARE)
        editor.putString("device_board", android.os.Build.BOARD)
        editor.putString("device_brand", android.os.Build.BRAND)
        editor.putString("device_display", android.os.Build.DISPLAY)
        editor.putString("device_fingerprint", android.os.Build.FINGERPRINT)
        
        editor.apply()
        
        // Log what we saved for debugging
        android.util.Log.d("DeviceManager", "Device info saved: Model=${android.os.Build.MODEL}, SDK=${android.os.Build.VERSION.SDK_INT}")
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: ""
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "Unknown"
    }

    fun getMacAddress(context: Context): String {
        // Try 0: If Device Owner, use DevicePolicyManager to get Wifi MAC
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val admin = ComponentName(context, AdminReceiver::class.java)
                val wifiMac = dpm.getWifiMacAddress(admin)
                if (wifiMac != null && isValidMac(wifiMac)) {
                    Log.d("DeviceManager", "Got MAC via DevicePolicyManager: $wifiMac")
                    return wifiMac.toUpperCase()
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceManager", "Error getting MAC via DevicePolicyManager: ${e.message}")
        }

        // Try 1: Try local ADB loopback
        val adbMac = getMacAddressUsingAdb(context)
        if (adbMac != null) {
            return adbMac
        }

        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            
            // Try 2: Find interface matching the local IP address
            val localIp = getLocalIpAddress()
            if (localIp != "Unknown") {
                for (intf in interfaces) {
                    val addrs = java.util.Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (addr.hostAddress == localIp) {
                            val mac = intf.hardwareAddress
                            if (mac != null && mac.isNotEmpty()) {
                                val buf = StringBuilder()
                                for (aMac in mac) {
                                    buf.append(String.format("%02X:", aMac))
                                }
                                if (buf.length > 0) {
                                    buf.deleteCharAt(buf.length - 1)
                                }
                                return buf.toString()
                            }
                        }
                    }
                }
            }

            // Try 3: Find eth0 or wlan0 specifically
            for (intf in interfaces) {
                val name = intf.name.toLowerCase()
                if (name == "eth0" || name == "wlan0") {
                    val mac = intf.hardwareAddress
                    if (mac != null && mac.isNotEmpty()) {
                        val buf = StringBuilder()
                        for (aMac in mac) {
                            buf.append(String.format("%02X:", aMac))
                        }
                        if (buf.length > 0) {
                            buf.deleteCharAt(buf.length - 1)
                        }
                        return buf.toString()
                    }
                }
            }

            // Try 4: Try any non-loopback interface that has a hardware address
            for (intf in interfaces) {
                if (intf.isLoopback) continue
                val mac = intf.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    val buf = StringBuilder()
                    for (aMac in mac) {
                        buf.append(String.format("%02X:", aMac))
                    }
                    if (buf.length > 0) {
                        buf.deleteCharAt(buf.length - 1)
                    }
                    return buf.toString()
                }
            }

            // Try 5: Read from sysfs /sys/class/net/
            val sysFsMac = getMacFromSysFsGeneric()
            if (sysFsMac != null) {
                return sysFsMac
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "Unknown"
    }

    private fun isValidMac(mac: String): Boolean {
        val trimmed = mac.trim()
        return trimmed.isNotEmpty() && 
               trimmed != "02:00:00:00:00:00" && 
               trimmed != "00:00:00:00:00:00" && 
               trimmed.contains(":")
    }

    private fun getMacAddressUsingAdb(context: Context): String? {
        try {
            val keyFile    = File(context.filesDir, "adbkey")
            val pubKeyFile = File(context.filesDir, "adbkey.pub")

            val keyPair = if (keyFile.exists() && pubKeyFile.exists()) {
                AdbKeyPair.read(keyFile, pubKeyFile)
            } else {
                AdbKeyPair.generate(privateKeyFile = keyFile, publicKeyFile = pubKeyFile)
                AdbKeyPair.read(keyFile, pubKeyFile)
            }

            Log.d("DeviceManager", "Connecting to local ADB to get MAC...")
            val adb = Dadb.create("127.0.0.1", 5555, keyPair)

            // Try to find the interface name that matches our local IP address
            val localIp = getLocalIpAddress()
            var targetInterfaceName: String? = null
            if (localIp != "Unknown") {
                val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = java.util.Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (addr.hostAddress == localIp) {
                            targetInterfaceName = intf.name
                            break
                        }
                    }
                    if (targetInterfaceName != null) break
                }
            }

            if (targetInterfaceName != null) {
                val response = adb.shell("cat /sys/class/net/$targetInterfaceName/address")
                if (response.exitCode == 0) {
                    val mac = response.output.trim().toUpperCase()
                    if (isValidMac(mac)) {
                        Log.d("DeviceManager", "Got MAC via ADB from $targetInterfaceName: $mac")
                        return mac
                    }
                }
            }

            // Fallback: Try eth0
            val ethResponse = adb.shell("cat /sys/class/net/eth0/address")
            if (ethResponse.exitCode == 0) {
                val mac = ethResponse.output.trim().toUpperCase()
                if (isValidMac(mac)) {
                    Log.d("DeviceManager", "Got MAC via ADB from eth0: $mac")
                    return mac
                }
            }

            // Fallback: Try wlan0
            val wlanResponse = adb.shell("cat /sys/class/net/wlan0/address")
            if (wlanResponse.exitCode == 0) {
                val mac = wlanResponse.output.trim().toUpperCase()
                if (isValidMac(mac)) {
                    Log.d("DeviceManager", "Got MAC via ADB from wlan0: $mac")
                    return mac
                }
            }

            // Fallback: list all and pick the first non-lo/non-zero MAC
            val allInterfacesResponse = adb.shell("ip link show")
            if (allInterfacesResponse.exitCode == 0) {
                val lines = allInterfacesResponse.output.lines()
                for (line in lines) {
                    if (line.contains("link/ether")) {
                        val parts = line.trim().split("\\s+".toRegex())
                        val idx = parts.indexOf("link/ether")
                        if (idx != -1 && idx + 1 < parts.size) {
                            val mac = parts[idx + 1].trim().toUpperCase()
                            if (isValidMac(mac)) {
                                Log.d("DeviceManager", "Got MAC via ADB ip link show: $mac")
                                return mac
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceManager", "Failed to get MAC via local ADB: ${e.message}")
        }
        return null
    }


    private fun getMacFromSysFsGeneric(): String? {
        try {
            val netDir = java.io.File("/sys/class/net/")
            val files = netDir.listFiles() ?: return null
            // Try to look for ethernet and wifi first
            val sortedFiles = files.sortedBy { file ->
                val name = file.name.toLowerCase()
                if (name.startsWith("eth") || name.startsWith("wlan")) 0 else 1
            }
            for (file in sortedFiles) {
                if (file.name == "lo") continue
                try {
                    val addrFile = java.io.File(file, "address")
                    if (addrFile.exists()) {
                        val address = addrFile.readText().trim()
                        if (address.isNotEmpty() && address != "02:00:00:00:00:00" && address.contains(":")) {
                            return address.toUpperCase()
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }
}
