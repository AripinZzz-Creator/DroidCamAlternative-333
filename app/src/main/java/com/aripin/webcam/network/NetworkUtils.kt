package com.aripin.webcam.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    data class NetworkEndpoints(
        val wifiIp: String,
        val usbTetherIp: String?,
        val isUsbPlugged: Boolean
    )

    fun getNetworkEndpoints(context: Context): NetworkEndpoints {
        var wifiIp = "0.0.0.0"
        var usbIp: String? = null

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        when {
                            name.startsWith("wlan") || name.startsWith("eth") -> {
                                wifiIp = hostAddress
                            }
                            name.startsWith("usb") || name.startsWith("rndis") -> {
                                usbIp = hostAddress
                            }
                            wifiIp == "0.0.0.0" -> {
                                wifiIp = hostAddress
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isUsbPlugged = plugged == BatteryManager.BATTERY_PLUGGED_USB

        return NetworkEndpoints(
            wifiIp = wifiIp,
            usbTetherIp = usbIp,
            isUsbPlugged = isUsbPlugged
        )
    }
}
