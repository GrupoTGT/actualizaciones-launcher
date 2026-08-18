package com.grupotgt.launcherkioscotgt

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.app.Dialog
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import com.grupotgt.launcherkioscotgt.mdm.InternalCommandGate
import com.grupotgt.launcherkioscotgt.mdm.ManagedMode
import com.grupotgt.launcherkioscotgt.mdm.ManagedModeStore
import com.grupotgt.launcherkioscotgt.mdm.MdmBridgeConfig
import com.grupotgt.launcherkioscotgt.mdm.MdmConfigCache
import com.grupotgt.launcherkioscotgt.mdm.MdmConfigSnapshot
import com.grupotgt.launcherkioscotgt.mdm.MdmEnrollmentCoordinator
import com.grupotgt.launcherkioscotgt.mdm.MdmHeartbeatScheduler
import com.grupotgt.launcherkioscotgt.mdm.MdmPilotOtaStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MaintenanceModeManager {
    private const val PREFS = "ConfigKiosco"
    private const val KEY_ACTIVO = "it_mantenimiento_activo"
    private const val KEY_HASTA = "it_mantenimiento_hasta"
    private const val KEY_INICIO = "it_mantenimiento_inicio"
    private const val KEY_HOME_MANT_PKG = "it_mantenimiento_home_pkg"
    private const val KEY_HOME_MANT_CLASS = "it_mantenimiento_home_class"
    private const val ACTION_EXPIRA = "com.grupotgt.launcherkioscotgt.MANTENIMIENTO_EXPIRA"
    private const val REQUEST_EXPIRA = 6201

    const val HASTA_MANUAL: Long = Long.MAX_VALUE

    fun estaConfigurado(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVO, false)
    }

    fun estaActivo(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVO, false)) return false

        val hasta = prefs.getLong(KEY_HASTA, 0L)
        if (hasta == HASTA_MANUAL) return true

        if (hasta <= System.currentTimeMillis()) {
            prefs.edit()
                .putBoolean(KEY_ACTIVO, false)
                .putLong(KEY_HASTA, 0L)
                .apply()
            cancelarAlarma(context)
            return false
        }

        return true
    }

    fun esHastaManual(context: Context): Boolean {
        if (!estaActivo(context)) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_HASTA, 0L) == HASTA_MANUAL
    }

    fun restanteMs(context: Context): Long {
        if (!estaActivo(context)) return 0L
        val hasta = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_HASTA, 0L)
        return if (hasta == HASTA_MANUAL) HASTA_MANUAL
        else (hasta - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun descripcion(context: Context): String {
        if (!estaActivo(context)) return "INACTIVO"
        if (esHastaManual(context)) return "HASTA BLOQUEO MANUAL"

        val restante = restanteMs(context)
        val totalSeg = restante / 1000L
        val horas = totalSeg / 3600L
        val minutos = (totalSeg % 3600L) / 60L
        val segundos = totalSeg % 60L
        return String.format(Locale.getDefault(), "%02d:%02d:%02d restantes", horas, minutos, segundos)
    }

    fun activar(context: Context, duracionMs: Long): Long {
        val ahora = System.currentTimeMillis()
        val hasta = if (duracionMs < 0L) HASTA_MANUAL else ahora + duracionMs

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVO, true)
            .putLong(KEY_INICIO, ahora)
            .putLong(KEY_HASTA, hasta)
            .commit()

        if (hasta == HASTA_MANUAL) {
            cancelarAlarma(context)
        } else {
            programarAlarma(context, hasta)
        }

        return hasta
    }

    fun finalizarEstado(context: Context, motivo: String) {
        val estabaActivo = estaConfigurado(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVO, false)
            .putLong(KEY_HASTA, 0L)
            .putLong(KEY_INICIO, 0L)
            .commit()

        cancelarAlarma(context)

        if (estabaActivo) {
            AppLog.info("IT MANTENIMIENTO -> finalizado. Motivo=$motivo")
        }
    }

    private fun filtroHome(): IntentFilter {
        return IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    private fun componenteHomeAlias(context: Context): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.HomeActivity")
    }

    private fun habilitarHomeAlias(context: Context, habilitar: Boolean) {
        val estado = if (habilitar) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        context.packageManager.setComponentEnabledSetting(
            componenteHomeAlias(context),
            estado,
            PackageManager.DONT_KILL_APP
        )

        AppLog.info(
            "HOME ALIAS -> ${if (habilitar) "HABILITADO" else "DESHABILITADO"} " +
                    "(${componenteHomeAlias(context).flattenToShortString()})"
        )
    }

    fun prepararHomeProduccion(context: Context) {
        try {
            habilitarHomeAlias(context, true)
        } catch (e: Exception) {
            AppLog.error("KIOSK -> no se pudo habilitar HOME alias: ${e.message}")
        }
    }

    private fun buscarLauncherNativo(context: Context): ComponentName? {
        return try {
            val pm = context.packageManager

            // Este parque de terminales usa Samsung One UI Home. No dejamos que
            // Android elija com.android.settings/.FallbackHome, porque NO es un
            // escritorio de usuario y fue precisamente uno de los falsos candidatos
            // detectados durante el diagnóstico ADB.
            val oneUiHome = ComponentName(
                "com.sec.android.app.launcher",
                "com.sec.android.app.launcher.activities.LauncherActivity"
            )

            val oneUiDisponible = try {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(oneUiHome, 0)
                true
            } catch (_: Exception) {
                false
            }

            if (oneUiDisponible) {
                AppLog.success(
                    "IT MANTENIMIENTO -> One UI Home detectado: ${oneUiHome.flattenToShortString()}"
                )
                return oneUiHome
            }

            // Fallback para futuros terminales no Samsung. Nunca aceptamos FallbackHome
            // de Settings como launcher de mantenimiento.
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }

            val candidatos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }

            val externos = candidatos
                .mapNotNull { ri ->
                    val ai = ri.activityInfo ?: return@mapNotNull null
                    if (ai.packageName == context.packageName) return@mapNotNull null
                    if (ai.packageName == "com.android.settings") return@mapNotNull null
                    ComponentName(ai.packageName, ai.name)
                }
                .distinctBy { it.flattenToShortString() }

            AppLog.info(
                "IT MANTENIMIENTO -> HOME externos válidos=" +
                        externos.joinToString { it.flattenToShortString() }
            )

            externos.firstOrNull()
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> error buscando launcher nativo: ${e.message}")
            null
        }
    }

    private fun guardarHomeMantenimiento(context: Context, component: ComponentName?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (component == null) {
            edit.remove(KEY_HOME_MANT_PKG).remove(KEY_HOME_MANT_CLASS).commit()
        } else {
            edit.putString(KEY_HOME_MANT_PKG, component.packageName)
                .putString(KEY_HOME_MANT_CLASS, component.className)
                .commit()
        }
    }

    private fun leerHomeMantenimiento(context: Context): ComponentName? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_HOME_MANT_PKG, null)
        val cls = prefs.getString(KEY_HOME_MANT_CLASS, null)
        return if (!pkg.isNullOrBlank() && !cls.isNullOrBlank()) ComponentName(pkg, cls) else null
    }

    private fun obtenerPaquetesMantenimiento(context: Context): Array<String> {
        val pm = context.packageManager
        val paquetes = linkedSetOf<String>()

        try {
            val instalados = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            instalados.mapTo(paquetes) { it.packageName }
        } catch (e: Exception) {
            AppLog.warning("IT MANTENIMIENTO -> no se pudo enumerar todos los paquetes: ${e.message}")
        }

        // Garantías explícitas para las piezas críticas del mantenimiento Samsung.
        paquetes.add(context.packageName)
        paquetes.add("com.sec.android.app.launcher")
        paquetes.add("com.android.settings")
        paquetes.add("com.android.vending")
        paquetes.add("com.google.android.gms")
        paquetes.add("com.google.android.packageinstaller")
        paquetes.add("com.android.packageinstaller")
        paquetes.add("com.samsung.android.packageinstaller")
        paquetes.add("com.google.android.permissioncontroller")
        paquetes.add("com.android.permissioncontroller")
        paquetes.add("com.sec.android.app.myfiles")
        paquetes.add("com.sec.android.app.samsungapps")

        return paquetes.toTypedArray()
    }

    fun refrescarAllowlistMantenimiento(context: Context): Boolean {
        if (!estaActivo(context) && !ManagedModeStore.isManagedFree(context)) return false

        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                AppLog.error("IT MANTENIMIENTO -> no es Device Owner; no se puede liberar LockTask.")
                return false
            }

            // En mantenimiento REAL no necesitamos una allowlist multi-app: LockTask debe estar NONE.
            // Dejamos la lista vacía para evitar que una tarea vuelva a entrar accidentalmente en kiosco.
            dpm.setLockTaskPackages(admin, emptyArray<String>())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }

            val totalConfirmado = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { dpm.getLockTaskPackages(admin).size } catch (_: Exception) { -1 }
            } else {
                -1
            }

            AppLog.success(
                "IT MANTENIMIENTO -> LockTask allowlist vaciada | Paquetes=$totalConfirmado"
            )
            true
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> error vaciando LockTask allowlist: ${e.message}")
            false
        }
    }

    fun suspenderPoliticasKiosco(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyAdminReceiver::class.java)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                AppLog.warning("IT MANTENIMIENTO -> no es Device Owner; no se puede abrir mantenimiento.")
                return false
            }

            val launcherNativo = buscarLauncherNativo(context)
            guardarHomeMantenimiento(context, launcherNativo)

            if (launcherNativo == null) {
                AppLog.error("IT MANTENIMIENTO -> no se encontró One UI Home.")
                return false
            }

            // 1) Con LockTask ya detenido por MainActivity, eliminamos cualquier allowlist residual.
            if (!refrescarAllowlistMantenimiento(context)) {
                return false
            }

            // 2) Fijamos primero el HOME OEM mientras TGT todavía sigue habilitado. Así una
            // interrupción en mitad de la transición nunca deja el dispositivo sin candidato HOME.
            try {
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
                AppLog.info("IT MANTENIMIENTO -> preferencia HOME persistente de TGT eliminada.")
            } catch (e: Exception) {
                AppLog.warning("IT MANTENIMIENTO -> no se pudo limpiar HOME TGT: ${e.message}")
            }

            try {
                dpm.clearPackagePersistentPreferredActivities(admin, launcherNativo.packageName)
            } catch (_: Exception) {
            }

            dpm.addPersistentPreferredActivity(admin, filtroHome(), launcherNativo)
            AppLog.success(
                "IT MANTENIMIENTO -> One UI Home fijado como HOME temporal: ${launcherNativo.flattenToShortString()}"
            )

            val homeAntesDeDeshabilitar = resolverHomeActual(context)
            if (homeAntesDeDeshabilitar != launcherNativo) {
                AppLog.error(
                    "IT MANTENIMIENTO -> HOME OEM no quedó fijado; resuelto=" +
                        (homeAntesDeDeshabilitar?.flattenToShortString() ?: "<ninguno>")
                )
                return false
            }

            // 3) Sólo después de verificar One UI, TGT deja de ser candidato HOME.
            try {
                habilitarHomeAlias(context, false)
            } catch (e: Exception) {
                AppLog.error("IT MANTENIMIENTO -> no se pudo deshabilitar HOME alias TGT: ${e.message}")
                return false
            }

            val homeFinal = resolverHomeActual(context)
            if (homeFinal != launcherNativo) {
                AppLog.error(
                    "IT MANTENIMIENTO -> HOME final no coincide con OEM; rollback seguro. Resuelto=" +
                        (homeFinal?.flattenToShortString() ?: "<ninguno>")
                )
                habilitarHomeAlias(context, true)
                return false
            }

            // 4) Abrimos el launcher Samsung por componente exacto. El diagnóstico ADB ya confirmó
            // que este componente arranca correctamente cuando LockTask está en NONE.
            val explicitHome = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
                component = launcherNativo
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(explicitHome)
            AppLog.success(
                "IT MANTENIMIENTO -> One UI Home abierto explícitamente: ${launcherNativo.flattenToShortString()}"
            )

            true
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> error cediendo HOME a One UI: ${e.message}")
            false
        }
    }

    fun resolverHomeActual(context: Context): ComponentName? {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            result?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        } catch (_: Exception) {
            null
        }
    }

    fun abrirEscritorioSistema(context: Context): Boolean {
        return try {
            val launcherNativo = leerHomeMantenimiento(context) ?: buscarLauncherNativo(context)
            if (launcherNativo == null) {
                AppLog.error("IT MANTENIMIENTO -> no hay launcher nativo guardado para abrir.")
                return false
            }

            val explicitHome = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
                component = launcherNativo
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

            context.startActivity(explicitHome)
            AppLog.success(
                "IT MANTENIMIENTO -> One UI Home abierto explícitamente: ${launcherNativo.flattenToShortString()}"
            )
            true
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> no se pudo abrir One UI Home: ${e.message}")
            false
        }
    }

    fun limpiarHomeMantenimiento(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyAdminReceiver::class.java)
            val launcherNativo = leerHomeMantenimiento(context)

            if (dpm.isDeviceOwnerApp(context.packageName) && launcherNativo != null) {
                try {
                    dpm.clearPackagePersistentPreferredActivities(admin, launcherNativo.packageName)
                    AppLog.info(
                        "IT MANTENIMIENTO -> HOME temporal OEM retirado: ${launcherNativo.flattenToShortString()}"
                    )
                } catch (e: Exception) {
                    AppLog.warning("IT MANTENIMIENTO -> no se pudo retirar HOME temporal OEM: ${e.message}")
                }
            }
        } finally {
            guardarHomeMantenimiento(context, null)
            prepararHomeProduccion(context)
        }
    }

    fun reprogramarSiActivo(context: Context) {
        if (!estaActivo(context) || esHastaManual(context)) return
        val hasta = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_HASTA, 0L)
        if (hasta > System.currentTimeMillis()) programarAlarma(context, hasta)
    }

    private fun pendingIntentExpiracion(context: Context): PendingIntent {
        val intent = Intent(context, MaintenanceExpiryReceiver::class.java).apply {
            action = ACTION_EXPIRA
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_EXPIRA, intent, flags)
    }

    private fun programarAlarma(context: Context, hasta: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntentExpiracion(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, hasta, pi)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, hasta, pi)
            }
            AppLog.info("IT MANTENIMIENTO -> cierre automático programado para $hasta.")
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> no se pudo programar cierre automático: ${e.message}")
        }
    }

    private fun cancelarAlarma(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntentExpiracion(context))
        } catch (_: Exception) {
        }
    }
}

