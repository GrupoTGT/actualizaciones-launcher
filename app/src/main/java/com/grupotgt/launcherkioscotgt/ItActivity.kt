package com.grupotgt.launcherkioscotgt

import android.Manifest
import android.app.ActivityManager
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ItActivity : AppCompatActivity() {

    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"

    private val handlerMantenimiento = Handler(Looper.getMainLooper())
    private var tvEstadoMantenimiento: TextView? = null
    private var btnModoMantenimiento: Button? = null
    private var btnFinalizarMantenimiento: Button? = null

    private val runnableEstadoMantenimiento = object : Runnable {
        override fun run() {
            actualizarEstadoMantenimientoUI()
            handlerMantenimiento.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_it)

        // V64: Panel IT retro-terminal + Wi-Fi/Logs unificados. Edge-to-edge controlado para respetar
        // de forma real los márgenes superiores e inferiores en Android 15/16.
        val rootScroll = findViewById<ScrollView>(R.id.itRootScroll)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor("#020704")
        window.navigationBarColor = Color.parseColor("#020704")
        WindowInsetsControllerCompat(window, rootScroll).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val baseLeft = rootScroll.paddingLeft
        val baseTop = rootScroll.paddingTop
        val baseRight = rootScroll.paddingRight
        val baseBottom = rootScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft,
                baseTop + systemBars.top,
                baseRight,
                baseBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(rootScroll)

        val etUbicacion = findViewById<AutoCompleteTextView>(R.id.etUbicacion)
        val btnGuardar = findViewById<Button>(R.id.btnGuardarConfig)
        val btnTestSincro = findViewById<Button>(R.id.btnTestSincro)
        val btnVerLogsIt = findViewById<Button>(R.id.btnVerLogsIt)
        val btnWifiDiag = findViewById<Button>(R.id.btnWifiDiag)
        val btnForzarOTA = findViewById<Button>(R.id.btnForzarOTA)
        val btnPurgarApks = findViewById<Button>(R.id.btnPurgarApks)
        val btnCerrarPanelIT = findViewById<Button>(R.id.btnCerrarPanelIT)

        tvEstadoMantenimiento = findViewById(R.id.tvEstadoMantenimiento)
        btnModoMantenimiento = findViewById(R.id.btnModoMantenimiento)
        btnFinalizarMantenimiento = findViewById(R.id.btnFinalizarMantenimiento)

        val tvEstadoKiosco = findViewById<TextView>(R.id.tvEstadoKiosco)
        val tvVersionApp = findViewById<TextView>(R.id.tvVersionApp)
        val tvEstadoOTA = findViewById<TextView>(R.id.tvEstadoOTA)

        val tvSaludAndroid = findViewById<TextView>(R.id.tvSaludAndroid)
        val tvSaludDispositivo = findViewById<TextView>(R.id.tvSaludDispositivo)
        val tvSaludBateria = findViewById<TextView>(R.id.tvSaludBateria)
        val tvSaludRam = findViewById<TextView>(R.id.tvSaludRam)
        val tvTelemetriaUptime = findViewById<TextView>(R.id.tvTelemetriaUptime)
        val tvTelemetriaAlmacenamiento = findViewById<TextView>(R.id.tvTelemetriaAlmacenamiento)

        val tvGestionDeviceOwner = findViewById<TextView>(R.id.tvGestionDeviceOwner)
        val tvGestionKiosco = findViewById<TextView>(R.id.tvGestionKiosco)
        val tvGestionPoliticas = findViewById<TextView>(R.id.tvGestionPoliticas)
        val tvGestionConectividad = findViewById<TextView>(R.id.tvGestionConectividad)
        val tvGestionPantalla = findViewById<TextView>(R.id.tvGestionPantalla)
        val tvGestionSonido = findViewById<TextView>(R.id.tvGestionSonido)
        val tvUltimaSincro = findViewById<TextView>(R.id.tvUltimaSincro)

        val tvDiagPing = findViewById<TextView>(R.id.tvDiagPing)
        val tvTelemetriaRed = findViewById<TextView>(R.id.tvTelemetriaRed)
        val tvTelemetriaMovil = findViewById<TextView>(R.id.tvTelemetriaMovil)
        val tvTelemetriaHardware = findViewById<TextView>(R.id.tvTelemetriaHardware)

        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val ubicacionActual = prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4")
        etUbicacion.setText(ubicacionActual)
        cargarSeccionesEnDesplegable(etUbicacion, prefs)

        val colorOk = Color.parseColor("#A7FFB1")
        val colorWarn = Color.parseColor("#FFE58A")
        val colorError = Color.parseColor("#FF7B7B")
        val colorNormal = Color.parseColor("#B9F7C1")

        // -----------------------------------------------------------------
        // IDENTIDAD + VERSIÓN + OTA
        // -----------------------------------------------------------------
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            val fechaActualizacion = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(Date(pInfo.lastUpdateTime))

            val instalador = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    packageManager.getInstallSourceInfo(packageName).installingPackageName
                        ?: "Android / sistema"
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstallerPackageName(packageName) ?: "Android / sistema"
                }
            } catch (_: Exception) {
                "No disponible"
            }

            tvVersionApp.text = "Version instalada      v$versionName ($versionCode)"
            tvEstadoOTA.text =
                "OTA automatica          ACTIVA\n" +
                        "Comprobacion             cada 2 min\n" +
                        "Ult. instalacion          $fechaActualizacion\n" +
                        "Origen Android            $instalador"
        } catch (e: Exception) {
            tvVersionApp.text = "Version instalada      NO DISPONIBLE"
            tvVersionApp.setTextColor(colorWarn)
            tvEstadoOTA.text = "OTA: no se pudo leer la auditoria local"
            tvEstadoOTA.setTextColor(colorWarn)
        }

        // -----------------------------------------------------------------
        // KIOSCO / DEVICE OWNER / POLÍTICAS
        // -----------------------------------------------------------------
        var esDeviceOwner = false
        var lockTaskActivo = false
        var lockTaskPermitido = false
        val mantenimientoActivo = MaintenanceModeManager.estaActivo(this)

        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            esDeviceOwner = dpm.isDeviceOwnerApp(packageName)
            lockTaskPermitido = try { dpm.isLockTaskPermitted(packageName) } catch (_: Exception) { false }

            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            lockTaskActivo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            } else {
                @Suppress("DEPRECATION")
                activityManager.isInLockTaskMode
            }

            val estadoPrincipal = when {
                mantenimientoActivo -> "● MANTENIMIENTO IT ACTIVO"
                esDeviceOwner && lockTaskActivo -> "● KIOSCO ACTIVO  ·  DEVICE OWNER OK"
                esDeviceOwner -> "● DEVICE OWNER OK  ·  KIOSCO EN ESPERA"
                else -> "● ATENCION  ·  DEVICE OWNER NO ACTIVO"
            }
            tvEstadoKiosco.text = estadoPrincipal
            tvEstadoKiosco.setTextColor(
                when {
                    mantenimientoActivo -> colorWarn
                    esDeviceOwner && lockTaskActivo -> colorOk
                    else -> colorError
                }
            )

            tvGestionDeviceOwner.text = "Device Owner   ${if (esDeviceOwner) "OK" else "ERROR"}"
            tvGestionDeviceOwner.setTextColor(if (esDeviceOwner) colorOk else colorError)

            tvGestionKiosco.text = "Kiosco         ${when {
                mantenimientoActivo -> "MANT."
                lockTaskActivo -> "ACTIVO"
                else -> "INACTIVO"
            }}"
            tvGestionKiosco.setTextColor(when {
                mantenimientoActivo -> colorWarn
                lockTaskActivo -> colorOk
                else -> colorWarn
            })

            val politicasOk = esDeviceOwner && (lockTaskPermitido || mantenimientoActivo)
            tvGestionPoliticas.text = "Politicas      ${if (politicasOk) "OK" else "REVISAR"}"
            tvGestionPoliticas.setTextColor(if (politicasOk) colorOk else colorWarn)
        } catch (e: Exception) {
            tvEstadoKiosco.text = "● DIAGNOSTICO KIOSCO NO DISPONIBLE"
            tvEstadoKiosco.setTextColor(colorWarn)
            tvGestionDeviceOwner.text = "Device Owner   --"
            tvGestionKiosco.text = "Kiosco         --"
            tvGestionPoliticas.text = "Politicas      --"
        }

        // -----------------------------------------------------------------
        // SALUD DEL TERMINAL
        // -----------------------------------------------------------------
        tvSaludAndroid.text = "Android        ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"
        tvSaludDispositivo.text = "Dispositivo    ${Build.MANUFACTURER} ${Build.MODEL}"

        val uptimeMillis = SystemClock.elapsedRealtime()
        val dias = TimeUnit.MILLISECONDS.toDays(uptimeMillis)
        val horas = TimeUnit.MILLISECONDS.toHours(uptimeMillis) % 24
        val minutos = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60
        tvTelemetriaUptime.text = "Uptime         ${dias}d ${horas}h ${minutos}m"

        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val freeBytes = stat.availableBytes
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
            val usedPct = if (totalBytes > 0L) ((usedBytes * 100L) / totalBytes).toInt() else 0
            val freeGb = freeBytes / (1024L * 1024L * 1024L)
            tvTelemetriaAlmacenamiento.text = "Almacenam.     ${usedPct}% / ${freeGb}GB libres"
            tvTelemetriaAlmacenamiento.setTextColor(if (usedPct >= 90) colorError else if (usedPct >= 80) colorWarn else colorNormal)
        } catch (_: Exception) {
            tvTelemetriaAlmacenamiento.text = "Almacenam.     --"
        }

        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val ramTotalMb = memInfo.totalMem / (1024L * 1024L)
        val ramLibreMb = memInfo.availMem / (1024L * 1024L)
        val ramUsadaPct = if (memInfo.totalMem > 0L) {
            (((memInfo.totalMem - memInfo.availMem) * 100L) / memInfo.totalMem).toInt()
        } else 0
        tvSaludRam.text = "RAM            ${ramUsadaPct}%"
        tvSaludRam.setTextColor(if (ramUsadaPct >= 90) colorError else if (ramUsadaPct >= 80) colorWarn else colorNormal)

        val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (batteryLevel >= 0 && batteryScale > 0) batteryLevel * 100 / batteryScale else -1
        val tempInt = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temperaturaC = tempInt / 10.0
        tvSaludBateria.text = "Bateria        ${if (batteryPct >= 0) "$batteryPct%" else "--"}"
        if (batteryPct in 0..19) tvSaludBateria.setTextColor(colorWarn)

        // -----------------------------------------------------------------
        // RED / WI-FI / IP
        // -----------------------------------------------------------------
        var wifiOk = false
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val rssi = wifiInfo?.rssi ?: -100
            val nivelWifi = WifiManager.calculateSignalLevel(rssi, 5)
            val calidades = listOf("MUY BAJA", "BAJA", "BUENA", "EXCELENTE", "MAXIMA")
            val calidad = if (nivelWifi in 0..4) calidades[nivelWifi] else "--"
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "--"
            wifiOk = ssid.isNotBlank() && ssid != "<unknown ssid>" && rssi > -95

            var ipLocal = "--"
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
                            ipLocal = addr.hostAddress.toString()
                        }
                    }
                }
            } catch (_: Exception) {}

            tvTelemetriaRed.text = "Wi-Fi  $ssid  |  $rssi dBm ($calidad)\nIP     $ipLocal\nToca WIFI para abrir el analizador"
            tvTelemetriaRed.setTextColor(if (wifiOk) colorOk else colorWarn)
            tvGestionConectividad.text = "Conectividad   ${if (wifiOk) "WIFI OK" else "REVISAR"}"
            tvGestionConectividad.setTextColor(if (wifiOk) colorOk else colorWarn)
        } catch (_: Exception) {
            tvTelemetriaRed.text = "Wi-Fi / IP: no disponibles"
            tvTelemetriaRed.setTextColor(colorWarn)
            tvGestionConectividad.text = "Conectividad   --"
        }

        // -----------------------------------------------------------------
        // PANTALLA / SONIDO / HARDWARE / ADB
        // -----------------------------------------------------------------
        try {
            val brillo = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            val brilloPct = if (brillo >= 0) ((brillo * 100) / 255).coerceIn(0, 100) else -1
            tvGestionPantalla.text = "Pantalla       ${if (brilloPct >= 0) "$brilloPct%" else "--"}"

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val volMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val volPct = (volMusic * 100 / maxMusic).coerceIn(0, 100)
            tvGestionSonido.text = "Sonido         $volPct%"
            tvGestionSonido.setTextColor(if (volPct == 0) colorWarn else colorOk)

            val adbEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            tvTelemetriaHardware.text =
                "RAM libre      ${ramLibreMb}MB / ${ramTotalMb}MB\n" +
                        "Temperatura    ${String.format(Locale.US, "%.1f", temperaturaC)} C\n" +
                        "ADB            ${if (adbEnabled) "ACTIVO" else "INACTIVO"}"
        } catch (_: Exception) {
            tvGestionPantalla.text = "Pantalla       --"
            tvGestionSonido.text = "Sonido         --"
            tvTelemetriaHardware.text = "Hardware: informacion no disponible"
        }

        // SIM / OPERADOR
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val operadorName = telephonyManager.networkOperatorName?.takeIf { it.isNotBlank() } ?: "--"
            val simState = when (telephonyManager.simState) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "AUSENTE"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "BLOQUEADA"
                else -> "--"
            }
            tvTelemetriaMovil.text = "Operador $operadorName  |  SIM $simState"
        } catch (_: Exception) {
            tvTelemetriaMovil.text = "Operador / SIM: no disponible"
        }

        val ultimaSincro = prefs.getString("ultima_sincro", null)
        tvUltimaSincro.text = "Ult. sincro    ${ultimaSincro ?: "--"}"
        tvDiagPing.text = "Ping / salida a Internet: pendiente de test manual"

        // -----------------------------------------------------------------
        // ACCIONES - mismas funciones validadas de V62, sólo cambia la UI.
        // -----------------------------------------------------------------
        tvTelemetriaRed.setOnClickListener { solicitarPermisosYMostrarWifi() }
        btnWifiDiag.setOnClickListener { solicitarPermisosYMostrarWifi() }

        btnGuardar.setOnClickListener {
            val nuevaUbicacion = etUbicacion.text.toString().trim()
            if (nuevaUbicacion.isNotEmpty()) {
                prefs.edit().putString("ubicacion_dispositivo", nuevaUbicacion).apply()
                AppLog.registrar("IT: ubicacion cambiada manualmente a $nuevaUbicacion")
                Toast.makeText(this, "Identificador guardado", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "La ubicacion no puede estar vacia", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestSincro.setOnClickListener {
            Toast.makeText(this, "Sincronizando datos y probando red...", Toast.LENGTH_SHORT).show()
            AppLog.registrar("IT: sincronizacion manual + test de red")
            tvDiagPing.text = "Ping / salida a Internet: comprobando..."
            tvDiagPing.setTextColor(colorWarn)

            val pingClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
            val pingRequest = Request.Builder().url("https://www.google.com").build()
            val inicioMs = System.currentTimeMillis()

            pingClient.newCall(pingRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AppLog.registrar("Test Conectividad: SIN SALIDA A INTERNET (${e.message})")
                    runOnUiThread {
                        tvDiagPing.text = "Ping / salida a Internet: ERROR"
                        tvDiagPing.setTextColor(colorError)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val latencia = System.currentTimeMillis() - inicioMs
                    AppLog.registrar("Test Conectividad: OK (${latencia}ms)")
                    response.close()
                    runOnUiThread {
                        tvDiagPing.text = "Ping / salida a Internet: OK  ${latencia}ms"
                        tvDiagPing.setTextColor(if (latencia > 500) colorWarn else colorOk)
                    }
                }
            })

            val client = OkHttpClient()
            val request = Request.Builder().url(URL_GOOGLE_SHEETS_CSV).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AppLog.registrar("Error red sincronizacion IT: ${e.message}")
                    runOnUiThread {
                        Toast.makeText(this@ItActivity, "Error de red al sincronizar", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val csvData = response.body?.string()
                    if (!response.isSuccessful || csvData.isNullOrEmpty()) {
                        AppLog.registrar("Error respuesta CSV vacia o incorrecta")
                        response.close()
                        return
                    }

                    try {
                        val fechaActual = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                        prefs.edit()
                            .putString("csv_cache_data", csvData)
                            .putString("ultima_sincro", fechaActual)
                            .apply()

                        runOnUiThread {
                            cargarSeccionesEnDesplegable(etUbicacion, prefs)
                            tvUltimaSincro.text = "Ult. sincro    $fechaActual"
                            tvUltimaSincro.setTextColor(colorOk)
                            Toast.makeText(this@ItActivity, "Sincronizacion completada", Toast.LENGTH_LONG).show()
                        }
                        AppLog.registrar("Sincronizacion manual completada y guardada en cache")
                    } catch (e: Exception) {
                        AppLog.registrar("Excepcion guardando cache manual: ${e.message}")
                    } finally {
                        response.close()
                    }
                }
            })
        }

        btnVerLogsIt.setOnClickListener { mostrarDialogoLogsEnPantalla() }

        // OTA: se conserva el motor único de MainActivity. No se duplica PackageInstaller.
        btnForzarOTA.setOnClickListener {
            AppLog.registrar("OTA manual solicitada desde Panel IT V64")
            Toast.makeText(this, "Abriendo motor OTA del Launcher...", Toast.LENGTH_SHORT).show()
            val otaIntent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_FORZAR_OTA, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(otaIntent)
            finish()
        }

        btnPurgarApks.setOnClickListener { purgarApksResiduales() }

        btnModoMantenimiento?.setOnClickListener { mostrarSelectorMantenimiento() }
        btnFinalizarMantenimiento?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Finalizar mantenimiento")
                .setMessage("El terminal volvera inmediatamente al modo kiosco protegido.")
                .setNegativeButton("CANCELAR", null)
                .setPositiveButton("BLOQUEAR AHORA") { _, _ -> solicitarFinMantenimiento() }
                .show()
        }

        actualizarEstadoMantenimientoUI()
        btnCerrarPanelIT.setOnClickListener { finish() }
    }

    private fun mostrarSelectorMantenimiento() {
        if (MaintenanceModeManager.estaActivo(this)) {
            Toast.makeText(
                this,
                "Mantenimiento ya activo (${MaintenanceModeManager.descripcion(this)}).",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val opciones = arrayOf(
            "15 minutos",
            "30 minutos",
            "1 hora",
            "Indefinido"
        )

        var seleccion = 0

        AlertDialog.Builder(this)
            .setTitle("MODO MANTENIMIENTO // DURACION")
            .setSingleChoiceItems(opciones, seleccion) { _, which ->
                seleccion = which
            }
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("ACTIVAR") { _, _ ->
                when (seleccion) {
                    0 -> solicitarMantenimiento(15L * 60L * 1000L, "15 minutos")
                    1 -> solicitarMantenimiento(30L * 60L * 1000L, "30 minutos")
                    2 -> solicitarMantenimiento(60L * 60L * 1000L, "1 hora")
                    3 -> confirmarMantenimientoIndefinido()
                }
            }
            .show()
    }

    private fun confirmarMantenimientoIndefinido() {
        AlertDialog.Builder(this)
            .setTitle("MANTENIMIENTO INDEFINIDO")
            .setMessage(
                "El terminal permanecerá en Android normal hasta que IT pulse FINALIZAR MANTENIMIENTO.\n\n" +
                        "Si el teléfono se reinicia, volverá automáticamente al modo kiosco protegido."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("ACTIVAR INDEFINIDO") { _, _ ->
                solicitarMantenimiento(-1L, "indefinido")
            }
            .show()
    }

    private fun solicitarMantenimiento(duracionMs: Long, etiqueta: String) {
        AppLog.registrar("IT solicita Modo Mantenimiento: $etiqueta")

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_MANTENIMIENTO_MS, duracionMs)
            putExtra(MainActivity.EXTRA_ABRIR_AJUSTES, false)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        startActivity(intent)
        finish()
    }

    private fun solicitarFinMantenimiento() {
        AppLog.registrar("IT solicita finalizar Modo Mantenimiento manualmente")

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_FINALIZAR_MANTENIMIENTO, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        startActivity(intent)
        finish()
    }

    private fun actualizarEstadoMantenimientoUI() {
        val activo = MaintenanceModeManager.estaActivo(this)

        if (activo) {
            tvEstadoMantenimiento?.text =
                "MANTENIMIENTO ACTIVO  //  ${MaintenanceModeManager.descripcion(this)}\n" +
                        "Reinicio = retorno automatico a modo protegido"
            tvEstadoMantenimiento?.setTextColor(Color.parseColor("#FFE58A"))

            btnModoMantenimiento?.isEnabled = false
            btnModoMantenimiento?.text = "MANTENIMIENTO ACTIVO"

            btnFinalizarMantenimiento?.visibility = android.view.View.VISIBLE
        } else {
            tvEstadoMantenimiento?.text =
                "MODO PRODUCCION  //  HOME + LOCK TASK PROTEGIDOS"
            tvEstadoMantenimiento?.setTextColor(Color.parseColor("#A7FFB1"))

            btnModoMantenimiento?.isEnabled = true
            btnModoMantenimiento?.text = "ACTIVAR MANTENIMIENTO"

            btnFinalizarMantenimiento?.visibility = android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        handlerMantenimiento.removeCallbacks(runnableEstadoMantenimiento)
        handlerMantenimiento.post(runnableEstadoMantenimiento)
    }

    override fun onPause() {
        handlerMantenimiento.removeCallbacks(runnableEstadoMantenimiento)
        super.onPause()
    }

    // --- FUNCIONES DEL ANALIZADOR WI-FI ---

    private fun solicitarPermisosYMostrarWifi() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        } else {
            mostrarAnalizadorWifi()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mostrarAnalizadorWifi()
            } else {
                Toast.makeText(this, "⚠️ Permiso de ubicación denegado. No se podrán leer los detalles del Wi-Fi.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun mostrarAnalizadorWifi() {
        val verde = Color.parseColor("#9CFF9F")
        val verdeSuave = Color.parseColor("#B9F7C1")
        val verdePanel = Color.parseColor("#0A1F0E")
        val amarillo = Color.parseColor("#FFE58A")
        val rojo = Color.parseColor("#FF7B7B")

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = fondoRetro(Color.parseColor("#020704"), verde, 0, 1)
        }

        root.addView(textoRetro("// ANALIZADOR DE RED", 23f, verde, true, Gravity.CENTER))
        root.addView(textoRetro("TGT NET-SCAN 64  ·  DIAGNÓSTICO EN TIEMPO REAL", 11f, Color.parseColor("#6FCB78"), false, Gravity.CENTER).apply {
            setPadding(0, dp(3), 0, dp(14))
        })

        val cardEstado = tarjetaRetro(verdePanel, verde)
        val tvEstado = textoRetro("ESCANEANDO RED...", 16f, amarillo, true, Gravity.CENTER)
        val barraSenal = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(verde)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#14321A"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)).apply {
                setMargins(0, dp(12), 0, dp(12))
            }
        }
        val tvSenalGrande = textoRetro("-- dBm", 34f, verde, true, Gravity.CENTER)
        val tvCalidad = textoRetro("CALIDAD: --", 14f, verdeSuave, true, Gravity.CENTER)

        cardEstado.addView(tvEstado)
        cardEstado.addView(tvSenalGrande)
        cardEstado.addView(barraSenal)
        cardEstado.addView(tvCalidad)
        root.addView(cardEstado)

        val cardDatos = tarjetaRetro(Color.parseColor("#061008"), Color.parseColor("#2B7D39")).apply {
            val p = dp(13)
            setPadding(p, p, p, p)
        }
        val tvDatos = textoRetro("SSID          --\nIP            --\nBANDA         --\nCANAL         --\nENLACE        --", 14f, verdeSuave, false, Gravity.START).apply {
            setLineSpacing(0f, 1.35f)
        }
        cardDatos.addView(tvDatos)
        root.addView(cardDatos)

        val cardInternet = tarjetaRetro(Color.parseColor("#061008"), Color.parseColor("#2B7D39")).apply {
            val p = dp(13)
            setPadding(p, p, p, p)
        }
        val tvInternet = textoRetro("SALIDA INTERNET     PENDIENTE\nLATENCIA            --", 13f, amarillo, false, Gravity.START)
        cardInternet.addView(tvInternet)
        root.addView(cardInternet)

        val filaAcciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val btnTest = botonRetro("TEST RED", false)
        val btnCerrar = botonRetro("CERRAR", true)
        filaAcciones.addView(btnTest, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        filaAcciones.addView(btnCerrar, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(filaAcciones)

        root.addView(textoRetro("Creado por Marco Carpi\nMadecem.com", 10f, Color.parseColor("#6FCB78"), false, Gravity.CENTER).apply {
            setPadding(0, dp(12), 0, 0)
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.86f }
        }
        dialog.show()
        dialog.window?.let { ventana ->
            ventana.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(ventana, false)
            val baseLeft = root.paddingLeft
            val baseTop = root.paddingTop
            val baseRight = root.paddingRight
            val baseBottom = root.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(
                    baseLeft + barras.left,
                    baseTop + barras.top,
                    baseRight + barras.right,
                    baseBottom + barras.bottom
                )
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }

        val handler = Handler(Looper.getMainLooper())
        val updateTask = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return

                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    val wifiInfo = wifiManager?.connectionInfo

                    val rssi = wifiInfo?.rssi ?: -100
                    val ssidRaw = wifiInfo?.ssid?.replace("\"", "") ?: "--"
                    val ssid = if (ssidRaw == "<unknown ssid>") "SIN IDENTIFICAR" else ssidRaw
                    val linkSpeed = wifiInfo?.linkSpeed ?: 0
                    val frecuencia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) wifiInfo?.frequency ?: 0 else 0
                    val quality = WifiManager.calculateSignalLevel(rssi, 100).coerceIn(0, 100)

                    val estado = when {
                        rssi >= -50 -> Triple("EXCELENTE", verde, "IDEAL PARA OTA / SINCRO")
                        rssi >= -65 -> Triple("BUENA", verde, "CONEXIÓN ESTABLE")
                        rssi >= -75 -> Triple("REGULAR", amarillo, "VIGILAR COBERTURA")
                        else -> Triple("POBRE", rojo, "REVISAR COBERTURA")
                    }

                    var ipLocal = "--"
                    try {
                        @Suppress("DEPRECATION")
                        val ip = wifiInfo?.ipAddress ?: 0
                        if (ip != 0) {
                            ipLocal = String.format(
                                Locale.US,
                                "%d.%d.%d.%d",
                                (ip and 0xff),
                                (ip shr 8 and 0xff),
                                (ip shr 16 and 0xff),
                                (ip shr 24 and 0xff)
                            )
                        }
                    } catch (_: Exception) {}

                    val banda = when {
                        frecuencia in 2400..2500 -> "2.4 GHz"
                        frecuencia in 4900..5900 -> "5 GHz"
                        frecuencia in 5925..7125 -> "6 GHz"
                        else -> "--"
                    }
                    val canal = canalWifi(frecuencia)

                    tvSenalGrande.text = "$rssi dBm"
                    barraSenal.progress = quality
                    barraSenal.progressTintList = android.content.res.ColorStateList.valueOf(estado.second)
                    tvCalidad.text = "CALIDAD: ${estado.first}  ·  ${estado.third}"
                    tvCalidad.setTextColor(estado.second)
                    tvEstado.text = if (ssid == "--" || ssid == "SIN IDENTIFICAR") "SIN RED WIFI IDENTIFICADA" else "ENLACE WIFI ACTIVO"
                    tvEstado.setTextColor(if (ssid == "--" || ssid == "SIN IDENTIFICAR") amarillo else verde)
                    tvDatos.text =
                        "SSID          $ssid\n" +
                                "IP            $ipLocal\n" +
                                "BANDA         $banda\n" +
                                "CANAL         $canal\n" +
                                "ENLACE        ${linkSpeed} Mbps"
                } catch (e: Exception) {
                    tvEstado.text = "ERROR DE TELEMETRÍA WIFI"
                    tvEstado.setTextColor(rojo)
                    tvDatos.text = "No se pudo leer el hardware Wi-Fi.\n${e.message ?: "Sin detalle"}"
                }

                handler.postDelayed(this, 1000L)
            }
        }

        btnTest.setOnClickListener {
            tvInternet.text = "SALIDA INTERNET     COMPROBANDO...\nLATENCIA            --"
            tvInternet.setTextColor(amarillo)
            AppLog.registrar("IT NET-SCAN: test de salida a Internet solicitado")

            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url("https://www.google.com/generate_204").build()
            val inicio = System.currentTimeMillis()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AppLog.registrar("IT NET-SCAN: sin salida a Internet (${e.message})")
                    runOnUiThread {
                        if (dialog.isShowing) {
                            tvInternet.text = "SALIDA INTERNET     ERROR\nLATENCIA            --\nDETALLE             ${e.message ?: "SIN RESPUESTA"}"
                            tvInternet.setTextColor(rojo)
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val latencia = System.currentTimeMillis() - inicio
                    response.close()
                    AppLog.registrar("IT NET-SCAN: salida a Internet OK (${latencia}ms)")
                    runOnUiThread {
                        if (dialog.isShowing) {
                            tvInternet.text = "SALIDA INTERNET     ONLINE\nLATENCIA            ${latencia} ms"
                            tvInternet.setTextColor(if (latencia > 500) amarillo else verde)
                        }
                    }
                }
            })
        }

        btnCerrar.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { handler.removeCallbacks(updateTask) }
        handler.post(updateTask)
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun fondoRetro(
        relleno: Int,
        borde: Int,
        radioDp: Int = 12,
        grosorDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(relleno)
        setStroke(dp(grosorDp), borde)
        cornerRadius = dp(radioDp).toFloat()
    }

    private fun textoRetro(
        contenido: String,
        tamanoSp: Float,
        color: Int,
        negrita: Boolean = false,
        gravedad: Int = Gravity.START
    ): TextView = TextView(this).apply {
        text = contenido
        textSize = tamanoSp
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (negrita) Typeface.BOLD else Typeface.NORMAL)
        gravity = gravedad
        includeFontPadding = false
    }

    private fun tarjetaRetro(relleno: Int, borde: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = fondoRetro(relleno, borde, 12, 1)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(6), 0, dp(6))
        }
    }

    private fun botonRetro(texto: String, principal: Boolean): Button = Button(this).apply {
        text = texto
        textSize = 12f
        isAllCaps = false
        setTextColor(if (principal) Color.parseColor("#021006") else Color.parseColor("#9CFF9F"))
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        background = fondoRetro(
            if (principal) Color.parseColor("#9CFF9F") else Color.parseColor("#07150A"),
            Color.parseColor("#58E76C"),
            9,
            1
        )
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun canalWifi(frecuenciaMhz: Int): String = when {
        frecuenciaMhz == 2484 -> "14"
        frecuenciaMhz in 2412..2472 -> ((frecuenciaMhz - 2407) / 5).toString()
        frecuenciaMhz in 5000..5895 -> ((frecuenciaMhz - 5000) / 5).toString()
        frecuenciaMhz in 5955..7115 -> ((frecuenciaMhz - 5950) / 5).toString()
        else -> "--"
    }

    // --- RESTO DE TUS FUNCIONES ORIGINALES ---

    private fun purgarApksResiduales() {
        try {
            var bytesLiberados: Long = 0
            var archivosBorrados = 0

            val extDir = getExternalFilesDir(null)
            if (extDir != null && extDir.exists()) {
                extDir.listFiles()?.forEach { archivo ->
                    if (archivo.isFile && (archivo.name.endsWith(".apk", true) || archivo.name.contains("update", true))) {
                        val tam = archivo.length()
                        if (archivo.delete()) {
                            bytesLiberados += tam
                            archivosBorrados++
                        }
                    }
                }
            }

            val cacheDir = cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { archivo ->
                    if (archivo.isFile && archivo.name.endsWith(".apk", true)) {
                        val tam = archivo.length()
                        if (archivo.delete()) {
                            bytesLiberados += tam
                            archivosBorrados++
                        }
                    }
                }
            }

            val mbLiberados = String.format(Locale.US, "%.2f", bytesLiberados / (1024.0 * 1024.0))
            AppLog.registrar("🧹 Purga ejecutada: $archivosBorrados archivos APK eliminados ($mbLiberados MB liberados).")
            Toast.makeText(this, "🧹 Limpieza completada:\n$archivosBorrados archivo(s) eliminados ($mbLiberados MB libres)", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            AppLog.registrar("❌ Error purgando APKs residuales: ${e.message}")
            Toast.makeText(this, "❌ Error durante la purga: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cargarSeccionesEnDesplegable(autoCompleteTextView: AutoCompleteTextView?, prefs: android.content.SharedPreferences) {
        try {
            val csvCache = prefs.getString("csv_cache_data", "") ?: ""
            if (csvCache.isNotEmpty()) {
                val lineas = csvCache.replace("\r", "").split("\n")
                val seccionesSet = mutableSetOf<String>()

                for (linea in lineas) {
                    if (linea.isBlank()) continue
                    val partes = if (linea.contains(";")) linea.split(";") else linea.split(",")
                    if (partes.isNotEmpty()) {
                        val seccion = partes[0].trim().replace("\"", "").replace("\uFEFF", "")
                        if (seccion.isNotEmpty()) {
                            seccionesSet.add(seccion)
                        }
                    }
                }

                val listaSecciones = seccionesSet.toList()
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listaSecciones)
                autoCompleteTextView?.setAdapter(adapter)

                autoCompleteTextView?.setOnClickListener {
                    autoCompleteTextView.showDropDown()
                }
                autoCompleteTextView?.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        autoCompleteTextView.showDropDown()
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.registrar("❌ Error cargando secciones en desplegable: ${e.message}")
        }
    }

    private fun mostrarDialogoLogsEnPantalla() {
        val verde = Color.parseColor("#9CFF9F")
        val verdeSuave = Color.parseColor("#B9F7C1")
        val verdePanel = Color.parseColor("#07150A")
        val amarillo = Color.parseColor("#FFE58A")
        val rojo = Color.parseColor("#FF7B7B")

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = fondoRetro(Color.parseColor("#020704"), verde, 0, 1)
        }

        root.addView(textoRetro("// SYSTEM LOG", 23f, verde, true, Gravity.CENTER))
        val ubicacion = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            .getString("ubicacion_dispositivo", "Terminal") ?: "Terminal"
        root.addView(textoRetro("TERMINAL: $ubicacion  ·  TGT LOG-VIEW 64", 11f, Color.parseColor("#6FCB78"), false, Gravity.CENTER).apply {
            setPadding(0, dp(3), 0, dp(12))
        })

        val tvResumen = textoRetro("Analizando registro...", 12f, amarillo, false, Gravity.CENTER).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = fondoRetro(Color.parseColor("#061008"), Color.parseColor("#2B7D39"), 9, 1)
        }
        root.addView(tvResumen)

        val filaFiltros = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(9))
        }
        val btnTodos = botonRetro("TODO", true)
        val btnErrores = botonRetro("ERRORES", false)
        val btnUltimos = botonRetro("ÚLTIMOS", false)
        filaFiltros.addView(btnTodos, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        filaFiltros.addView(btnErrores, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        filaFiltros.addView(btnUltimos, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        root.addView(filaFiltros)

        val scrollLogs = ScrollView(this).apply {
            isFillViewport = true
            background = fondoRetro(verdePanel, Color.parseColor("#2B7D39"), 10, 1)
        }
        val textoLogs = textoRetro("", 11.5f, verdeSuave, false, Gravity.START).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        scrollLogs.addView(textoLogs)
        root.addView(scrollLogs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val filaAcciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val btnCopiar = botonRetro("COPIAR", false)
        val btnSms = botonRetro("SMS IT", false)
        val btnCerrar = botonRetro("CERRAR", true)
        filaAcciones.addView(btnCopiar, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        filaAcciones.addView(btnSms, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        filaAcciones.addView(btnCerrar, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        root.addView(filaAcciones)

        root.addView(textoRetro("Creado por Marco Carpi\nMadecem.com", 10f, Color.parseColor("#6FCB78"), false, Gravity.CENTER).apply {
            setPadding(0, dp(12), 0, 0)
        })

        var filtro = 0

        fun actualizarLogs() {
            val logs = AppLog.obtenerLogs()
            val lineas = logs.replace("\r", "").split("\n").filter { it.isNotBlank() }
            val errores = lineas.count {
                val l = it.uppercase(Locale.getDefault())
                l.contains("[ERROR]") || l.contains("ERROR") || l.contains("❌") || l.contains("FALLO")
            }
            val avisos = lineas.count {
                val l = it.uppercase(Locale.getDefault())
                l.contains("WARN") || l.contains("AVISO") || l.contains("⚠")
            }

            tvResumen.text = "EVENTOS ${lineas.size}   ·   ERRORES $errores   ·   AVISOS $avisos"
            tvResumen.setTextColor(if (errores > 0) rojo else if (avisos > 0) amarillo else verde)

            val salida = when (filtro) {
                1 -> lineas.filter {
                    val l = it.uppercase(Locale.getDefault())
                    l.contains("[ERROR]") || l.contains("ERROR") || l.contains("❌") || l.contains("FALLO")
                }
                2 -> lineas.takeLast(120)
                else -> lineas
            }

            textoLogs.text = if (salida.isEmpty()) {
                when (filtro) {
                    1 -> "// SIN ERRORES REGISTRADOS"
                    else -> "// REGISTRO VACÍO"
                }
            } else {
                salida.joinToString("\n")
            }

            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }

        fun marcarFiltro(seleccionado: Button, vararg resto: Button) {
            seleccionado.background = fondoRetro(verde, Color.parseColor("#58E76C"), 9, 1)
            seleccionado.setTextColor(Color.parseColor("#021006"))
            resto.forEach {
                it.background = fondoRetro(Color.parseColor("#07150A"), Color.parseColor("#58E76C"), 9, 1)
                it.setTextColor(verde)
            }
        }

        btnTodos.setOnClickListener {
            filtro = 0
            marcarFiltro(btnTodos, btnErrores, btnUltimos)
            actualizarLogs()
        }
        btnErrores.setOnClickListener {
            filtro = 1
            marcarFiltro(btnErrores, btnTodos, btnUltimos)
            actualizarLogs()
        }
        btnUltimos.setOnClickListener {
            filtro = 2
            marcarFiltro(btnUltimos, btnTodos, btnErrores)
            actualizarLogs()
        }
        btnCopiar.setOnClickListener {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Logs Kiosco TGT", AppLog.obtenerLogs()))
                Toast.makeText(this, "Logs copiados al portapapeles", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al copiar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        btnSms.setOnClickListener { enviarLogsPorSms() }
        btnCerrar.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.86f }
        }
        dialog.show()
        dialog.window?.let { ventana ->
            ventana.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(ventana, false)
            val baseLeft = root.paddingLeft
            val baseTop = root.paddingTop
            val baseRight = root.paddingRight
            val baseBottom = root.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(
                    baseLeft + barras.left,
                    baseTop + barras.top,
                    baseRight + barras.right,
                    baseBottom + barras.bottom
                )
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }
        marcarFiltro(btnTodos, btnErrores, btnUltimos)
        actualizarLogs()
    }

    private fun enviarLogsPorSms() {
        Thread {
            try {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val numeroIT = prefs.getString("telefono_it", "") ?: ""
                if (numeroIT.isEmpty()) {
                    runOnUiThread { Toast.makeText(this, "❌ No hay teléfono IT configurado (Columna 4)", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                val logsCompletos = AppLog.obtenerLogs()
                val mensajeFinal = "LOGS KIOSCO:\n" + if (logsCompletos.length > 600) logsCompletos.takeLast(600) else logsCompletos

                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                } else {
                    @Suppress("DEPRECATION") SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(mensajeFinal)
                smsManager.sendMultipartTextMessage(numeroIT, null, parts, null, null)

                runOnUiThread {
                    Toast.makeText(this, "✅ Logs enviados por SMS al número IT ($numeroIT)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "❌ Error enviando logs por SMS: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}