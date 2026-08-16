package com.grupotgt.launcherkioscotgt.mdm

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address

internal object MdmTelemetryCollector {
    private const val NOT_AVAILABLE = "NO DISPONIBLE"
    private const val NOT_VERIFIABLE = "NO VERIFICABLE"
    private const val PERMISSION_DENIED = "PERMISO DENEGADO"

    fun collect(context: Context): JSONObject {
        val app = context.applicationContext
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val link = network?.let(connectivity::getLinkProperties)
        val wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellularConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val internetValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport = when {
            wifiConnected -> "WIFI"
            cellularConnected -> "MOVIL"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
            network == null -> "SIN CONEXION"
            else -> "OTRA"
        }
        val ip = link?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress ?: NOT_AVAILABLE
        val wifi = wifiState(app, wifiConnected)
        val battery = batteryState(app)
        val mode = ManagedModeStore.state(app)
        val config = MdmConfigCache(app).load().getOrNull()
        val configuredApps = config?.apps ?: emptyList()
        val installedApps = JSONArray()
        configuredApps.forEach { configured ->
            if (isPackageInstalled(app, configured.packageName)) installedApps.put(configured.packageName)
        }
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = if (maxVolume > 0) {
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume
        } else -1
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        val airplane = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }.getOrNull()
        val brightness = runCatching {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        }.getOrNull()

        return JSONObject()
            .put("collected_at_ms", System.currentTimeMillis())
            .put("model", listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim())
            .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("app_version", "${packageInfo.versionName ?: NOT_AVAILABLE} ($versionCode)")
            .put("battery_percent", battery.first)
            .put("charging", battery.second)
            .put("ip", ip)
            .put("network_transport", transport)
            .put("wifi_connected", wifiConnected)
            .put("ssid", wifi.first)
            .put("wifi_rssi_dbm", wifi.second)
            .put("internet_validated", internetValidated)
            .put("mobile_data_connected", cellularConnected)
            .put("airplane_mode", airplane ?: NOT_AVAILABLE)
            .put("brightness_percent", brightness ?: NOT_AVAILABLE)
            .put("volume_percent", if (volumePercent >= 0) volumePercent else NOT_AVAILABLE)
            .put("device_owner", dpm.isDeviceOwnerApp(app.packageName))
            .put("lock_task_state", lockTaskState(activityManager.lockTaskModeState))
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put("storage_available_bytes", storage.availableBytes)
            .put("storage_total_bytes", storage.totalBytes)
            .put("desired_mode", mode.desiredMode.wireValue)
            .put("desired_mode_revision", mode.desiredRevision)
            .put("applied_mode", mode.appliedMode.wireValue)
            .put("applied_mode_revision", mode.appliedRevision)
            .put("transition_phase", mode.phase)
            .put("last_error", mode.lastError.ifBlank { "SIN ERROR" })
            .put("agenda_status", if (config == null) NOT_AVAILABLE else "CACHE VALIDADA")
            .put("agenda_contacts", config?.contacts?.size ?: 0)
            .put("configured_apps", JSONArray(configuredApps.map { it.packageName }))
            .put("installed_configured_apps", installedApps)
            .put("telephony_capable", app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            .put("vowifi_state", NOT_VERIFIABLE)
    }

    private fun batteryState(context: Context): Pair<Any, Any> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return NOT_AVAILABLE to NOT_AVAILABLE
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent: Any = if (level >= 0 && scale > 0) level * 100 / scale else NOT_AVAILABLE
        val charging: Any = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> NOT_AVAILABLE
        }
        return percent to charging
    }

    @Suppress("DEPRECATION")
    private fun wifiState(context: Context, connected: Boolean): Pair<String, Any> {
        if (!connected) return NOT_AVAILABLE to NOT_AVAILABLE
        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!locationGranted) return PERMISSION_DENIED to PERMISSION_DENIED
        return runCatching {
            val info = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            val ssid = info.ssid?.removeSurrounding("\"")
                ?.takeUnless { it.equals("<unknown ssid>", true) || it.isBlank() }
                ?: NOT_AVAILABLE
            ssid to info.rssi
        }.getOrElse { NOT_AVAILABLE to NOT_AVAILABLE }
    }

    private fun lockTaskState(state: Int): String = when (state) {
        ActivityManager.LOCK_TASK_MODE_LOCKED -> "LOCKED"
        ActivityManager.LOCK_TASK_MODE_PINNED -> "PINNED"
        ActivityManager.LOCK_TASK_MODE_NONE -> "NONE"
        else -> "UNKNOWN"
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