object KioskPolicyManager {
    fun aplicar(context: Context, forceBlindado: Boolean = false): Boolean {
        if (MaintenanceModeManager.estaActivo(context)) {
            AppLog.info("KIOSK POLICY -> omitida porque Modo Mantenimiento IT está activo.")
            return false
        }
        if (!forceBlindado && ManagedModeStore.isManagedFree(context)) {
            AppLog.info("KIOSK POLICY -> omitida porque el modo deseado es LIBRE GESTIONADO.")
            return false
        }

        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyAdminReceiver::class.java)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                AppLog.warning("KIOSK POLICY -> el Launcher no es Device Owner; no se puede fijar HOME persistente.")
                return false
            }

            // Retiramos cualquier HOME OEM temporal usado en mantenimiento y rehabilitamos HOME TGT.
            MaintenanceModeManager.limpiarHomeMantenimiento(context)
            try {
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            } catch (_: Exception) {
            }

            val prefs = context.getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val appsConfiguradas = (prefs.getString("apps_permitidas", "") ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val paquetes = linkedSetOf(context.packageName)
            paquetes.addAll(appsConfiguradas)
            dpm.setLockTaskPackages(admin, paquetes.toTypedArray())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }

            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val homeActivity = ComponentName(context.packageName, "${context.packageName}.HomeActivity")
            dpm.addPersistentPreferredActivity(admin, homeFilter, homeActivity)

            val homeResuelto = MaintenanceModeManager.resolverHomeActual(context)
            if (homeResuelto != homeActivity) {
                AppLog.error(
                    "KIOSK POLICY -> HOME TGT no quedó resuelto; actual=" +
                        (homeResuelto?.flattenToShortString() ?: "<ninguno>")
                )
                return false
            }

            val permitido = dpm.isLockTaskPermitted(context.packageName)
            AppLog.success(
                "KIOSK POLICY -> HOME persistente aplicado | LockTaskPermitido=$permitido | " +
                        "AppsPermitidas=${paquetes.size}"
            )
            permitido
        } catch (e: Exception) {
            AppLog.error("KIOSK POLICY -> error aplicando políticas base: ${e.message}")
            false
        }
    }
}

class BootAndReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val accion = intent?.action ?: return
        val ctx = context ?: return

        AppLog.inicializar(ctx)
        MdmHeartbeatScheduler.schedule(ctx)

        when (accion) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Un reinicio cancela el mantenimiento IT temporal, pero conserva el modo
                // gestionado autenticado y persistido.
                MaintenanceModeManager.finalizarEstado(ctx, "reinicio del dispositivo")
                if (ManagedModeStore.isManagedFree(ctx)) {
                    val libreOk = MaintenanceModeManager.suspenderPoliticasKiosco(ctx)
                    AppLog.info("BOOT -> LIBRE GESTIONADO restaurado=$libreOk")
                } else {
                    val politicaOk = KioskPolicyManager.aplicar(ctx)
                    AppLog.info("BOOT -> política kiosco aplicada=$politicaOk")
                }
                abrirLauncher(ctx)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Android Studio/OTA pueden reemplazar la APK durante mantenimiento.
                // En ese caso NO reblinda el terminal a mitad del trabajo de IT.
                if (MaintenanceModeManager.estaActivo(ctx)) {
                    MaintenanceModeManager.reprogramarSiActivo(ctx)
                    val suspendido = MaintenanceModeManager.suspenderPoliticasKiosco(ctx)
                    AppLog.info(
                        "PACKAGE_REPLACED -> mantenimiento sigue activo (${MaintenanceModeManager.descripcion(ctx)}). " +
                                "HOME alias continúa deshabilitado. Suspendido=$suspendido"
                    )
                } else if (ManagedModeStore.isManagedFree(ctx)) {
                    val libreOk = MaintenanceModeManager.suspenderPoliticasKiosco(ctx)
                    AppLog.info("PACKAGE_REPLACED -> LIBRE GESTIONADO restaurado=$libreOk")
                    abrirLauncher(ctx)
                } else {
                    AppLog.info("PACKAGE_REPLACED -> mantenimiento inactivo. Restaurando HOME/Kiosco...")
                    val politicaOk = KioskPolicyManager.aplicar(ctx)
                    AppLog.info("PACKAGE_REPLACED -> política kiosco aplicada=$politicaOk")
                    abrirLauncher(ctx)
                }
            }
        }
    }

    private fun abrirLauncher(context: Context) {
        try {
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(i)
        } catch (e: Exception) {
            AppLog.error("BOOT/PACKAGE_REPLACED -> no se pudo abrir Launcher: ${e.message}")
        }
    }
}

class MaintenanceExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        AppLog.inicializar(ctx)

        if (!MaintenanceModeManager.estaConfigurado(ctx)) return

        if (MaintenanceModeManager.esHastaManual(ctx)) {
            AppLog.info("IT MANTENIMIENTO -> alarma ignorada: modo hasta bloqueo manual.")
            return
        }

        val restante = MaintenanceModeManager.restanteMs(ctx)
        if (restante > 1500L) {
            // AlarmManager inexacto puede despertar antes/después: reprogramamos si aún no toca.
            MaintenanceModeManager.reprogramarSiActivo(ctx)
            return
        }

        MaintenanceModeManager.finalizarEstado(ctx, "tiempo agotado")
        if (ManagedModeStore.isManagedFree(ctx)) {
            val libreOk = MaintenanceModeManager.suspenderPoliticasKiosco(ctx)
            AppLog.success("IT MANTENIMIENTO -> tiempo agotado. LIBRE restaurado=$libreOk")
        } else {
            val politicaOk = KioskPolicyManager.aplicar(ctx)
            AppLog.success("IT MANTENIMIENTO -> tiempo agotado. Kiosco restaurado=$politicaOk")
        }

        try {
            val i = Intent(ctx, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            ctx.startActivity(i)
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> no se pudo traer Launcher al frente al caducar: ${e.message}")
        }
    }
}


class MaintenancePackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (!MaintenanceModeManager.estaActivo(ctx)) return

        AppLog.inicializar(ctx)
        val pkg = intent?.data?.schemeSpecificPart ?: "<desconocido>"
        AppLog.info("IT MANTENIMIENTO -> cambio de paquete detectado: ${intent?.action} $pkg")
        MaintenanceModeManager.refrescarAllowlistMantenimiento(ctx)
    }
}

object AppLog {
    private const val MAX_LINEAS_MEMORIA = 100
    private val historialMemoria = mutableListOf<String>()
    private var contextoApp: Context? = null

    fun inicializar(context: Context) {
        contextoApp = context.applicationContext
    }

    fun info(mensaje: String) = registrar("INFO", "ℹ️", mensaje)
    fun success(mensaje: String) = registrar("SUCCESS", "✨", mensaje)
    fun warning(mensaje: String) = registrar("WARNING", "⚠️", mensaje)
    fun error(mensaje: String) = registrar("ERROR", "❌", mensaje)
    fun ota(mensaje: String) = registrar("OTA", "📦", mensaje)

    fun registrar(mensaje: String) {
        info(mensaje)
    }

    @Synchronized
    fun registrar(nivel: String, icono: String, mensaje: String) {
        val timeStamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
        val linea = "[$timeStamp] [$nivel] $icono $mensaje"

        synchronized(historialMemoria) {
            if (historialMemoria.size >= MAX_LINEAS_MEMORIA) {
                historialMemoria.removeAt(0)
            }
            historialMemoria.add(linea)
        }

        android.util.Log.d("TGT_KIOSCO", linea)

        val ctx = contextoApp
        if (ctx != null) {
            try {
                val archivo = File(ctx.filesDir, "kiosco_log_persistente.txt")
                if (archivo.exists() && archivo.length() > 500 * 1024) {
                    archivo.writeText("[AUTO-PURGE] Archivo reiniciado por límite de tamaño\n")
                }
                archivo.appendText("$linea\n")
            } catch (e: Exception) {
                android.util.Log.e("TGT_KIOSCO", "Error escribiendo log en disco: ${e.message}")
            }
        }
    }

    fun obtenerLogs(): String {
        val ctx = contextoApp
        if (ctx != null) {
            try {
                val archivo = File(ctx.filesDir, "kiosco_log_persistente.txt")
                if (archivo.exists()) {
                    val contenido = archivo.readText()
                    if (contenido.isNotBlank()) {
                        val lineas = contenido.lines()
                        return if (lineas.size > 120) {
                            lineas.takeLast(120).joinToString("\n")
                        } else {
                            contenido
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TGT_KIOSCO", "Error leyendo archivo de logs: ${e.message}")
            }
        }

        synchronized(historialMemoria) {
            return if (historialMemoria.isEmpty()) "No hay registros de eventos todavía." else historialMemoria.joinToString("\n")
        }
    }
}

object OtaRuntimeState {
    @Volatile
    var enCurso: Boolean = false
        private set

    @Volatile
    var versionObjetivo: Int = -1
        private set

    @Synchronized
    fun intentarIniciar(version: Int): Boolean {
        if (enCurso) return false
        enCurso = true
        versionObjetivo = version
        return true
    }

    @Synchronized
    fun finalizar() {
        enCurso = false
        versionObjetivo = -1
    }
}

@Suppress("DEPRECATION")
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        AppLog.inicializar(context)

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Sin mensaje adicional"
        val sessionId = intent.getIntExtra("ota_session_id", -1)

        AppLog.info(
            "OTA PackageInstaller callback -> Session=$sessionId | Status=$status | Message=$msg"
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.warning(
                    "OTA -> STATUS_PENDING_USER_ACTION (-1). Android solicita confirmación para continuar."
                )

                val confirmationIntent: Intent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                    }

                if (confirmationIntent == null) {
                    AppLog.error(
                        "OTA -> Android pidió confirmación, pero no devolvió Intent.EXTRA_INTENT."
                    )
                    OtaRuntimeState.finalizar()
                    Toast.makeText(
                        context,
                        "⚠️ Android requiere confirmar la actualización, pero no pudo abrirse el instalador.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                try {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmationIntent)

                    AppLog.info(
                        "OTA -> pantalla de confirmación de Android abierta correctamente."
                    )
                    Toast.makeText(
                        context,
                        "📦 Confirma la actualización de Launcher TGT.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    OtaRuntimeState.finalizar()
                    AppLog.error(
                        "OTA -> no se pudo abrir la confirmación de Android: ${e.message}"
                    )
                    Toast.makeText(
                        context,
                        "❌ No se pudo abrir el instalador de Android.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                OtaRuntimeState.finalizar()
                AppLog.success(
                    "OTA -> STATUS_SUCCESS. Actualización instalada correctamente. Session=$sessionId"
                )
                Toast.makeText(
                    context,
                    "🎉 ¡Actualización aplicada con éxito!",
                    Toast.LENGTH_LONG
                ).show()
            }

            else -> {
                OtaRuntimeState.finalizar()
                AppLog.error(
                    "FALLO REAL INSTALACIÓN OTA [Session=$sessionId | Cod=$status]: $msg"
                )
                Toast.makeText(
                    context,
                    "❌ Error OTA [Cod: $status]: $msg",
                    Toast.LENGTH_LONG
                ).show()

                try {
                    val prefs = context.getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                    val ubicacion = prefs.getString("ubicacion_dispositivo", "Desconocida")
                    val numeroIT = prefs.getString("telefono_it", "") ?: ""

                    val permisoSmsConcedido =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                                context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

                    if (numeroIT.isNotEmpty() && permisoSmsConcedido) {
                        val mensajeError =
                            "🚨 ALERTA KIOSCO [$ubicacion]: Fallo real al instalar OTA. " +
                                    "Código=$status. Motivo=$msg"

                        val smsManager: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            SmsManager.getDefault()
                        }

                        if (smsManager != null) {
                            val parts = smsManager.divideMessage(mensajeError)
                            smsManager.sendMultipartTextMessage(numeroIT, null, parts, null, null)
                            AppLog.info("Alerta de fallo OTA enviada por SMS a IT de forma automática.")
                        }
                    } else if (numeroIT.isNotEmpty()) {
                        AppLog.warning(
                            "OTA -> no se envía SMS de fallo porque SEND_SMS no está concedido."
                        )
                    }
                } catch (e: Exception) {
                    AppLog.error("Error enviando alerta de fallo OTA: ${e.message}")
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORZAR_OTA = "com.grupotgt.launcherkioscotgt.EXTRA_FORZAR_OTA"
        const val EXTRA_MANTENIMIENTO_MS = "com.grupotgt.launcherkioscotgt.EXTRA_MANTENIMIENTO_MS"
        const val EXTRA_ABRIR_AJUSTES = "com.grupotgt.launcherkioscotgt.EXTRA_ABRIR_AJUSTES"
        const val EXTRA_FINALIZAR_MANTENIMIENTO = "com.grupotgt.launcherkioscotgt.EXTRA_FINALIZAR_MANTENIMIENTO"
        const val EXTRA_INTERNAL_COMMAND_TOKEN = "com.grupotgt.launcherkioscotgt.EXTRA_INTERNAL_COMMAND_TOKEN"
        const val EXTRA_RECONCILE_MANAGED_MODE = "com.grupotgt.launcherkioscotgt.EXTRA_RECONCILE_MANAGED_MODE"
        const val EXTRA_APPLY_PILOT_OTA = "com.grupotgt.launcherkioscotgt.EXTRA_APPLY_PILOT_OTA"
        const val ACTION_RECONCILE_MANAGED_MODE = "com.grupotgt.launcherkioscotgt.action.RECONCILE_MANAGED_MODE"
        const val ACTION_APPLY_PILOT_OTA = "com.grupotgt.launcherkioscotgt.action.APPLY_PILOT_OTA"
    }

    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"
    private val URL_OTA_JSON = "https://grupotgt.github.io/actualizaciones-launcher/version.json"

    private val handler = Handler(Looper.getMainLooper())

    private val intervaloSyncAgenda = 5 * 60 * 1000L
    private val intervaloCheckOTA = 2 * 60 * 1000L

    private var wakeLockLlamada: android.os.PowerManager.WakeLock? = null

    private var contactosGuardados = listOf<Pair<String, String>>()
    private var whitelistGlobal = listOf<String>()
    @Volatile private var usandoConfigCanonica = false

    private var handlerInactividad = Handler(Looper.getMainLooper())
    private var minutosInactividadConfig = 10
    private var dialogScreensaver: Dialog? = null
    private val handlerMovimientoScreensaver = Handler(Looper.getMainLooper())
    private var contenidoScreensaver: View? = null
    private var indiceMovimientoScreensaver = 0
    private var ultimoEstadoConexionTexto = "CONEXIÓN · --"
    private var ultimoEstadoConexionColor = Color.parseColor("#FFE58A")

    private val runnableMovimientoScreensaver = object : Runnable {
        override fun run() {
            val contenido = contenidoScreensaver ?: return
            if (!animacionesSistemaActivas()) return

            val posiciones = arrayOf(
                -14 to -34,
                16 to -18,
                10 to 34,
                -16 to 20,
                0 to 0
            )
            val (xDp, yDp) = posiciones[indiceMovimientoScreensaver % posiciones.size]
            indiceMovimientoScreensaver++
            contenido.animate()
                .translationX(dpMain(xDp).toFloat())
                .translationY(dpMain(yDp).toFloat())
                .setDuration(1600L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (contenidoScreensaver === contenido) {
                        handlerMovimientoScreensaver.postDelayed(this, 30_000L)
                    }
                }
                .start()
        }
    }

    // TextToSpeech Motor
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var ttsPendienteGlobal = ""
    private var ttsPendienteLocal = ""
    private var volumenAntesDeTts: Int? = null

    // Memoria MDM
    private var mdmVolumenObjetivo: Int? = null
    private var ultimoComandoEjecutado = ""
    private var ultimoTimeoutEjecutado = ""
    private var ultimoTTSLocal = ""
    private var ultimoTTSGlobal = ""
    private var ultimoReinicioEjecutado = ""

    private var contadorVolumenAbajo = 0
    private var contadorVolumenArriba = 0
    private var primeraReanudacion = true

    private var dialogLlamadaActiva: Dialog? = null
    private var dialogLlamadaEntrante: Dialog? = null
    private var dialogBloqueoRobo: Dialog? = null
    private val handlerLlamadas = Handler(Looper.getMainLooper())

    private val runnableScreensaver = Runnable {
        mostrarScreensaverTGT()
    }

    private val runnableAutoSyncAgenda = object : Runnable {
        override fun run() {
            AppLog.info("Sincronización automática periódica...")
            // La telemetría se envía DESPUÉS de recibir y aplicar la configuración de Sheets.
            // Antes se enviaba aquí inmediatamente y competía con la descarga asíncrona,
            // por lo que Inventario podía registrar el brillo/volumen ANTERIOR.
            descargarAgendaNube(modoSilencioso = true)
            handler.postDelayed(this, intervaloSyncAgenda)
        }
    }

    private val runnableAutoCheckOTA = object : Runnable {
        override fun run() {
            AppLog.ota("Comprobación automática periódica de OTA en nube...")
            comprobarActualizacionOTA()
            handler.postDelayed(this, intervaloCheckOTA)
        }
    }

    private val runnableReloj = object : Runnable {
        override fun run() {
            actualizarRelojEnPantalla()
            verificarHoraReinicioAutomatico()
            handler.postDelayed(this, 1000)
        }
    }

    private val runnableEstadoDispositivo = object : Runnable {
        override fun run() {
            actualizarIndicadoresReales()
            handler.postDelayed(this, 5000)
        }
    }

    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        val numeroEntranteRaw = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        if (numeroEntranteRaw == null) return

                        val numeroEntrante = numeroEntranteRaw
                        var nombreCaller = "Desconocido"
                        val numLimpio = numeroEntrante.replace(" ", "").replace("+34", "").replace("-", "")

                        if (whitelistGlobal.isNotEmpty() || contactosGuardados.isNotEmpty()) {
                            val prefs = context?.getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                            val itPhone = prefs?.getString("telefono_it", "")?.replace(" ", "")?.replace("+34", "") ?: ""

                            var estaPermitido = false
                            if (numLimpio.isNotEmpty()) {
                                val enWhitelist = whitelistGlobal.any { it.contains(numLimpio) || numLimpio.contains(it) }
                                val enBotones = contactosGuardados.any {
                                    val numAgenda = it.second.replace(" ", "").replace("+34", "").replace("-", "")
                                    numAgenda.isNotEmpty() && (numAgenda.contains(numLimpio) || numLimpio.contains(numAgenda))
                                }
                                val esIT = itPhone.isNotEmpty() && (itPhone.contains(numLimpio) || numLimpio.contains(itPhone))
                                estaPermitido = enWhitelist || enBotones || esIT
                            }

                            if (whitelistGlobal.isNotEmpty() && !estaPermitido) {
                                AppLog.warning("🛡️ FIREWALL: Llamada bloqueada '$numeroEntrante'.")
                                colgarLlamadaReal()
                                return
                            }
                        }

                        if (numLimpio.isNotEmpty()) {
                            val contactoMatch = contactosGuardados.find {
                                val numAgenda = it.second.replace(" ", "").replace("+34", "").replace("-", "")
                                numAgenda.isNotEmpty() && (numAgenda.contains(numLimpio) || numLimpio.contains(numAgenda))
                            }
                            if (contactoMatch != null) nombreCaller = contactoMatch.first
                        }
                        mostrarPantallaLlamadaEntrante(nombreCaller, numeroEntrante)
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        cancelarTareasLlamadaPendientes()
                        dialogLlamadaActiva?.dismiss()
                        dialogLlamadaEntrante?.dismiss()
                        liberarPantalla()
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        dialogLlamadaEntrante?.dismiss()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            AppLog.inicializar(this)
            registrarArranqueReal()

            // Seguridad primero, salvo que IT haya abierto un mantenimiento válido.
            if (MaintenanceModeManager.estaActivo(this)) {
                MaintenanceModeManager.reprogramarSiActivo(this)
                AppLog.warning(
                    "IT MANTENIMIENTO -> Launcher iniciado en modo mantenimiento (${MaintenanceModeManager.descripcion(this)})."
                )
            } else if (ManagedModeStore.isManagedFree(this)) {
                AppLog.info("MDM MODO -> arranque conserva LIBRE GESTIONADO; no se aplica kiosco.")
            } else {
                KioskPolicyManager.aplicar(this)
            }
            Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                val errorMsg = "CRASH: ${throwable.localizedMessage}"
                AppLog.error(errorMsg)
                enviarAlertaITCRasante("⚠️ CRASH CRÍTICO: ${throwable.localizedMessage}")
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }

            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val resultadoIdioma = tts.setLanguage(Locale("es", "ES"))
                    if (resultadoIdioma == TextToSpeech.LANG_MISSING_DATA || resultadoIdioma == TextToSpeech.LANG_NOT_SUPPORTED) {
                        isTtsReady = false
                        AppLog.error("MDM TTS: español (es-ES) no disponible en el motor instalado. Resultado=$resultadoIdioma")
                    } else {
                        isTtsReady = true
                        AppLog.success("MDM TTS: motor inicializado correctamente. Engine=${tts.defaultEngine}")
                        configurarListenerTts()
                        handler.post { reproducirTtsPendienteSiProcede() }
                    }
                } else {
                    isTtsReady = false
                    AppLog.error("MDM TTS: fallo de inicialización. status=$status")
                }
            }

            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            minutosInactividadConfig = prefs.getInt("minutos_inactividad_custom", 10)
            val volumenPersistido = prefs.getInt("mdm_volumen_objetivo", -1)
            mdmVolumenObjetivo = volumenPersistido.takeIf { it in 0..100 }
            ultimoComandoEjecutado = prefs.getString("mdm_ultimo_comando", "") ?: ""
            ultimoTimeoutEjecutado = prefs.getString("mdm_ultimo_timeout", "") ?: ""
            ultimoTTSLocal = prefs.getString("mdm_ultimo_tts_local", "") ?: ""
            ultimoTTSGlobal = prefs.getString("mdm_ultimo_tts_global", "") ?: ""

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }

            setContentView(R.layout.activity_main)
            configurarVentanaPrincipalRetro()

            procesarIntentMantenimiento(intent)

            if (!MaintenanceModeManager.estaActivo(this)) {
                handler.post { reconciliarModoGestionado("onCreate") }
            } else {
                AppLog.info("IT MANTENIMIENTO -> no se activa LockTask en onCreate.")
            }

            if (prefs.getBoolean("mdm_robo_activo", false)) {
                handler.post { mostrarPantallaRobo() }
            }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
            })

            solicitarPermisos()
            solicitarAdministradorDispositivo()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                // Un Device Owner (API 28+) usa DPM.setSystemSetting para brillo/timeout y no
                // necesita sacar al kiosco a la pantalla especial de WRITE_SETTINGS.
                val usaAjustesViaDeviceOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm.isDeviceOwnerApp(packageName)
                if (!usaAjustesViaDeviceOwner && !Settings.System.canWrite(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.data = Uri.parse("package:" + packageName)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { startActivity(intent) } catch (e: Exception) {}
                }
            }

            cargarIdentificacionYLogo()
            MdmHeartbeatScheduler.schedule(this)
            MdmEnrollmentCoordinator.enroll(this, MdmBridgeConfig.ENDPOINT) { enrollment ->
                if (enrollment.approvalState == "APPROVED") {
                    runOnUiThread {
                        if (enrollment.commandsEnabled) {
                            val accepted = ManagedModeStore.acceptAuthenticated(
                                this,
                                enrollment.mode,
                                enrollment.modeRevision
                            )
                            if (accepted) {
                                reconciliarModoGestionado("SAFE BRIDGE rev=${enrollment.modeRevision}")
                            } else {
                                AppLog.warning(
                                    "MDM MODO -> orden rechazada por revisión obsoleta o contradictoria."
                                )
                            }
                        }
                        MdmConfigCache(this).load()
                            .onSuccess(::aplicarConfigCanonica)
                    }
                }
            }
            cargarAgendaDesdeCache()
            descargarAgendaNube(modoSilencioso = true)

            val otaPilotoAutenticada = consumirComandoInterno(
                intent,
                EXTRA_APPLY_PILOT_OTA,
                InternalCommandGate.ACTION_APPLY_PILOT_OTA
            )
            val otaForzadaDesdePanel = consumirComandoInterno(
                intent,
                EXTRA_FORZAR_OTA,
                InternalCommandGate.ACTION_FORCE_OTA
            )
            if (otaPilotoAutenticada) {
                comprobarActualizacionPiloto()
            } else if (otaForzadaDesdePanel) {
                AppLog.ota("OTA manual solicitada desde Panel IT. Ejecutando motor OTA único.")
                comprobarActualizacionOTA(forzada = true)
            } else {
                comprobarActualizacionOTA()
            }

            configurarBotonSecreto()
            configurarChivatoSincro()

            handler.post(runnableEstadoDispositivo)
            handler.post(runnableReloj)
            handler.postDelayed(runnableAutoSyncAgenda, intervaloSyncAgenda)
            handler.postDelayed(runnableAutoCheckOTA, intervaloCheckOTA)

            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(callStateReceiver, filter)

            reiniciarTemporizadorInactividad()
            // La sincronización inicial ya enviará la telemetría una vez aplicada la configuración
            // recibida de Google Sheets. Evitamos un heartbeat prematuro con valores antiguos.

        } catch (e: Exception) {
            AppLog.error("Error en onCreate: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun registrarArranqueReal() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val esDeviceOwner = dpm.isDeviceOwnerApp(packageName)

            AppLog.info(
                "Launcher TGT iniciado | Runtime=V62.8-HOME-HANDOFF | VersionName=$versionName | VersionCode=$versionCode | " +
                        "Android=${Build.VERSION.RELEASE} | API=${Build.VERSION.SDK_INT} | DeviceOwner=$esDeviceOwner"
            )
        } catch (e: Exception) {
            AppLog.warning("No se pudo obtener versión real al arrancar: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (procesarIntentMantenimiento(intent)) return

        if (intent.action == ACTION_RECONCILE_MANAGED_MODE) {
            if (!consumirComandoInterno(
                    intent,
                    EXTRA_RECONCILE_MANAGED_MODE,
                    InternalCommandGate.ACTION_RECONCILE_MANAGED_MODE
                )
            ) return
            reconciliarModoGestionado("heartbeat firmado")
            MdmConfigCache(this).load().onSuccess(::aplicarConfigCanonica)
            return
        }

        if (intent.action == ACTION_APPLY_PILOT_OTA) {
            if (!consumirComandoInterno(
                    intent,
                    EXTRA_APPLY_PILOT_OTA,
                    InternalCommandGate.ACTION_APPLY_PILOT_OTA
                )
            ) return
            comprobarActualizacionPiloto()
            return
        }

        if (consumirComandoInterno(
                intent,
                EXTRA_FORZAR_OTA,
                InternalCommandGate.ACTION_FORCE_OTA
            )
        ) {
            AppLog.ota("OTA manual recibida con MainActivity ya activa. Ejecutando motor OTA único.")
            comprobarActualizacionOTA(forzada = true)
        }
    }

    private fun procesarIntentMantenimiento(intent: Intent?): Boolean {
        if (intent == null) return false

        if (intent.getBooleanExtra(EXTRA_FINALIZAR_MANTENIMIENTO, false)) {
            if (!consumirComandoInterno(
                    intent,
                    EXTRA_FINALIZAR_MANTENIMIENTO,
                    InternalCommandGate.ACTION_FINISH_MAINTENANCE
                )
            ) return true
            finalizarModoMantenimiento("cierre manual desde Panel IT")
            return true
        }

        if (intent.hasExtra(EXTRA_MANTENIMIENTO_MS)) {
            val duracionMs = intent.getLongExtra(EXTRA_MANTENIMIENTO_MS, 15L * 60L * 1000L)
            val abrirAjustes = intent.getBooleanExtra(EXTRA_ABRIR_AJUSTES, true)
            intent.removeExtra(EXTRA_ABRIR_AJUSTES)
            if (!consumirComandoInterno(
                    intent,
                    EXTRA_MANTENIMIENTO_MS,
                    InternalCommandGate.ACTION_START_MAINTENANCE
                )
            ) return true
            activarModoMantenimiento(duracionMs, abrirAjustes)
            return true
        }

        return false
    }

    private fun consumirComandoInterno(intent: Intent?, extra: String, action: String): Boolean {
        if (intent?.hasExtra(extra) != true) return false
        val token = intent.getStringExtra(EXTRA_INTERNAL_COMMAND_TOKEN)
        intent.removeExtra(extra)
        intent.removeExtra(EXTRA_INTERNAL_COMMAND_TOKEN)
        val autorizado = InternalCommandGate.consume(this, action, token)
        if (!autorizado) {
            AppLog.warning("SEGURIDAD -> comando interno rechazado: $action")
        }
        return autorizado
    }

    private fun activarModoMantenimiento(duracionMs: Long, abrirAjustes: Boolean) {
        try {
            // Marcamos mantenimiento ANTES de tocar la tarea. Así ningún onResume puede
            // reactivar la política kiosco durante la transición.
            val hasta = MaintenanceModeManager.activar(this, duracionMs)
            val descripcion = if (hasta == MaintenanceModeManager.HASTA_MANUAL) {
                "hasta bloqueo manual"
            } else {
                MaintenanceModeManager.descripcion(this)
            }

            AppLog.warning("IT MANTENIMIENTO -> INICIANDO ($descripcion)")

            // MainActivity es quien inició startLockTask(), por tanto es quien debe cerrarlo.
            try {
                stopLockTask()
                AppLog.success("IT MANTENIMIENTO -> stopLockTask ejecutado desde MainActivity.")
            } catch (e: Exception) {
                AppLog.warning("IT MANTENIMIENTO -> stopLockTask devolvió: ${e.message}")
            }

            // Respaldo DPC: vaciar la allowlist también evita reentrada accidental en LockTask.
            val allowlistVaciada = MaintenanceModeManager.refrescarAllowlistMantenimiento(this)

            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val estado = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.lockTaskModeState
            } else {
                @Suppress("DEPRECATION")
                if (am.isInLockTaskMode) 1 else 0
            }

            AppLog.info(
                "IT MANTENIMIENTO -> después de stopLockTask | LockTaskState=$estado | AllowlistVaciada=$allowlistVaciada"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                estado != android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                MaintenanceModeManager.finalizarEstado(this, "LockTask no llegó a NONE")
                KioskPolicyManager.aplicar(this)
                entrarEnKioscoSiPermitido("rollback mantenimiento: LockTask sigue activo")
                Toast.makeText(
                    this,
                    "❌ No se pudo liberar el modo kiosco. El terminal sigue protegido.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val homeOk = MaintenanceModeManager.suspenderPoliticasKiosco(this)
            if (!homeOk) {
                MaintenanceModeManager.finalizarEstado(this, "fallo al ceder HOME a One UI")
                KioskPolicyManager.aplicar(this)
                entrarEnKioscoSiPermitido("rollback mantenimiento: HOME")
                Toast.makeText(
                    this,
                    "❌ No se pudo ceder el escritorio a One UI. El terminal sigue protegido.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            Toast.makeText(
                this,
                "🛠️ Mantenimiento IT activo: $descripcion",
                Toast.LENGTH_LONG
            ).show()

            // Reaplicamos una vez tras el cambio de política. En Android recientes la política
            // persistente puede notificarse de forma asíncrona; el alias TGT ya permanece deshabilitado.
            Handler(Looper.getMainLooper()).postDelayed({
                if (MaintenanceModeManager.estaActivo(this)) {
                    MaintenanceModeManager.suspenderPoliticasKiosco(this)
                }
            }, 600L)

            AppLog.success(
                "IT MANTENIMIENTO -> salida real preparada: LockTask NONE + HOME One UI + TGT HOME deshabilitado."
            )
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> error activando mantenimiento: ${e.message}")
        }
    }

    private fun finalizarModoMantenimiento(motivo: String) {
        try {
            MaintenanceModeManager.finalizarEstado(this, motivo)
            reconciliarModoGestionado("fin mantenimiento")
            Toast.makeText(this, "Modo gestionado restaurado", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.error("IT MANTENIMIENTO -> error restaurando kiosco: ${e.message}")
        }
    }

    private fun reconciliarModoGestionado(origen: String) {
        if (MaintenanceModeManager.estaActivo(this)) {
            AppLog.info("MDM MODO -> reconciliación aplazada por mantenimiento IT activo.")
            return
        }

        val mode = ManagedModeStore.desiredMode(this)
        ManagedModeStore.markApplying(this, mode)
        when (mode) {
            ManagedMode.BLINDADO -> {
                val policyOk = KioskPolicyManager.aplicar(this, forceBlindado = true)
                entrarEnKioscoSiPermitido("modo BLINDADO: $origen")
                val lockTaskOk = lockTaskEstaActivo()
                if (policyOk && lockTaskOk) {
                    ManagedModeStore.markApplied(this, mode)
                    AppLog.success("MDM MODO -> BLINDADO aplicado y verificado desde $origen.")
                } else {
                    ManagedModeStore.markError(
                        this,
                        "BLINDADO incompleto: policy=$policyOk lockTask=$lockTaskOk"
                    )
                    AppLog.error(
                        "MDM MODO -> BLINDADO no confirmado: policy=$policyOk lockTask=$lockTaskOk"
                    )
                }
            }

            ManagedMode.LIBRE_GESTIONADO -> {
                try {
                    stopLockTask()
                } catch (error: Exception) {
                    AppLog.warning("MDM MODO -> stopLockTask en LIBRE devolvió: ${error.message}")
                }
                val lockTaskLibre = !lockTaskEstaActivo()
                val homeLibre = lockTaskLibre && MaintenanceModeManager.suspenderPoliticasKiosco(this)
                if (lockTaskLibre && homeLibre) {
                    ManagedModeStore.markApplied(this, mode)
                    AppLog.success(
                        "MDM MODO -> LIBRE GESTIONADO aplicado; Device Owner conservado. Origen=$origen"
                    )
                } else {
                    ManagedModeStore.markError(
                        this,
                        "LIBRE incompleto: lockTaskLibre=$lockTaskLibre homeLibre=$homeLibre"
                    )
                    AppLog.error("MDM MODO -> LIBRE falló; ejecutando rollback a BLINDADO.")
                    KioskPolicyManager.aplicar(this, forceBlindado = true)
                    entrarEnKioscoSiPermitido("rollback LIBRE", forceBlindado = true)
                }
            }
        }
    }

    private fun lockTaskEstaActivo(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activityManager.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            activityManager.isInLockTaskMode
        }
    }

    private fun configurarVentanaPrincipalRetro() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = findViewById<View>(R.id.mainRoot) ?: return
        val paddingLeftBase = root.paddingLeft
        val paddingRightBase = root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val zonaSegura = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                paddingLeftBase + zonaSegura.left,
                zonaSegura.top,
                paddingRightBase + zonaSegura.right,
                zonaSegura.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dpMain(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun animacionesSistemaActivas(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.animation.ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }

    private fun iniciarMovimientoScreensaver(contenido: View) {
        detenerMovimientoScreensaver()
        contenidoScreensaver = contenido
        indiceMovimientoScreensaver = 0
        if (animacionesSistemaActivas()) {
            handlerMovimientoScreensaver.postDelayed(runnableMovimientoScreensaver, 30_000L)
        }
    }

    private fun detenerMovimientoScreensaver() {
        handlerMovimientoScreensaver.removeCallbacks(runnableMovimientoScreensaver)
        contenidoScreensaver?.animate()?.cancel()
        contenidoScreensaver = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        reiniciarTemporizadorInactividad()
        if (dialogScreensaver != null && dialogScreensaver!!.isShowing) {
            dialogScreensaver?.dismiss()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun reiniciarTemporizadorInactividad() {
        handlerInactividad.removeCallbacks(runnableScreensaver)
        val milisegundosEspera = minutosInactividadConfig * 60 * 1000L
        handlerInactividad.postDelayed(runnableScreensaver, milisegundosEspera)
    }

    private fun mostrarScreensaverTGT() {
        if (dialogScreensaver != null && dialogScreensaver!!.isShowing) return
        if (dialogLlamadaEntrante?.isShowing == true || dialogLlamadaActiva?.isShowing == true) return

        runOnUiThread {
            try {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val nombreSeccion = prefs.getString("ubicacion_dispositivo", "Sección No Asignada")?.uppercase(Locale.getDefault()) ?: "SECCIÓN NO ASIGNADA"
                val logoUriString = prefs.getString("logo_uri_custom", "")

                val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val inflateParent = FrameLayout(this)
                val root = layoutInflater.inflate(
                    R.layout.dialog_screensaver_retro,
                    inflateParent,
                    false
                )
                val contenido = root.findViewById<View>(R.id.screensaverContent)
                val logo = root.findViewById<ImageView>(R.id.screensaverLogo)
                val antena = root.findViewById<RetroNetworkAntennaView>(R.id.screensaverAntenna)
                val terminal = root.findViewById<TextView>(R.id.screensaverTerminal)
                val conexion = root.findViewById<TextView>(R.id.screensaverConnection)

                terminal.text = nombreSeccion
                conexion.text = ultimoEstadoConexionTexto
                conexion.setTextColor(ultimoEstadoConexionColor)
                antena.setSignalActive(true)

                if (!logoUriString.isNullOrEmpty()) {
                    try {
                        logo.setImageURI(Uri.parse(logoUriString))
                    } catch (_: Exception) {
                        logo.setImageResource(R.drawable.logo_corporativo)
                    }
                }

                root.setOnClickListener {
                    dialog.dismiss()
                    reiniciarTemporizadorInactividad()
                }

                dialog.setContentView(root)
                dialog.setCancelable(false)
                dialog.setOnDismissListener {
                    antena.setSignalActive(false)
                    detenerMovimientoScreensaver()
                    if (dialogScreensaver === dialog) dialogScreensaver = null
                }

                dialog.window?.let { win ->
                    WindowCompat.setDecorFitsSystemWindows(win, false)
                    win.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
                    @Suppress("DEPRECATION")
                    win.addFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        win.attributes = win.attributes.apply {
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        }
                    }
                }

                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val zonaSegura = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout()
                    )
                    view.setPadding(
                        zonaSegura.left,
                        zonaSegura.top,
                        zonaSegura.right,
                        zonaSegura.bottom
                    )
                    insets
                }

                dialogScreensaver = dialog
                dialog.show()
                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                ViewCompat.requestApplyInsets(root)
                iniciarMovimientoScreensaver(contenido)
            } catch (e: Exception) {}
        }
    }

    private fun liberarPantalla() {
        try {
            wakeLockLlamada?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun actualizarRelojEnPantalla() {
        try {
            val horaActual = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            findViewById<TextView>(R.id.tvHoraReal)?.text = horaActual
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun verificarHoraReinicioAutomatico() {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            var horaProgramada = prefs.getString("hora_reinicio_seccion", "")?.trim() ?: ""

            if (horaProgramada.isNotEmpty() && horaProgramada != "No programado") {
                if (horaProgramada.contains(":")) {
                    val partes = horaProgramada.split(":")
                    if (partes.size >= 2) {
                        val h = partes[0].padStart(2, '0')
                        val m = partes[1].padStart(2, '0').take(2)
                        horaProgramada = "$h:$m"
                    }
                }

                val horaActualMinuto = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val diaActual = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                val identificadorReinicio = "$diaActual-$horaActualMinuto"

                if (horaActualMinuto == horaProgramada && ultimoReinicioEjecutado != identificadorReinicio) {
                    ultimoReinicioEjecutado = identificadorReinicio
                    AppLog.info("Hora de reinicio automático alcanzada ($horaProgramada)! Ejecutando orden...")
                    enviarAlertaITCRasante("🔄 Reinicio programado a las $horaProgramada en curso.")

                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminName = ComponentName(this, MyAdminReceiver::class.java)

                    if (dpm.isDeviceOwnerApp(packageName)) {
                        dpm.reboot(adminName)
                    } else {
                        Runtime.getRuntime().exec("reboot")
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.error("Error crítico en reinicio automático: ${e.message}")
        }
    }

    private fun actualizarTextoUltimaSincro() {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val ultimaSincro = prefs.getString("ultima_sincro", "Pendiente de red")
            val tvSincro = findViewById<TextView>(R.id.tvUltimaSincroVisual)
            tvSincro?.text = "🕒 Última sincronización: $ultimaSincro"

            tvSincro?.setOnClickListener {
                Toast.makeText(this, "🔄 Forzando actualización...", Toast.LENGTH_SHORT).show()
                descargarAgendaNube(modoSilencioso = false)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cargarAgendaDesdeCache() {
        try {
            MdmConfigCache(this).load().onSuccess { snapshot ->
                aplicarConfigCanonica(snapshot)
                return
            }
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val csvCache = prefs.getString("csv_cache_data", "") ?: ""
            if (csvCache.isNotEmpty()) {
                AppLog.info("Agenda cargada desde Caché Local")
                procesarYConstruirCSV(csvCache, ejecutarAccionesRemotas = false)
                actualizarTextoUltimaSincro()
            }
        } catch (e: Exception) {
            AppLog.error("Error cargando caché: ${e.message}")
        }
    }

    private fun aplicarConfigCanonica(snapshot: MdmConfigSnapshot) {
        usandoConfigCanonica = true
        val contactos = snapshot.contacts
            .filter { it.terminalCanCall }
            .map { it.name to it.phone }
        val whitelist = snapshot.contacts
            .filter { it.canCallTerminal }
            .map { it.phone }
            .distinct()
        val apps = snapshot.apps.map { it.packageName }.distinct()
        val settingsJson = JSONObject(snapshot.settings).toString()
        val persisted = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE).edit()
            .putString("ubicacion_dispositivo", snapshot.section)
            .putString("mdm_terminal_nombre", snapshot.terminal)
            .putString("mdm_profile_id", snapshot.profileId)
            .putString("apps_permitidas", apps.joinToString(","))
            .putString("mdm_config_canonica", settingsJson)
            .putLong("mdm_config_revision", snapshot.revision)
            .putString(
                "ultima_sincro",
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            )
            .commit()
        if (!persisted) {
            AppLog.error("MDM CACHE -> no se pudo proyectar el snapshot en la configuración local.")
            return
        }

        contactosGuardados = contactos
        whitelistGlobal = whitelist
        runOnUiThread {
            cargarIdentificacionYLogo()
            configurarModoKioscoEstricto()
            construirPanelDesdeNube(contactos, apps)
            actualizarTextoUltimaSincro()
        }
        AppLog.success(
            "MDM CACHE -> configuración canónica aplicada rev=${snapshot.revision}; " +
                "perfil=${snapshot.profileId}; contactos=${contactos.size}; apps=${apps.size}"
        )
    }

    private fun descargarAgendaNube(modoSilencioso: Boolean) {
        if (usandoConfigCanonica) {
            AppLog.info("MDM LEGACY -> descarga CSV omitida; snapshot canónico activo.")
            return
        }
        val client = OkHttpClient()
        val urlCsvSinCache = "$URL_GOOGLE_SHEETS_CSV&t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(urlCsvSinCache).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.error("Error red CSV: ${e.message}")
                // Aunque falle la descarga de configuración, el terminal debe seguir reportando
                // su estado al Inventario. La caché local sigue siendo la configuración válida.
                enviarTelemetriaMDM()
                if (!modoSilencioso) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "❌ Error de red al sincronizar", Toast.LENGTH_SHORT).show() }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val csvData = response.body?.string()
                if (usandoConfigCanonica) {
                    AppLog.info("MDM LEGACY -> respuesta CSV descartada; snapshot canónico ya activo.")
                    return
                }
                if (!response.isSuccessful || csvData.isNullOrEmpty()) {
                    AppLog.error("CSV inválido/no disponible. HTTP=${response.code} vacío=${csvData.isNullOrEmpty()}")
                    enviarTelemetriaMDM()
                    return
                }
                try {
                    val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                    val fechaActual = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                    prefs.edit().putString("csv_cache_data", csvData).putString("ultima_sincro", fechaActual).apply()
                } catch (e: Exception) {}

                runOnUiThread {
                    procesarYConstruirCSV(csvData, ejecutarAccionesRemotas = false)
                    actualizarTextoUltimaSincro()

                    // DPM/AudioManager ya han recibido las órdenes. Damos un pequeño margen para
                    // que Android refleje el valor y entonces tomamos la telemetría real.
                    handler.postDelayed({ enviarTelemetriaMDM() }, 1200L)

                    if (!modoSilencioso) {
                        Toast.makeText(this@MainActivity, "✨ ¡Sincronizado!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun procesarYConstruirCSV(csvData: String, ejecutarAccionesRemotas: Boolean = false) {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val grupoFiltro = prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4") ?: "Seccion Finales linea 4"

            val filasCsv = parsearCsvSeguro(csvData)

            val nuevosBotones = mutableListOf<Pair<String, String>>()
            val listaNumerosPermitidos = mutableListOf<String>()
            val listaAppsPermitidas = mutableListOf<String>()

            var avisoEncontrado = ""
            var horaReinicioEncontrada = ""

            var volumenCmd: Int? = null
            var brilloCmd: Int? = null
            var especialCmd = ""
            var timeoutCmd = ""
            var ttsLocalCmd = ""
            var ttsGlobalCmd = ""

            for ((indiceFila, partes) in filasCsv.withIndex()) {
                if (partes.isEmpty() || partes.all { it.isBlank() }) continue

                // La cabecera NO es una orden MDM. Antes, "Megafonía GLOBAL" se interpretaba
                // como un mensaje real y bloqueaba la megafonía por sección.
                if (indiceFila == 0 && partes.getOrNull(0)?.trim()?.equals("Grupo", ignoreCase = true) == true) continue

                if (partes.size >= 17) {
                    val celdaGlobal = partes[16].trim()
                    if (celdaGlobal.isNotEmpty()) {
                        ttsGlobalCmd = celdaGlobal
                    }
                }

                if (partes.size >= 3) {
                    val grupoExcel = partes[0].trim().replace("\"", "").replace("\uFEFF", "")
                    val nombreExcel = partes[1].trim().replace("\"", "")
                    val telefonoExcel = partes[2].trim().replace("\"", "")

                    if (ejecutarAccionesRemotas && partes.size >= 5) {
                        val telefonoITGlobal = partes[3].trim().replace("\"", "")
                        val pinITGlobal = partes[4].trim().replace("\"", "")
                        prefs.edit().apply {
                            if (telefonoITGlobal.isNotEmpty()) putString("telefono_it", telefonoITGlobal)
                            if (pinITGlobal.isNotEmpty()) putString("pin_it", pinITGlobal)
                        }.apply()
                    }

                    if (ejecutarAccionesRemotas && partes.size >= 8) {
                        val correoITGlobal = partes[7].trim().replace("\"", "")
                        if (correoITGlobal.isNotEmpty() && correoITGlobal.contains("@")) {
                            prefs.edit().putString("correo_it", correoITGlobal).apply()
                        }
                    }

                    if (partes.size >= 9) {
                        val numeroPermitido = partes[8].trim().replace("\"", "").replace(" ", "").replace("+34", "").replace("-", "")
                        if (numeroPermitido.isNotEmpty()) {
                            listaNumerosPermitidos.add(numeroPermitido)
                        }
                    }

                    if (ejecutarAccionesRemotas && partes.size >= 10) {
                        val minutosExcel = partes[9].trim().replace("\"", "").toIntOrNull()
                        if (minutosExcel != null && minutosExcel > 0) {
                            minutosInactividadConfig = minutosExcel
                            prefs.edit().putInt("minutos_inactividad_custom", minutosExcel).apply()
                        }
                    }

                    if (grupoExcel.equals(grupoFiltro.trim(), ignoreCase = true)) {
                        if (partes.size >= 6) {
                            val textoAviso = partes[5].trim().replace("\"", "")
                            if (textoAviso.isNotEmpty()) avisoEncontrado = textoAviso
                        }

                        if (partes.size >= 7) {
                            val horaRein = partes[6].trim().replace("\"", "")
                            if (horaRein.isNotEmpty()) horaReinicioEncontrada = horaRein
                        }

                        if (nombreExcel.isNotEmpty() && telefonoExcel.isNotEmpty()) {
                            nuevosBotones.add(Pair(nombreExcel, telefonoExcel))
                        }

                        if (partes.size >= 11) {
                            val appsExcel = partes[10].trim().replace("\"", "")
                            if (appsExcel.isNotEmpty()) {
                                val appsSeparadas = appsExcel.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }
                                listaAppsPermitidas.addAll(appsSeparadas)
                            }
                        }

                        if (partes.size >= 12) {
                            val celdaVol = partes[11].trim()
                            if (celdaVol.isNotEmpty()) {
                                val v = celdaVol.replace(Regex("[^0-9]"), "").toIntOrNull()
                                if (v != null) volumenCmd = v
                            }
                        }

                        if (partes.size >= 13) {
                            val celdaBrillo = partes[12].trim()
                            if (celdaBrillo.isNotEmpty()) {
                                val b = celdaBrillo.replace(Regex("[^0-9]"), "").toIntOrNull()
                                if (b != null) brilloCmd = b
                            }
                        }

                        if (partes.size >= 14) {
                            val celdaEspecial = partes[13].trim().uppercase(Locale.getDefault())
                            if (celdaEspecial.isNotEmpty()) especialCmd = celdaEspecial
                        }

                        if (partes.size >= 15) {
                            val celdaTimeout = partes[14].trim().uppercase(Locale.getDefault())
                            if (celdaTimeout.isNotEmpty()) {
                                if (celdaTimeout.contains("NUNCA")) {
                                    timeoutCmd = "NUNCA"
                                } else {
                                    val t = celdaTimeout.replace(Regex("[^0-9]"), "")
                                    if (t.isNotEmpty()) timeoutCmd = t
                                }
                            }
                        }

                        if (partes.size >= 16) {
                            val celdaTts = partes[15].trim().replace("\"", "")
                            if (celdaTts.isNotEmpty()) ttsLocalCmd = celdaTts
                        }
                    }
                }
            }

            if (ejecutarAccionesRemotas && horaReinicioEncontrada.isNotEmpty()) {
                prefs.edit().putString("hora_reinicio_seccion", horaReinicioEncontrada).apply()
            }
            val appsFinales = listaAppsPermitidas.distinct()
            prefs.edit().putString("apps_permitidas", appsFinales.joinToString(",")).apply()

            contactosGuardados = nuevosBotones.toList()
            whitelistGlobal = listaNumerosPermitidos.toList()

            AppLog.info("MDM Parser -> Vol: $volumenCmd | Brillo: $brilloCmd | CMD: $especialCmd | TTS Local: $ttsLocalCmd")

            runOnUiThread {
                configurarModoKioscoEstricto()
                construirPanelDesdeNube(nuevosBotones, appsFinales)

                if (ejecutarAccionesRemotas) {
                    actualizarBannerUrgencia(avisoEncontrado)
                    if (volumenCmd != null) aplicarVolumenRemoto(volumenCmd!!)
                    if (brilloCmd != null) aplicarBrilloRemoto(brilloCmd!!)
                    if (timeoutCmd.isNotEmpty() && timeoutCmd != ultimoTimeoutEjecutado) {
                        aplicarTimeoutPantalla(timeoutCmd)
                        ultimoTimeoutEjecutado = timeoutCmd
                        prefs.edit().putString("mdm_ultimo_timeout", timeoutCmd).apply()
                    }
                    // FIX1.1: los comandos de estado (ROBADA/BLOQUEO/DESBLOQUEAR) son idempotentes.
                    // No se pueden tratar igual que REINICIAR/ALARMA, porque si el primer DESBLOQUEAR
                    // no llega a cerrar la UI por cualquier recreación/race, bloquearlo por "último comando"
                    // deja el terminal atrapado para siempre.
                    procesarComandoEspecialRemoto(especialCmd, prefs)

                    val hayGlobalNuevo = ttsGlobalCmd.isNotEmpty() && ttsGlobalCmd != ultimoTTSGlobal
                    if (ttsGlobalCmd != ultimoTTSGlobal) {
                        if (ttsGlobalCmd.isEmpty()) {
                            ultimoTTSGlobal = ""
                            prefs.edit().putString("mdm_ultimo_tts_global", "").apply()
                        } else {
                            solicitarTts(ttsGlobalCmd, esGlobal = true)
                        }
                    }

                    if (ttsLocalCmd != ultimoTTSLocal) {
                        if (ttsLocalCmd.isEmpty()) {
                            ultimoTTSLocal = ""
                            prefs.edit().putString("mdm_ultimo_tts_local", "").apply()
                        } else if (!hayGlobalNuevo && ttsPendienteGlobal.isEmpty()) {
                            // Un global ya reproducido que siga escrito en Sheets no bloquea para siempre los avisos locales.
                            solicitarTts(ttsLocalCmd, esGlobal = false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.error("Error procesando CSV: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun parsearCsvSeguro(csvData: String): List<List<String>> {
        if (csvData.isBlank()) return emptyList()

        // Google Sheets publicado como CSV usa normalmente coma. Se detecta ';' solo si
        // la cabecera realmente lo usa como separador; nunca por encontrar ';' en un texto.
        val primeraLinea = csvData.lineSequence().firstOrNull() ?: ""
        val delimitador = if (primeraLinea.count { it == ';' } > primeraLinea.count { it == ',' }) ';' else ','

        val filas = mutableListOf<List<String>>()
        var fila = mutableListOf<String>()
        val campo = StringBuilder()
        var entreComillas = false
        var i = 0

        fun cerrarCampo() {
            fila.add(campo.toString())
            campo.setLength(0)
        }

        fun cerrarFila() {
            cerrarCampo()
            filas.add(fila)
            fila = mutableListOf()
        }

        while (i < csvData.length) {
            val c = csvData[i]
            when {
                c == '"' -> {
                    if (entreComillas && i + 1 < csvData.length && csvData[i + 1] == '"') {
                        campo.append('"')
                        i++
                    } else {
                        entreComillas = !entreComillas
                    }
                }
                c == delimitador && !entreComillas -> cerrarCampo()
                c == '\n' && !entreComillas -> cerrarFila()
                c == '\r' && !entreComillas -> Unit
                else -> campo.append(c)
            }
            i++
        }

        if (campo.isNotEmpty() || fila.isNotEmpty()) cerrarFila()
        return filas
    }

    // --- FUNCIONES MDM DE CONTROL REMOTO TOTAL ---

    private fun aplicarVolumenRemoto(nivelPorcentaje: Int) {
        val nivelSeguro = nivelPorcentaje.coerceIn(0, 100)
        mdmVolumenObjetivo = nivelSeguro
        getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            .edit().putInt("mdm_volumen_objetivo", nivelSeguro).apply()

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isVolumeFixed) {
                AppLog.warning("MDM: el dispositivo informa política de volumen fijo; no se puede cambiar por AudioManager.")
                return
            }

            fun aplicarStream(stream: Int, nombre: String) {
                try {
                    val max = audioManager.getStreamMaxVolume(stream)
                    val objetivo = (nivelSeguro * max) / 100
                    audioManager.setStreamVolume(stream, objetivo, 0)
                    val real = audioManager.getStreamVolume(stream)
                    val realPct = if (max > 0) (real * 100) / max else 0
                    if (stream == AudioManager.STREAM_MUSIC) {
                        getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                            .edit().putInt("mdm_ultimo_volumen_real_pct", realPct).apply()
                    }
                    AppLog.info("MDM volumen $nombre -> objetivo=$nivelSeguro% índice=$objetivo/$max real=$realPct% ($real/$max)")
                } catch (se: SecurityException) {
                    AppLog.error("MDM volumen $nombre bloqueado por Android: ${se.message}")
                } catch (e: Exception) {
                    AppLog.error("MDM volumen $nombre error: ${e.message}")
                }
            }

            aplicarStream(AudioManager.STREAM_MUSIC, "MUSIC")
            aplicarStream(AudioManager.STREAM_RING, "RING")
            aplicarStream(AudioManager.STREAM_ALARM, "ALARM")
        } catch (e: Exception) {
            AppLog.error("MDM: Error al ajustar volumen: ${e.message}")
        }
    }

    private fun aplicarBrilloRemoto(nivelPorcentaje: Int) {
        try {
            val nivelSeguro = nivelPorcentaje.coerceIn(0, 100)
            val brilloAbsoluto = ((nivelSeguro * 255) / 100).coerceIn(1, 255)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminName = ComponentName(this, MyAdminReceiver::class.java)

            var aplicado = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm.isDeviceOwnerApp(packageName)) {
                // Vía correcta para un Device Owner: no depende del permiso especial WRITE_SETTINGS.
                dpm.setSystemSetting(adminName, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString())
                dpm.setSystemSetting(adminName, Settings.System.SCREEN_BRIGHTNESS, brilloAbsoluto.toString())
                aplicado = true
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brilloAbsoluto)
                aplicado = true
            }

            if (!aplicado) {
                AppLog.warning("MDM: brillo no aplicado; no es Device Owner compatible y WRITE_SETTINGS no está concedido.")
                return
            }

            val params = window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params

            val real = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            val realPct = if (real >= 0) (real * 100) / 255 else -1
            if (realPct >= 0) {
                getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                    .edit().putInt("mdm_ultimo_brillo_real_pct", realPct).apply()
            }
            AppLog.info("MDM: brillo objetivo=$nivelSeguro% ($brilloAbsoluto/255), leído=$realPct% ($real/255)")

            // Segunda lectura diferida: algunos firmwares reflejan el cambio de Settings.System
            // unas décimas después de la llamada de DevicePolicyManager.
            handler.postDelayed({
                try {
                    val real2 = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                    val realPct2 = if (real2 >= 0) (real2 * 100) / 255 else -1
                    if (realPct2 >= 0) {
                        getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                            .edit().putInt("mdm_ultimo_brillo_real_pct", realPct2).apply()
                    }
                    AppLog.info("MDM: verificación brillo diferida -> $realPct2% ($real2/255)")
                } catch (e: Exception) {
                    AppLog.error("MDM: error verificando brillo diferido: ${e.message}")
                }
            }, 500L)
        } catch (e: Exception) {
            AppLog.error("MDM: Error al ajustar brillo: ${e.message}")
        }
    }

    private fun aplicarTimeoutPantalla(valor: String) {
        try {
            val milis = if (valor == "NUNCA") Int.MAX_VALUE else (valor.toIntOrNull() ?: return) * 1000
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminName = ComponentName(this, MyAdminReceiver::class.java)

            var aplicado = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm.isDeviceOwnerApp(packageName)) {
                dpm.setSystemSetting(adminName, Settings.System.SCREEN_OFF_TIMEOUT, milis.toString())
                aplicado = true
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, milis)
                aplicado = true
            }

            if (aplicado) {
                AppLog.info("MDM: Tiempo de pantalla ajustado a $valor ($milis ms)")
            } else {
                AppLog.warning("MDM: timeout no aplicado; falta Device Owner compatible o WRITE_SETTINGS.")
            }
        } catch (e: Exception) {
            AppLog.error("MDM: Error al ajustar Timeout: ${e.message}")
        }
    }

    private fun configurarListenerTts() {
        if (!::tts.isInitialized) return
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                AppLog.info("MDM TTS: reproducción iniciada id=$utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                    when {
                        utteranceId?.startsWith("TTS_GLOBAL|") == true -> {
                            val texto = utteranceId.substringAfter('|')
                            ultimoTTSGlobal = texto
                            ttsPendienteGlobal = ""
                            prefs.edit().putString("mdm_ultimo_tts_global", texto).apply()
                        }
                        utteranceId?.startsWith("TTS_LOCAL|") == true -> {
                            val texto = utteranceId.substringAfter('|')
                            ultimoTTSLocal = texto
                            ttsPendienteLocal = ""
                            prefs.edit().putString("mdm_ultimo_tts_local", texto).apply()
                        }
                    }
                    restaurarVolumenTrasTts()
                    AppLog.success("MDM TTS: reproducción finalizada correctamente.")
                    reproducirTtsPendienteSiProcede()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread {
                    restaurarVolumenTrasTts()
                    AppLog.error("MDM TTS: error de reproducción id=$utteranceId code=$errorCode")
                }
            }
        })
    }

    private fun solicitarTts(texto: String, esGlobal: Boolean) {
        if (texto.isBlank()) return
        if (esGlobal) ttsPendienteGlobal = texto else ttsPendienteLocal = texto

        if (!isTtsReady) {
            AppLog.warning("MDM: Motor TTS aún no listo; mensaje ${if (esGlobal) "GLOBAL" else "LOCAL"} queda pendiente.")
            return
        }
        reproducirTtsPendienteSiProcede()
    }

    private fun reproducirTtsPendienteSiProcede() {
        if (!isTtsReady || !::tts.isInitialized || tts.isSpeaking) return
        val esGlobal = ttsPendienteGlobal.isNotBlank()
        val texto = if (esGlobal) ttsPendienteGlobal else ttsPendienteLocal
        if (texto.isBlank()) return
        hablarTexto(texto, esGlobal)
    }

    private fun hablarTexto(texto: String, esGlobal: Boolean): Boolean {
        return try {
            if (!isTtsReady) return false
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (volumenAntesDeTts == null) volumenAntesDeTts = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolMusic, 0)

            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            val prefijo = if (esGlobal) "TTS_GLOBAL" else "TTS_LOCAL"
            val utteranceId = "$prefijo|$texto"
            val resultado = tts.speak(texto, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            AppLog.info("🗣️ Megafonía ${if (esGlobal) "GLOBAL" else "LOCAL"} solicitada -> resultado=$resultado texto=$texto")
            if (resultado != TextToSpeech.SUCCESS) {
                restaurarVolumenTrasTts()
                false
            } else true
        } catch (e: Exception) {
            restaurarVolumenTrasTts()
            AppLog.error("MDM: Error ejecutando Megafonía TTS: ${e.message}")
            false
        }
    }

    private fun restaurarVolumenTrasTts() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val objetivoPersistido = mdmVolumenObjetivo?.let { (it * max) / 100 }
            val restaurar = objetivoPersistido ?: volumenAntesDeTts
            if (restaurar != null) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restaurar.coerceIn(0, max), 0)
        } catch (_: Exception) {
        } finally {
            volumenAntesDeTts = null
        }
    }

    private fun procesarComandoEspecialRemoto(comandoRaw: String, prefs: android.content.SharedPreferences) {
        val comando = comandoRaw.trim().uppercase(Locale.getDefault())
        val roboActivo = prefs.getBoolean("mdm_robo_activo", false)
        val dialogVisible = dialogBloqueoRobo?.isShowing == true

        AppLog.info(
            "MDM CMD -> Nube=[$comando] | Último=[$ultimoComandoEjecutado] | " +
                    "RoboActivo=$roboActivo | DialogVisible=$dialogVisible"
        )

        when (comando) {
            "ROBADA", "BLOQUEO" -> {
                // Estado deseado: bloqueado. Si tras un reboot/recreación el estado quedó activo
                // pero la pantalla no está visible, la reconstruimos.
                if (!roboActivo || !dialogVisible) {
                    ejecutarComandoEspecial(comando)
                }
                ultimoComandoEjecutado = comando
                prefs.edit().putString("mdm_ultimo_comando", comando).commit()
            }

            "DESBLOQUEAR" -> {
                // Estado deseado: desbloqueado. Se reintenta mientras quede CUALQUIER evidencia
                // de bloqueo, aunque DESBLOQUEAR ya figure como último comando.
                if (roboActivo || dialogVisible || ultimoComandoEjecutado != comando) {
                    ejecutarComandoEspecial(comando)
                } else {
                    AppLog.info("MDM: DESBLOQUEAR confirmado; el terminal ya estaba desbloqueado.")
                }
                ultimoComandoEjecutado = comando
                prefs.edit().putString("mdm_ultimo_comando", comando).commit()
            }

            "" -> {
                // Limpiar la celda rearma los comandos de una sola ejecución para permitir
                // lanzar de nuevo REINICIAR/ALARMA más adelante con el mismo texto.
                if (ultimoComandoEjecutado.isNotEmpty()) {
                    ultimoComandoEjecutado = ""
                    prefs.edit().putString("mdm_ultimo_comando", "").commit()
                    AppLog.info("MDM: celda de comando vacía; motor de comandos rearmado.")
                }
            }

            else -> {
                // Comandos de una sola ejecución: persistir ANTES es importante para REINICIAR,
                // así evitamos que el mismo comando se repita tras el boot.
                if (comando != ultimoComandoEjecutado) {
                    ultimoComandoEjecutado = comando
                    prefs.edit().putString("mdm_ultimo_comando", comando).commit()
                    ejecutarComandoEspecial(comando)
                }
            }
        }
    }

    private fun ejecutarComandoEspecial(comando: String) {
        AppLog.warning("MDM: COMANDO ESPECIAL RECIBIDO -> [$comando]")
        when (comando) {
            "REINICIAR" -> {
                enviarAlertaITCRasante("🔄 MDM: Ejecutando orden de REINICIO forzado.")
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminName = ComponentName(this, MyAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(packageName)) {
                    dpm.reboot(adminName)
                } else {
                    Runtime.getRuntime().exec("reboot")
                }
            }
            "ALARMA" -> {
                aplicarVolumenRemoto(100)
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000), -1)
                try {
                    val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    val r = RingtoneManager.getRingtone(applicationContext, notification)
                    r.play()
                    Handler(Looper.getMainLooper()).postDelayed({ r.stop() }, 10000)
                } catch (e: Exception) {}
            }
            "ROBADA", "BLOQUEO" -> {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val guardado = prefs.edit().putBoolean("mdm_robo_activo", true).commit()
                AppLog.warning("MDM: activando estado ROBADA/BLOQUEO. Persistencia=$guardado")
                mostrarPantallaRobo()
            }
            "DESBLOQUEAR" -> {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val guardado = prefs.edit().putBoolean("mdm_robo_activo", false).commit()
                runOnUiThread {
                    val estabaVisible = dialogBloqueoRobo?.isShowing == true
                    try {
                        dialogBloqueoRobo?.dismiss()
                    } catch (e: Exception) {
                        AppLog.error("MDM: error cerrando pantalla ROBADA: ${e.message}")
                    } finally {
                        dialogBloqueoRobo = null
                    }
                    AppLog.success(
                        "MDM: DESBLOQUEAR aplicado. Persistencia=$guardado | " +
                                "DialogEstabaVisible=$estabaVisible | RoboActivoAhora=${prefs.getBoolean("mdm_robo_activo", true)}"
                    )
                }
            }
            else -> AppLog.info("MDM: Comando desconocido ignorado.")
        }
    }

    private fun mostrarPantallaRobo() {
        if (dialogBloqueoRobo != null && dialogBloqueoRobo!!.isShowing) return

        runOnUiThread {
            try {
                dialogBloqueoRobo = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    window?.let { win ->
                        win.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN)

                        @Suppress("DEPRECATION")
                        win.addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        )
                    }

                    val rootLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.parseColor("#7F1D1D"), Color.parseColor("#450A0A"))
                        )
                        setPadding(40, 40, 40, 40)
                    }

                    val tvIcono = TextView(context).apply {
                        text = "🔒"
                        textSize = 100f
                        gravity = Gravity.CENTER
                    }

                    val tvAlerta = TextView(context).apply {
                        text = "DISPOSITIVO BLOQUEADO"
                        setTextColor(Color.WHITE)
                        textSize = 35f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 40, 0, 20) }
                    }

                    val tvInfo = TextView(context).apply {
                        text = "Este terminal es propiedad exclusiva de GRUPO TGT y ha sido reportado como PERDIDO o ROBADO.\n\nPor favor, devuélvalo a su departamento de sistemas o a la autoridad competente."
                        setTextColor(Color.parseColor("#FECACA"))
                        textSize = 20f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 60) }
                    }

                    rootLayout.addView(tvIcono)
                    rootLayout.addView(tvAlerta)
                    rootLayout.addView(tvInfo)

                    setContentView(rootLayout)
                    setCancelable(false)
                    show()
                }
            } catch (e: Exception) {
                AppLog.error("MDM: Error al mostrar bloqueo por robo: ${e.message}")
            }
        }
    }

    private fun actualizarBannerUrgencia(textoAviso: String) {
        try {
            val banner = findViewById<TextView>(R.id.tvBannerUrgencia)
            if (banner != null) {
                if (textoAviso.isNotEmpty()) {
                    banner.text = textoAviso
                    banner.visibility = View.VISIBLE
                } else {
                    banner.text = ""
                    banner.visibility = View.GONE
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun construirPanelDesdeNube(listaContactos: List<Pair<String, String>>, listaApps: List<String>) {
        try {
            val panelBase = findViewById<LinearLayout>(R.id.panelBotonesDinamicos)
            panelBase?.removeAllViews()
            val listaBotonesUI = mutableListOf<Button>()

            for (contacto in listaContactos) {
                val nombre = contacto.first
                val numero = contacto.second

                val btn = Button(this).apply {
                    text = nombre
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    isAllCaps = false
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    backgroundTintList = null
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_main_action_contact)
                    elevation = 0f
                    contentDescription = getString(R.string.main_contact_action_description, nombre)
                    setPadding(dpMain(8), dpMain(8), dpMain(8), dpMain(8))
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_main_phone)?.mutate()?.let { icono ->
                        val lado = dpMain(22)
                        icono.setBounds(0, 0, lado, lado)
                        setCompoundDrawables(null, icono, null, null)
                        compoundDrawablePadding = dpMain(5)
                    }

                    val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        val margen = dpMain(5)
                        setMargins(margen, margen, margen, margen)
                    }
                    layoutParams = p

                    setOnClickListener {
                        if (numero.isNotEmpty()) {
                            mostrarPantallaLlamando(nombre, numero, true)
                        } else {
                            Toast.makeText(this@MainActivity, "Número no válido", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                listaBotonesUI.add(btn)
            }

            val pm = packageManager
            for (appPackage in listaApps) {
                try {
                    val appInfo = pm.getApplicationInfo(appPackage, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()

                    val btnApp = Button(this).apply {
                        text = appName
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                        isAllCaps = false
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        backgroundTintList = null
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_main_action_app)
                        elevation = 0f
                        contentDescription = getString(R.string.main_app_action_description, appName)
                        setPadding(dpMain(8), dpMain(8), dpMain(8), dpMain(8))
                        val icono = try {
                            appInfo.loadIcon(pm).mutate()
                        } catch (_: Exception) {
                            ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_it_apps)?.mutate()
                        }
                        icono?.let {
                            val lado = dpMain(24)
                            it.setBounds(0, 0, lado, lado)
                            setCompoundDrawables(null, it, null, null)
                            compoundDrawablePadding = dpMain(5)
                        }

                        val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            val margen = dpMain(5)
                            setMargins(margen, margen, margen, margen)
                        }
                        layoutParams = p

                        setOnClickListener {
                            val launchIntent = pm.getLaunchIntentForPackage(appPackage)
                            if (launchIntent != null) {
                                AppLog.info("Lanzando aplicación externa permitida: $appName")
                                startActivity(launchIntent)
                            } else {
                                Toast.makeText(this@MainActivity, "Esta app no se puede abrir directamente", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    listaBotonesUI.add(btnApp)
                } catch (e: PackageManager.NameNotFoundException) {
                    AppLog.warning("La app $appPackage está en el Excel pero NO está instalada en este dispositivo.")
                }
            }

            var filaActual: LinearLayout? = null
            for (index in listaBotonesUI.indices) {
                if (index % 2 == 0) {
                    filaActual = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpMain(88)
                        )
                    }
                    panelBase?.addView(filaActual)
                }
                filaActual?.addView(listaBotonesUI[index])
            }

            if (listaBotonesUI.size % 2 != 0) {
                val dummy = Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                }
                filaActual?.addView(dummy)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun configurarChivatoSincro() {
        findViewById<TextView>(R.id.tvUbicacionDispositivo)?.setOnLongClickListener {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val ultimaSincro = prefs.getString("ultima_sincro", "Nunca")
            Toast.makeText(this, "🔄 Sincro: $ultimaSincro\n⏱️ Inactividad: ${minutosInactividadConfig} min\n🛡️ Whitelist: ${whitelistGlobal.size} núms", Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun cancelarTareasLlamadaPendientes() {
        handlerLlamadas.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    private fun colgarLlamadaReal(): Boolean {
        cancelarTareasLlamadaPendientes()

        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                AppLog.warning("Telefonía -> finalizar llamada no soportado en esta API.")
                return false
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ANSWER_PHONE_CALLS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.error("Telefonía -> no se puede finalizar: falta ANSWER_PHONE_CALLS.")
                return false
            }

            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val finalizada = telecomManager.endCall()
            AppLog.info("Telefonía -> solicitud endCall resultado=$finalizada")

            if (!finalizada) {
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
                    handlerLlamadas.postDelayed({
                        try {
                            val reintento = telecomManager.endCall()
                            AppLog.info("Telefonía -> reintento endCall resultado=$reintento")
                        } catch (e: Exception) {
                            AppLog.error("Telefonía -> error en reintento endCall: ${e.message}")
                        }
                    }, 250L)
                }
            }
            finalizada
        } catch (e: Exception) {
            AppLog.error("Excepción al colgar llamada: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun contestarLlamadaReal() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall()
                }
            }
        } catch (e: Exception) {
            AppLog.error("Excepción al contestar llamada: ${e.message}")
        }
    }

    private fun configurarVentanaLlamadaRetro(dialog: Dialog, root: View, descartarKeyguard: Boolean) {
        dialog.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.statusBarColor = Color.BLACK
            win.navigationBarColor = Color.BLACK
            win.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                    )

            @Suppress("DEPRECATION")
            var flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (descartarKeyguard) flags = flags or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            win.addFlags(flags)
        }

        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                left + safe.left,
                top + safe.top,
                right + safe.right,
                bottom + safe.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun mostrarPantallaLlamadaEntrante(nombreCaller: String, numeroCaller: String) {
        if (dialogLlamadaEntrante?.isShowing == true) return

        try {
            if (wakeLockLlamada?.isHeld == true) wakeLockLlamada?.release()
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            wakeLockLlamada = powerManager.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "KioscoTGT::DespertarLlamada"
            )
            wakeLockLlamada?.acquire(10 * 60 * 1000L)

            val intentBring = Intent(this@MainActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intentBring)
            Handler(Looper.getMainLooper()).postDelayed({ startActivity(intentBring) }, 800)
            Handler(Looper.getMainLooper()).postDelayed({ startActivity(intentBring) }, 2000)
        } catch (e: Exception) {
            AppLog.error("Error forzando WakeLock: ${e.message}")
        }

        runOnUiThread {
            try {
                val content = layoutInflater.inflate(R.layout.dialog_call_incoming_retro, null)
                content.findViewById<TextView>(R.id.tvCallIncomingName).text = nombreCaller
                content.findViewById<TextView>(R.id.tvCallIncomingNumber).text = numeroCaller

                dialogLlamadaEntrante = Dialog(
                    this@MainActivity,
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen
                ).apply {
                    content.findViewById<Button>(R.id.btnCallAnswer).setOnClickListener {
                        AppLog.success("Llamada contestada desde UI retro")
                        contestarLlamadaReal()
                        dismiss()
                        mostrarPantallaLlamando(nombreCaller, numeroCaller, false)
                    }
                    content.findViewById<Button>(R.id.btnCallReject).setOnClickListener {
                        AppLog.warning("Llamada rechazada desde UI retro")
                        colgarLlamadaReal()
                        dismiss()
                        liberarPantalla()
                    }

                    setContentView(content)
                    setCancelable(false)
                    configurarVentanaLlamadaRetro(this, content, descartarKeyguard = true)
                    show()
                }
            } catch (e: Exception) {
                AppLog.error("Error al mostrar UI de llamada entrante: ${e.message}")
            }
        }
    }

    private fun mostrarPantallaLlamando(nombre: String, numero: String, esSaliente: Boolean = true) {
        if (dialogLlamadaActiva?.isShowing == true) return

        runOnUiThread {
            try {
                val content = layoutInflater.inflate(R.layout.dialog_call_active_retro, null)
                content.findViewById<TextView>(R.id.tvCallActiveState).setText(
                    if (esSaliente) R.string.call_state_dialing else R.string.call_state_connected
                )
                content.findViewById<TextView>(R.id.tvCallActiveName).text = nombre
                content.findViewById<TextView>(R.id.tvCallActiveNumber).text = numero

                dialogLlamadaActiva = Dialog(
                    this@MainActivity,
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen
                ).apply {
                    content.findViewById<Button>(R.id.btnCallEnd).setOnClickListener {
                        AppLog.info("Llamada finalizada desde UI retro")
                        colgarLlamadaReal()
                        dismiss()
                        liberarPantalla()
                    }

                    setContentView(content)
                    setCancelable(false)
                    configurarVentanaLlamadaRetro(this, content, descartarKeyguard = false)
                    show()
                }

                if (esSaliente && numero.isNotEmpty()) {
                    val numLimpio = numero.replace(" ", "")
                    cancelarTareasLlamadaPendientes()
                    handlerLlamadas.postDelayed({
                        try {
                            if (dialogLlamadaActiva?.isShowing != true) {
                                AppLog.info("Telefonía -> marcación cancelada antes de iniciar.")
                                return@postDelayed
                            }
                            val intentCall = Intent(Intent.ACTION_CALL, Uri.parse("tel:$numLimpio"))
                            this@MainActivity.startActivity(intentCall)

                            val intentBring = Intent(this@MainActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            handlerLlamadas.postDelayed({ this@MainActivity.startActivity(intentBring) }, 500)
                            handlerLlamadas.postDelayed({ this@MainActivity.startActivity(intentBring) }, 1500)
                        } catch (e: Exception) {
                            AppLog.error("Error marcando: ${e.message}")
                        }
                    }, 300)
                }
            } catch (e: Exception) {
                AppLog.error("Error al mostrar UI Activa: ${e.message}")
            }
        }
    }

    private fun aplicarIndicadorPrincipal(id: Int, texto: String, color: Int) {
        findViewById<TextView>(id)?.apply {
            text = texto
            setTextColor(color)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(color))
        }
    }

    private fun actualizarIndicadoresReales() {
        try {
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (scale > 0) (level * 100) / scale else 0
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val colorBateria = when {
                pct <= 15 -> Color.parseColor("#FF6B6B")
                isCharging -> Color.parseColor("#FFE58A")
                else -> Color.parseColor("#9CFF9F")
            }
            aplicarIndicadorPrincipal(
                R.id.tvBateria,
                getString(R.string.main_status_battery_value, pct),
                colorBateria
            )
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var wifiDetectado = false
            var wifiValidado = false
            var datosDetectados = false
            var datosValidados = false
            var internetValidado = false

            val networks = cm.allNetworks
            for (network in networks) {
                val capabilities = cm.getNetworkCapabilities(network) ?: continue
                val tieneInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                internetValidado = internetValidado || tieneInternet

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiDetectado = true
                    wifiValidado = wifiValidado || tieneInternet
                }

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    datosDetectados = true
                    datosValidados = datosValidados || tieneInternet
                }
            }

            val verde = Color.parseColor("#9CFF9F")
            val ambar = Color.parseColor("#FFE58A")
            val rojo = Color.parseColor("#FF6B6B")
            aplicarIndicadorPrincipal(
                R.id.tvWifi,
                when {
                    wifiValidado -> getString(R.string.main_status_wifi_ok)
                    wifiDetectado -> getString(R.string.main_status_wifi_no_network)
                    else -> getString(R.string.main_status_wifi_off)
                },
                when {
                    wifiValidado -> verde
                    wifiDetectado -> ambar
                    else -> rojo
                }
            )
            aplicarIndicadorPrincipal(
                R.id.tvInternet,
                getString(
                    if (internetValidado) R.string.main_status_internet_ok
                    else R.string.main_status_internet_off
                ),
                if (internetValidado) verde else rojo
            )
            aplicarIndicadorPrincipal(
                R.id.tvDatos,
                when {
                    datosValidados -> getString(R.string.main_status_data_ok)
                    datosDetectados -> getString(R.string.main_status_data_limited)
                    else -> getString(R.string.main_status_data_off)
                },
                when {
                    datosValidados -> verde
                    datosDetectados -> ambar
                    else -> rojo
                }
            )

            val modoAvion = Settings.Global.getInt(
                contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) == 1
            aplicarIndicadorPrincipal(
                R.id.tvModoAvion,
                getString(
                    if (modoAvion) R.string.main_status_airplane_on
                    else R.string.main_status_airplane_off
                ),
                if (modoAvion) ambar else verde
            )
            aplicarIndicadorPrincipal(
                R.id.tvVoWifi,
                getString(R.string.main_status_vowifi_unverified),
                ambar
            )

            val estadoGeneral = findViewById<TextView>(R.id.tvEstadoGeneral)
            if (internetValidado) {
                estadoGeneral?.text = getString(R.string.main_terminal_operational)
                estadoGeneral?.setTextColor(verde)
                ultimoEstadoConexionTexto = getString(R.string.screensaver_connection_operational)
                ultimoEstadoConexionColor = verde
            } else {
                estadoGeneral?.text = getString(R.string.main_terminal_no_internet)
                estadoGeneral?.setTextColor(ambar)
                ultimoEstadoConexionTexto = getString(R.string.screensaver_connection_offline)
                ultimoEstadoConexionColor = ambar
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnableEstadoDispositivo)
        handler.removeCallbacks(runnableAutoSyncAgenda)
        handler.removeCallbacks(runnableAutoCheckOTA)
        handler.removeCallbacks(runnableReloj)
        handlerInactividad.removeCallbacks(runnableScreensaver)
        cancelarTareasLlamadaPendientes()
        detenerMovimientoScreensaver()

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        try { unregisterReceiver(callStateReceiver) } catch (e: Exception) {}

        try { dialogoOTA?.dismiss() } catch (e: Exception) {}
        try { dialogScreensaver?.dismiss() } catch (e: Exception) {}
        try { dialogBloqueoRobo?.dismiss() } catch (e: Exception) {}
        liberarPantalla()
    }

    private fun configurarModoKioscoEstricto() {
        if (MaintenanceModeManager.estaActivo(this)) {
            AppLog.info("KIOSK -> configurarModoKioscoEstricto omitido por mantenimiento IT.")
            return
        }

        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, MyAdminReceiver::class.java)

            val politicaAplicada = KioskPolicyManager.aplicar(this)

            if (devicePolicyManager.isDeviceOwnerApp(packageName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Los permisos funcionales se conceden si es posible, pero NUNCA condicionan
                // la seguridad LockTask del terminal.
                val permisosFuncionales = arrayOf(
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.READ_CALL_LOG
                )
                for (permiso in permisosFuncionales) {
                    try {
                        devicePolicyManager.setPermissionGrantState(
                            componentName,
                            packageName,
                            permiso,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        )
                    } catch (e: Exception) {
                        AppLog.warning("MDM permiso $permiso no concedido automáticamente: ${e.message}")
                    }
                }
            }

            if (!politicaAplicada) {
                AppLog.warning("KIOSK -> política base no confirmada; revisar Device Owner en Panel IT.")
            }
        } catch (e: Exception) {
            AppLog.error("KIOSK -> error configurando modo estricto: ${e.message}")
        }
    }

    private fun esSeguroBloquearPantalla(forceBlindado: Boolean = false): Boolean {
        if (MaintenanceModeManager.estaActivo(this)) return false
        if (!forceBlindado && ManagedModeStore.isManagedFree(this)) return false

        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(packageName) && dpm.isLockTaskPermitted(packageName)
        } catch (e: Exception) {
            AppLog.error("KIOSK -> no se pudo comprobar LockTask: ${e.message}")
            false
        }
    }

    private fun entrarEnKioscoSiPermitido(origen: String, forceBlindado: Boolean = false) {
        if (MaintenanceModeManager.estaActivo(this)) {
            AppLog.info("KIOSK -> LockTask omitido desde $origen por mantenimiento IT activo.")
            return
        }
        if (!forceBlindado && ManagedModeStore.isManagedFree(this)) {
            AppLog.info("KIOSK -> LockTask omitido desde $origen por LIBRE GESTIONADO.")
            return
        }

        if (esSeguroBloquearPantalla(forceBlindado)) {
            try {
                startLockTask()
                AppLog.success("KIOSK -> LockTask activo solicitado desde $origen.")
            } catch (e: Exception) {
                AppLog.error("KIOSK -> no se pudo activar LockTask desde $origen: ${e.message}")
            }
        } else {
            AppLog.warning("KIOSK -> LockTask no permitido desde $origen; no se fuerza stopLockTask().")
        }
    }

    override fun onResume() {
        super.onResume()
        forzarVolumenMaximo()

        if (MaintenanceModeManager.estaActivo(this)) {
            MaintenanceModeManager.reprogramarSiActivo(this)
            AppLog.info("IT MANTENIMIENTO -> onResume sin reactivar kiosco (${MaintenanceModeManager.descripcion(this)}).")
        } else {
            reconciliarModoGestionado("onResume")
        }

        // onCreate ya carga caché y lanza la primera sincronización. Evitamos repetir todo
        // inmediatamente en el primer onResume, que antes duplicaba trabajo durante el arranque.
        if (primeraReanudacion) {
            primeraReanudacion = false
        } else {
            cargarIdentificacionYLogo()
            cargarAgendaDesdeCache()
            descargarAgendaNube(modoSilencioso = true)
        }

        reiniciarTemporizadorInactividad()
    }

    private fun cargarIdentificacionYLogo() {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val txtUbicacion = prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4")
            findViewById<TextView>(R.id.tvUbicacionDispositivo)?.text = txtUbicacion
            val logoUriString = prefs.getString("logo_uri_custom", "")
            if (!logoUriString.isNullOrEmpty()) {
                findViewById<ImageView>(R.id.logoEmpresa)?.setImageURI(Uri.parse(logoUriString))
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        reiniciarTemporizadorInactividad()
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) contadorVolumenAbajo = 1
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) contadorVolumenArriba = 1

        if (contadorVolumenAbajo == 1 && contadorVolumenArriba == 1) {
            contadorVolumenAbajo = 0
            contadorVolumenArriba = 0
            mostrarDialogoPIN()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            forzarVolumenMaximo(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        reiniciarTemporizadorInactividad()
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) contadorVolumenAbajo = 0
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) contadorVolumenArriba = 0
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun forzarVolumenMaximo() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mdmVolumenObjetivo == null) {
                val guardado = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE).getInt("mdm_volumen_objetivo", -1)
                if (guardado in 0..100) mdmVolumenObjetivo = guardado
            }
            val maxVolMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxVolRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val maxVolAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

            // Respetar el nivel que dictó el MDM en lugar del 100% obligatorio.
            val targetMusic = mdmVolumenObjetivo?.let { (it * maxVolMusic) / 100 } ?: maxVolMusic
            val targetRing = mdmVolumenObjetivo?.let { (it * maxVolRing) / 100 } ?: maxVolRing
            val targetAlarm = mdmVolumenObjetivo?.let { (it * maxVolAlarm) / 100 } ?: maxVolAlarm

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, targetRing, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun solicitarAdministradorDispositivo() {
        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, MyAdminReceiver::class.java)
            if (!devicePolicyManager.isAdminActive(componentName)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Blindaje del Kiosco necesario para evitar desinstalaciones.")
                }
                startActivity(intent)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun solicitarPermisos() {
        try {
            val permisosRequeridos = mutableListOf(
                Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG
            )
            val pedir = permisosRequeridos.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
            if (pedir.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, pedir, 100)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun configurarBotonSecreto() {
        findViewById<ImageView>(R.id.logoEmpresa)?.setOnLongClickListener { mostrarDialogoPIN(); true }
        findViewById<TextView>(R.id.tvUbicacionDispositivo)?.setOnLongClickListener { mostrarDialogoPIN(); true }
    }

    private fun enviarAlertaIT(mensajeBase: String) {
        Thread {
            try {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val numeroIT = prefs.getString("telefono_it", "")
                val ubicacion = prefs.getString("ubicacion_dispositivo", "Ubicación Desconocida")

                if (numeroIT.isNullOrEmpty()) return@Thread
                val mensajeFinal = "[$ubicacion] $mensajeBase"

                val smsManager: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java)
                } else {
                    SmsManager.getDefault()
                }

                if (smsManager != null) {
                    val parts = smsManager.divideMessage(mensajeFinal)
                    smsManager.sendMultipartTextMessage(numeroIT, null, parts, null, null)
                }
                AppLog.info("Alerta enviada por SMS a IT")
            } catch (e: Exception) {
                AppLog.error("Error enviando SMS: ${e.message}")
            }
        }.start()
    }

    private fun enviarAlertaITCRasante(mensajeBase: String) {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val numeroIT = prefs.getString("telefono_it", "")
            val ubicacion = prefs.getString("ubicacion_dispositivo", "Ubicación Desconocida")

            if (numeroIT.isNullOrEmpty()) return
            val mensajeFinal = "[$ubicacion] $mensajeBase"

            val smsManager: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }

            smsManager?.sendTextMessage(numeroIT, null, mensajeFinal, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun mostrarDialogoPIN() {
        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val tiempoDesbloqueo = prefs.getLong("tiempo_desbloqueo_pin", 0L)
        val tiempoActual = System.currentTimeMillis()

        if (tiempoActual < tiempoDesbloqueo) {
            val segundosRestantes = (tiempoDesbloqueo - tiempoActual) / 1000
            Toast.makeText(this, "🚫 Panel Bloqueado. Inténtalo en $segundosRestantes segundos.", Toast.LENGTH_LONG).show()
            AppLog.warning("Intento de acceso al Panel IT rechazado. (Bloqueo activo por $segundosRestantes s)")
            return
        }

        val pinCorrecto = prefs.getString("pin_it", "1234")
        val contenido = layoutInflater.inflate(R.layout.dialog_it_access, null)
        val input = contenido.findViewById<EditText>(R.id.etItAccessPin)
        val togglePassword = contenido.findViewById<ImageButton>(R.id.btnToggleItPassword)
        var passwordVisible = false
        togglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            input.transformationMethod = if (passwordVisible) {
                null
            } else {
                PasswordTransformationMethod.getInstance()
            }
            input.setSelection(input.text?.length ?: 0)
            togglePassword.setImageResource(
                if (passwordVisible) R.drawable.ic_main_eye_off else R.drawable.ic_main_eye
            )
            togglePassword.contentDescription = getString(
                if (passwordVisible) R.string.it_access_hide_password
                else R.string.it_access_show_password
            )
        }

        val builder = AlertDialog.Builder(this)
            .setView(contenido)
            .setPositiveButton("ENTRAR") { _, _ ->
                val codigoMetido = input.text.toString()

                if (codigoMetido == "*###9999#" || codigoMetido == pinCorrecto) {
                    prefs.edit().putInt("intentos_fallidos_pin", 0).apply()

                    // Entrar al Panel IT NO debe liberar el kiosco. La única salida de producción
                    // es el botón Modo Mantenimiento, que ejecuta la transición completa desde MainActivity.
                    if (codigoMetido == "*###9999#") {
                        AppLog.warning("Acceso de emergencia al Panel IT utilizado. No se abre Ajustes directamente.")
                    }
                    startActivity(Intent(this, ItActivity::class.java))
                } else {
                    val intentos = prefs.getInt("intentos_fallidos_pin", 0) + 1

                    if (intentos >= 3) {
                        val bloqueoHasta = System.currentTimeMillis() + (3 * 60 * 1000L)
                        prefs.edit()
                            .putInt("intentos_fallidos_pin", 0)
                            .putLong("tiempo_desbloqueo_pin", bloqueoHasta)
                            .apply()

                        AppLog.error("BLOQUEO ACTIVADO: 3 intentos fallidos de PIN IT.")
                        Toast.makeText(this, "🚨 DEMASIADOS INTENTOS. Panel bloqueado por 3 minutos.", Toast.LENGTH_LONG).show()
                    } else {
                        prefs.edit().putInt("intentos_fallidos_pin", intentos).apply()
                        Toast.makeText(this, "❌ PIN Incorrecto. Intento $intentos/3", Toast.LENGTH_SHORT).show()
                    }

                    AppLog.warning("Seguridad IT -> PIN incorrecto. Intento $intentos/3.")
                    if (intentos >= 3) {
                        enviarAlertaIT("⚠️ SEGURIDAD: Panel IT bloqueado tras 3 intentos de PIN incorrectos.")
                    }
                }
            }
            .setNegativeButton("CANCELAR", null)

        val dialog = builder.create()
        dialog.setOnShowListener {
            val verde = Color.parseColor("#9CFF9F")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(verde)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                backgroundTintList = null
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_retro_button_primary)
                minHeight = dpMain(48)
                setPadding(dpMain(14), 0, dpMain(14), 0)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(Color.parseColor("#B9F7C1"))
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                backgroundTintList = null
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_retro_button_outline)
                minHeight = dpMain(48)
                setPadding(dpMain(14), 0, dpMain(14), 0)
            }
        }
        dialog.window?.apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_it_access_dialog))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private var dialogoOTA: Dialog? = null
    private var tvProgresoOta: TextView? = null
    private var pbProgresoOta: ProgressBar? = null

    private fun mostrarPantallaOTA() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (dialogoOTA != null && dialogoOTA!!.isShowing) return@runOnUiThread

            try {
                dialogoOTA = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    window?.let { win ->
                        win.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN)

                        @Suppress("DEPRECATION")
                        win.addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        )
                    }

                    val rootLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
                        )
                    }

                    val cardLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#1E293B"))
                            cornerRadius = 40f
                            setStroke(3, Color.parseColor("#334155"))
                        }
                        setPadding(60, 80, 60, 80)

                        val params = LinearLayout.LayoutParams(
                            (resources.displayMetrics.widthPixels * 0.85).toInt(),
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(40, 40, 40, 40)
                        }
                        layoutParams = params
                        elevation = 25f
                    }

                    val icono = TextView(context).apply {
                        text = "📦"
                        textSize = 70f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 0, 30) }

                        val floatAnim = android.view.animation.TranslateAnimation(
                            0f, 0f, -15f, 15f
                        ).apply {
                            duration = 1500
                            repeatMode = Animation.REVERSE
                            repeatCount = Animation.INFINITE
                            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        }
                        startAnimation(floatAnim)
                    }

                    val titulo = TextView(context).apply {
                        text = "ACTUALIZACIÓN EN CURSO"
                        setTextColor(Color.WHITE)
                        textSize = 22f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        letterSpacing = 0.05f
                    }

                    tvProgresoOta = TextView(context).apply {
                        text = "Conectando con servidor seguro..."
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 16f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 20, 0, 50) }
                    }

                    pbProgresoOta = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = true
                        max = 100
                        progress = 0
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                        }
                        scaleY = 1.5f
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 30)
                    }

                    val textoAviso = TextView(context).apply {
                        text = "⚠️ Por favor, no apague ni desconecte el equipo."
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 60, 0, 0) }
                    }

                    cardLayout.addView(icono)
                    cardLayout.addView(titulo)
                    cardLayout.addView(tvProgresoOta)
                    cardLayout.addView(pbProgresoOta)

                    rootLayout.addView(cardLayout)
                    rootLayout.addView(textoAviso)

                    setContentView(rootLayout)
                    setCancelable(false)
                    show()
                }
            } catch (e: Exception) {
                AppLog.error("Error al mostrar UI de OTA: ${e.message}")
            }
        }
    }

    private fun actualizarProgresoOTA(porcentaje: Int, texto: String, indeterminado: Boolean) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            tvProgresoOta?.text = texto
            pbProgresoOta?.isIndeterminate = indeterminado
            if (!indeterminado) {
                pbProgresoOta?.progress = porcentaje
            }
        }
    }

    private fun comprobarActualizacionOTA(forzada: Boolean = false) {
        if (OtaRuntimeState.enCurso) {
            AppLog.warning(
                "OTA -> comprobación ignorada porque ya existe una OTA en curso " +
                        "hacia v${OtaRuntimeState.versionObjetivo}."
            )
            if (forzada) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "⏳ Ya hay una actualización OTA en curso.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val urlOtaSinCache = "$URL_OTA_JSON?t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(urlOtaSinCache).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.error("OTA Error Red consultando version.json: ${e.message}")
                if (forzada) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "❌ No se pudo consultar la OTA. Revisa la conexión.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val jsonStr = response.body?.string()
                    if (!response.isSuccessful || jsonStr.isNullOrEmpty()) {
                        AppLog.error("OTA JSON Falló: HTTP ${response.code}")
                        if (forzada) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "❌ El servidor OTA devolvió HTTP ${response.code}.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        return
                    }

                    val jsonObject = JSONObject(jsonStr)
                    val versionNube = jsonObject.getInt("versionCode")
                    val apkUrl = jsonObject.getString("apkUrl")

                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    val versionActual: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
                    }

                    AppLog.info(
                        "OTA Check -> Local=$versionActual | Nube=$versionNube | Manual=$forzada"
                    )

                    if (versionNube > versionActual) {
                        if (!OtaRuntimeState.intentarIniciar(versionNube)) {
                            AppLog.warning("OTA -> otro proceso se adelantó e inició la actualización.")
                            return
                        }

                        AppLog.ota(
                            "===== INICIO OTA v$versionActual -> v$versionNube ====="
                        )
                        AppLog.ota("URL APK: $apkUrl")
                        descargarYInstalarAPK(apkUrl, versionNube)
                    } else {
                        AppLog.success(
                            "Aplicación al día (Local: $versionActual >= Nube: $versionNube)."
                        )
                        if (forzada) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "✅ Launcher TGT ya está actualizado (v$versionActual).",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLog.error("Excepción procesando JSON OTA: ${e.message}")
                    if (forzada) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "❌ Error procesando la información OTA.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun comprobarActualizacionPiloto() {
        val deviceId = MdmConfigCache(this).currentDeviceId()
        val assignment = MdmPilotOtaStore.load(this, deviceId)
            .onFailure { error -> AppLog.error("OTA PILOTO -> asignación no disponible: ${error.message}") }
            .getOrNull() ?: return
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        if (!assignment.isEligible(currentVersion, System.currentTimeMillis())) {
            if (assignment.versionCode.toLong() <= currentVersion) MdmPilotOtaStore.clear(this)
            AppLog.info(
                "OTA PILOTO -> sin actualización aplicable; local=$currentVersion " +
                    "target=${assignment.versionCode}"
            )
            return
        }
        if (!OtaRuntimeState.intentarIniciar(assignment.versionCode)) {
            AppLog.warning("OTA PILOTO -> otra actualización ya está en curso")
            return
        }
        AppLog.ota(
            "OTA PILOTO AUTENTICADA -> assignment=${assignment.assignmentId}; " +
                "local=$currentVersion; target=${assignment.versionCode}"
        )
        descargarYInstalarAPK(
            assignment.apkUrl,
            assignment.versionCode,
            assignment.sha256,
            assignment.sizeBytes
        )
    }

    private fun descargarYInstalarAPK(
        url: String,
        versionEsperada: Int,
        sha256Esperado: String? = null,
        bytesEsperados: Long? = null
    ) {
        mostrarPantallaOTA()

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                OtaRuntimeState.finalizar()
                AppLog.error("Error de red descargando APK: ${e.message}")
                actualizarProgresoOTA(0, "Error de red al descargar. Reintentando luego.", false)
                handler.postDelayed({
                    runOnUiThread {
                        try { dialogoOTA?.dismiss() } catch (_: Exception) {}
                    }
                }, 4000)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        OtaRuntimeState.finalizar()
                        AppLog.error("Error HTTP descargando APK: Código ${response.code}")
                        actualizarProgresoOTA(0, "Error en el servidor de descargas.", false)
                        handler.postDelayed({
                            runOnUiThread {
                                try { dialogoOTA?.dismiss() } catch (_: Exception) {}
                            }
                        }, 4000)
                        return
                    }

                    val body = response.body
                    if (body == null) {
                        OtaRuntimeState.finalizar()
                        AppLog.error("Error crítico: El cuerpo de respuesta está vacío")
                        actualizarProgresoOTA(0, "La descarga OTA llegó vacía.", false)
                        return
                    }

                    val file = File(getExternalFilesDir(null), "update.apk")
                    val contentLength = body.contentLength()

                    body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            var totalBytesRead: Long = 0

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                val mbLeidos = String.format(
                                    Locale.US,
                                    "%.2f",
                                    totalBytesRead / (1024.0 * 1024.0)
                                )

                                if (contentLength > 0) {
                                    val progreso = ((totalBytesRead * 100) / contentLength).toInt()
                                    val mbTotales = String.format(
                                        Locale.US,
                                        "%.2f",
                                        contentLength / (1024.0 * 1024.0)
                                    )
                                    actualizarProgresoOTA(
                                        progreso,
                                        "Descargando actualización... $progreso%\n($mbLeidos MB / $mbTotales MB)",
                                        false
                                    )
                                } else {
                                    actualizarProgresoOTA(
                                        0,
                                        "Descargando actualización...\n($mbLeidos MB descargados)",
                                        true
                                    )
                                }
                            }
                            outputStream.flush()
                        }
                    }

                    AppLog.success(
                        "OTA -> APK descargado (${file.length()} bytes). Validando paquete y versión..."
                    )
                    actualizarProgresoOTA(
                        100,
                        "🔎 Validando actualización antes de instalar...",
                        true
                    )

                    if (!validarApkDescargado(file, versionEsperada, sha256Esperado, bytesEsperados)) {
                        OtaRuntimeState.finalizar()
                        try { file.delete() } catch (_: Exception) {}
                        actualizarProgresoOTA(
                            0,
                            "❌ APK rechazada: identidad o integridad incorrectas.",
                            false
                        )
                        handler.postDelayed({
                            runOnUiThread {
                                try { dialogoOTA?.dismiss() } catch (_: Exception) {}
                            }
                        }, 5000)
                        return
                    }

                    actualizarProgresoOTA(
                        100,
                        "⚙️ Instalando nueva versión...\nNo apague el dispositivo.",
                        true
                    )
                    instalarApkSilenciosa(file)

                } catch (e: Exception) {
                    OtaRuntimeState.finalizar()
                    AppLog.error("Excepción guardando/validando APK OTA: ${e.message}")
                    actualizarProgresoOTA(0, "Error preparando la actualización.", false)
                    handler.postDelayed({
                        runOnUiThread {
                            try { dialogoOTA?.dismiss() } catch (_: Exception) {}
                        }
                    }, 4000)
                }
            }
        })
    }

    private fun validarApkDescargado(
        apkFile: File,
        versionEsperada: Int,
        sha256Esperado: String? = null,
        bytesEsperados: Long? = null
    ): Boolean {
        return try {
            if (bytesEsperados != null && bytesEsperados > 0L && apkFile.length() != bytesEsperados) {
                AppLog.error(
                    "OTA RECHAZADA -> tamaño incorrecto. Esperado=$bytesEsperados Recibido=${apkFile.length()}"
                )
                return false
            }
            if (!sha256Esperado.isNullOrBlank()) {
                val recibido = sha256Archivo(apkFile)
                if (!MessageDigest.isEqual(
                        sha256Esperado.lowercase(Locale.US).toByteArray(),
                        recibido.toByteArray()
                    )
                ) {
                    AppLog.error("OTA RECHAZADA -> SHA-256 no coincide con la asignación firmada")
                    return false
                }
            }
            val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(signingFlags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, signingFlags)
            }

            if (packageInfo == null) {
                AppLog.error("OTA VALIDACIÓN -> Android no puede interpretar el APK descargado.")
                return false
            }

            val apkPackage = packageInfo.packageName
            val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            val instalada = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(signingFlags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, signingFlags)
            }
            val versionLocal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instalada.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                instalada.versionCode.toLong()
            }

            AppLog.info(
                "OTA VALIDACIÓN -> Package=$apkPackage | APKVersion=$apkVersionCode | " +
                        "Esperada=$versionEsperada | Local=$versionLocal | Bytes=${apkFile.length()}"
            )

            when {
                apkPackage != packageName -> {
                    AppLog.error(
                        "OTA RECHAZADA -> package incorrecto. Esperado=$packageName Recibido=$apkPackage"
                    )
                    false
                }
                apkVersionCode != versionEsperada.toLong() -> {
                    AppLog.error(
                        "OTA RECHAZADA -> versionCode del APK ($apkVersionCode) no coincide con version.json ($versionEsperada)."
                    )
                    false
                }
                apkVersionCode <= versionLocal -> {
                    AppLog.error(
                        "OTA RECHAZADA -> el APK no es una versión superior a la instalada."
                    )
                    false
                }
                certificadosSha256(packageInfo) != certificadosSha256(instalada) -> {
                    AppLog.error("OTA RECHAZADA -> certificado de firma incompatible")
                    false
                }
                else -> {
                    AppLog.success(
                        "OTA VALIDACIÓN OK -> package y versionCode correctos. Preparada para PackageInstaller."
                    )
                    true
                }
            }
        } catch (e: Exception) {
            AppLog.error(
                "OTA VALIDACIÓN -> excepción leyendo metadata del APK: ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    private fun sha256Archivo(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun certificadosSha256(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.toSet()
    }

    private fun instalarApkSilenciosa(apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() <= 0L) {
                OtaRuntimeState.finalizar()
                AppLog.error("OTA -> APK local inexistente o vacío: ${apkFile.absolutePath}")
                actualizarProgresoOTA(0, "El APK descargado no es válido.", false)
                return
            }

            val packageInstaller = packageManager.packageInstaller

            // Limpiamos sesiones antiguas pertenecientes a esta aplicación para no dejar
            // instalaciones huérfanas de intentos OTA anteriores.
            try {
                val sessions = packageInstaller.mySessions
                for (sessionInfo in sessions) {
                    try {
                        val openSession = packageInstaller.openSession(sessionInfo.sessionId)
                        openSession.abandon()
                        AppLog.ota(
                            "Sesión PackageInstaller anterior (${sessionInfo.sessionId}) abortada correctamente."
                        )
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                AppLog.warning("OTA -> no se pudieron revisar sesiones anteriores: ${e.message}")
            }

            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val esDeviceOwner = dpm.isDeviceOwnerApp(packageName)
            val puedeSolicitarInstalaciones =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    packageManager.canRequestPackageInstalls()
                } else {
                    true
                }

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(packageName)
                setSize(apkFile.length())

                // Android 12+ permite indicar que preferimos una actualización sin intervención.
                // Si Android no puede concederla, devolverá STATUS_PENDING_USER_ACTION y el
                // ApkInstallReceiver abrirá correctamente la pantalla de confirmación.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    )
                }
            }

            AppLog.info(
                "OTA PackageInstaller PRE-COMMIT -> " +
                        "DeviceOwner=$esDeviceOwner | " +
                        "CanRequestInstalls=$puedeSolicitarInstalaciones | " +
                        "AndroidAPI=${Build.VERSION.SDK_INT} | " +
                        "TargetSDK=${applicationInfo.targetSdkVersion} | " +
                        "APKBytes=${apkFile.length()} | " +
                        "RequireUserAction=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "NOT_REQUIRED" else "DEFAULT"}"
            )

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            try {
                session.openWrite("LauncherUpdate", 0, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { fis ->
                        val buffer = ByteArray(65536)
                        var length: Int

                        while (fis.read(buffer).also { length = it } != -1) {
                            out.write(buffer, 0, length)
                        }

                        session.fsync(out)
                    }
                }

                var pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // PackageInstaller necesita poder rellenar los EXTRA_STATUS del PendingIntent.
                    pendingFlags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                }

                val callbackIntent = Intent(this, ApkInstallReceiver::class.java).apply {
                    action = "com.grupotgt.launcherkioscotgt.INSTALL_COMPLETE"
                    putExtra("ota_session_id", sessionId)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    callbackIntent,
                    pendingFlags
                )

                session.commit(pendingIntent.intentSender)

                AppLog.ota(
                    "Sesión de PackageInstaller enviada a Android con éxito. " +
                            "Session=$sessionId. Esperando callback..."
                )
            } catch (e: Exception) {
                try {
                    session.abandon()
                } catch (_: Exception) {
                }
                throw e
            } finally {
                try {
                    session.close()
                } catch (_: Exception) {
                }
            }

        } catch (e: Exception) {
            OtaRuntimeState.finalizar()
            AppLog.error(
                "Fallo crítico al invocar PackageInstaller: ${e.javaClass.simpleName}: ${e.message}"
            )
            actualizarProgresoOTA(0, "Fallo al iniciar la instalación.", false)
            handler.postDelayed({
                runOnUiThread {
                    try {
                        dialogoOTA?.dismiss()
                    } catch (_: Exception) {
                    }
                }
            }, 4000)
        }
    }

    // --- SISTEMA DE INVENTARIO Y TELEMETRÍA MDM TGT ---
    private fun enviarTelemetriaMDM() {
        MdmHeartbeatScheduler.enqueueImmediate(this)
    }
}
