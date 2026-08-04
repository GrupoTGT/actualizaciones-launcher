package com.grupotgt.launcherkioscotgt

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
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
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ItActivity : AppCompatActivity() {

    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_it)

        val etUbicacion = findViewById<AutoCompleteTextView>(R.id.etUbicacion)
        val btnGuardar = findViewById<Button>(R.id.btnGuardarConfig)
        val btnTestSincro = findViewById<Button>(R.id.btnTestSincro)
        val btnVerLogsIt = findViewById<Button>(R.id.btnVerLogsIt)
        val btnForzarOTA = findViewById<Button>(R.id.btnForzarOTA)
        val btnPurgarApks = findViewById<Button>(R.id.btnPurgarApks)
        val btnAbrirAjustesAndroid = findViewById<Button>(R.id.btnAbrirAjustesAndroid)
        val btnCerrarPanelIT = findViewById<Button>(R.id.btnCerrarPanelIT)
        val tvEstadoKiosco = findViewById<TextView>(R.id.tvEstadoKiosco)
        val tvVersionApp = findViewById<TextView>(R.id.tvVersionApp)

        val tvTelemetriaUptime = findViewById<TextView>(R.id.tvTelemetriaUptime)
        val tvTelemetriaAlmacenamiento = findViewById<TextView>(R.id.tvTelemetriaAlmacenamiento)
        val tvTelemetriaRed = findViewById<TextView>(R.id.tvTelemetriaRed)
        val tvTelemetriaMovil = findViewById<TextView>(R.id.tvTelemetriaMovil)
        val tvTelemetriaHardware = findViewById<TextView>(R.id.tvTelemetriaHardware)

        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val ubicacionActual = prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4")
        etUbicacion?.setText(ubicacionActual)

        cargarSeccionesEnDesplegable(etUbicacion, prefs)

        // VERSIÓN DE LA APP Y DE ANDROID
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "1.0"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            val androidVer = Build.VERSION.RELEASE
            val sdkVer = Build.VERSION.SDK_INT
            tvVersionApp?.text = "📱 App: v$versionName ($versionCode) | Android: $sdkVer (v$androidVer)"
        } catch (e: Exception) {
            tvVersionApp?.text = "📱 Versión instalada: Desconocida"
        }

        tvEstadoKiosco?.text = "📦 Paquete: $packageName\n🔒 Estado: Kiosco Blindado Activo"

        // TIEMPO ACTIVO (UPTIME)
        val uptimeMillis = SystemClock.elapsedRealtime()
        val dias = TimeUnit.MILLISECONDS.toDays(uptimeMillis)
        val horas = TimeUnit.MILLISECONDS.toHours(uptimeMillis) % 24
        val minutos = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60
        tvTelemetriaUptime?.text = "⏱️ Tiempo activo: $dias días, $horas hrs, $minutos min"

        // ALMACENAMIENTO LIBRE
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val bytesDisponibles = stat.availableBlocksLong * stat.blockSizeLong
            val gbDisponibles = bytesDisponibles / (1024 * 1024 * 1024)
            tvTelemetriaAlmacenamiento?.text = "💾 Almacenamiento Libre: $gbDisponibles GB"
        } catch (e: Exception) {
            tvTelemetriaAlmacenamiento?.text = "💾 Almacenamiento Libre: Desconocido"
        }

        // TELEMETRÍA DE RED: WI-FI, RSSI E IP LOCAL
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val rssi = wifiInfo?.rssi ?: -99
            val nivelWifi = WifiManager.calculateSignalLevel(rssi, 5)
            val calidades = listOf("Muy baja", "Baja", "Buena", "Excelente", "Máxima")
            val calDesc = if (nivelWifi in 0..4) calidades[nivelWifi] else "Desconocida"
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Desconocida"

            var ipLocal = "No disponible"
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
            } catch (e: Exception) {}

            tvTelemetriaRed?.text = "📶 Wi-Fi: $ssid ($rssi dBm - $calDesc)\n🌐 IP Local: $ipLocal\n👉 TOCA AQUÍ PARA ANALIZADOR WI-FI"
        } catch (e: Exception) {
            tvTelemetriaRed?.text = "📶 Wi-Fi e IP: No disponibles"
        }

        // ESTADO DE LA SIM Y DATOS MÓVILES
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val operadorName = telephonyManager?.networkOperatorName?.ifEmpty { "Desconocido" } ?: "No disponible"
            val simState = when (telephonyManager?.simState) {
                TelephonyManager.SIM_STATE_READY -> "Insertada y lista"
                TelephonyManager.SIM_STATE_ABSENT -> "Ausente"
                else -> "Bloqueada o no lista"
            }
            tvTelemetriaMovil?.text = "📱 Operador Móvil: $operadorName\n💳 Estado SIM: $simState"
        } catch (e: Exception) {
            tvTelemetriaMovil?.text = "📱 Operador Móvil: Información no accesible"
        }

        // MEMORIA RAM, VOLÚMENES Y ESTADO DE ADB
        try {
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val ramLibreMb = memInfo.availMem / (1024 * 1024)
            val ramTotalMb = memInfo.totalMem / (1024 * 1024)

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val volRing = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val volMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            val adbEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            val estadoAdb = if (adbEnabled) "🟢 Activado" else "🔴 Desactivado"

            val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempInt = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val temperaturaC = tempInt / 10.0

            tvTelemetriaHardware?.text = "🧠 RAM Libre: $ramLibreMb MB / $ramTotalMb MB\n" +
                    "🔊 Vol. Tono: $volRing/$maxRing | Música: $volMusic/$maxMusic\n" +
                    "🌡️ Temperatura: $temperaturaC °C\n" +
                    "🔌 Depuración USB (ADB): $estadoAdb"
        } catch (e: Exception) {
            tvTelemetriaHardware?.text = "🧠 Parámetros de Hardware: No disponibles"
        }

        // --- ACCIÓN PARA ABRIR MINI-APP WI-FI ---
        tvTelemetriaRed?.setOnClickListener {
            solicitarPermisosYMostrarWifi()
        }

        // Guardar ubicación
        btnGuardar?.setOnClickListener {
            val nuevaUbicacion = etUbicacion?.text.toString().trim()
            if (nuevaUbicacion.isNotEmpty()) {
                prefs.edit().putString("ubicacion_dispositivo", nuevaUbicacion).apply()
                AppLog.registrar("⚙️ Ubicación cambiada manualmente a: $nuevaUbicacion")
                Toast.makeText(this, "✅ Ubicación guardada con éxito", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "⚠️ La ubicación no puede estar vacía", Toast.LENGTH_SHORT).show()
            }
        }

        // Forzar Sincro con Excel real + Test Ping
        btnTestSincro?.setOnClickListener {
            Toast.makeText(this, "🔄 Sincronizando datos y ejecutando test de red...", Toast.LENGTH_SHORT).show()
            AppLog.registrar("🔄 Forzada sincronización manual y test de red desde Panel IT")

            val pingClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
            val pingRequest = Request.Builder().url("https://www.google.com").build()
            val inicioMs = System.currentTimeMillis()

            pingClient.newCall(pingRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AppLog.registrar("🏓 Test Conectividad: ❌ Sin salida a internet (${e.message})")
                }
                override fun onResponse(call: Call, response: Response) {
                    val latencia = System.currentTimeMillis() - inicioMs
                    AppLog.registrar("🏓 Test Conectividad: ✅ OK (Latencia aproximada: ${latencia}ms)")
                    response.close()
                }
            })

            val client = OkHttpClient()
            val request = Request.Builder().url(URL_GOOGLE_SHEETS_CSV).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AppLog.registrar("❌ Error red sincronización IT: ${e.message}")
                    runOnUiThread { Toast.makeText(this@ItActivity, "❌ Error de red al sincronizar", Toast.LENGTH_SHORT).show() }
                }

                override fun onResponse(call: Call, response: Response) {
                    val csvData = response.body?.string()
                    if (!response.isSuccessful || csvData.isNullOrEmpty()) {
                        AppLog.registrar("❌ Error respuesta CSV vacía o incorrecta")
                        return
                    }

                    try {
                        val fechaActual = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                        prefs.edit().putString("csv_cache_data", csvData).putString("ultima_sincro", fechaActual).apply()

                        runOnUiThread {
                            cargarSeccionesEnDesplegable(etUbicacion, prefs)
                            Toast.makeText(this@ItActivity, "✨ ¡Sincronizado y red probada con éxito!", Toast.LENGTH_LONG).show()
                        }
                        AppLog.registrar("✨ Sincronización manual completada y guardada en caché")
                    } catch (e: Exception) {
                        AppLog.registrar("❌ Excepción guardando caché manual: ${e.message}")
                    }
                }
            })
        }

        // Ver Logs en pantalla
        btnVerLogsIt?.setOnClickListener {
            mostrarDialogoLogsEnPantalla()
        }

        // Forzar OTA
        btnForzarOTA?.setOnClickListener {
            Toast.makeText(this, "🚀 Comprobando versión en GitHub...", Toast.LENGTH_SHORT).show()
            AppLog.registrar("🚀 Comprobación manual de OTA iniciada desde Panel IT")

            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = Request.Builder().url("https://grupotgt.github.io/actualizaciones-launcher/version.json").build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AppLog.registrar("❌ OTA Error Red JSON: ${e.message}")
                    runOnUiThread { Toast.makeText(this@ItActivity, "❌ Error de red al buscar OTA", Toast.LENGTH_SHORT).show() }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val jsonStr = response.body?.string()
                        if (!response.isSuccessful || jsonStr.isNullOrEmpty()) {
                            AppLog.registrar("❌ OTA JSON Falló: HTTP ${response.code}")
                            return
                        }

                        val jsonObject = JSONObject(jsonStr)
                        val versionNube = jsonObject.getInt("versionCode")

                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        val versionActual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }

                        AppLog.registrar("ℹ️ OTA Check Panel IT -> Local: $versionActual | Nube: $versionNube")

                        runOnUiThread {
                            if (versionNube > versionActual) {
                                Toast.makeText(this@ItActivity, "✨ ¡Actualización encontrada en nube (v$versionNube)!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@ItActivity, "✅ Estás al día (Local: $versionActual, Nube: $versionNube)", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.registrar("❌ Excepción leyendo JSON OTA en IT: ${e.message}")
                    }
                }
            })
        }

        // Purga de APKs
        btnPurgarApks?.setOnClickListener {
            purgarApksResiduales()
        }

        // Ajustes de Android
        btnAbrirAjustesAndroid?.setOnClickListener {
            try {
                stopLockTask()
            } catch (e: Exception) {}
            startActivity(Intent(Settings.ACTION_SETTINGS))
            finish()
        }

        // Cerrar panel
        btnCerrarPanelIT?.setOnClickListener {
            finish()
        }
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
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val tvInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }

        val barraSenal = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40).apply {
                setMargins(0, 40, 0, 40)
            }
        }

        val tvEstado = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 14f
            gravity = Gravity.CENTER
            text = "Analizando espectro de red..."
        }

        dialogView.addView(tvInfo)
        dialogView.addView(barraSenal)
        dialogView.addView(tvEstado)

        val dialog = AlertDialog.Builder(this)
            .setTitle("📡 Diagnóstico Wi-Fi Kiosco")
            .setView(dialogView)
            .setPositiveButton("Cerrar", null)
            .create()

        dialog.show()

        val handler = Handler(Looper.getMainLooper())
        val updateTask = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return

                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val wifiInfo = wifiManager?.connectionInfo

                    val rssi = wifiInfo?.rssi ?: -100
                    val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Desconectado"
                    val linkSpeed = wifiInfo?.linkSpeed ?: 0

                    val quality = WifiManager.calculateSignalLevel(rssi, 100)

                    val estado = when {
                        rssi > -50 -> "Excelente (Ideal para OTA y Sincro)"
                        rssi in -50 downTo -65 -> "Buena (Conexión estable)"
                        rssi in -66 downTo -75 -> "Regular (Posibles cortes)"
                        else -> "Pobre (¡Revisar cobertura / antenas!)"
                    }

                    var ipLocal = "127.0.0.1"
                    try {
                        val ip = wifiInfo?.ipAddress ?: 0
                        ipLocal = String.format("%d.%d.%d.%d", (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff))
                    } catch (e: Exception) {}

                    tvInfo.text = "📶 SSID: $ssid\n" +
                            "🛜 Potencia (RSSI): $rssi dBm\n" +
                            "⚡ Velocidad Enlace: $linkSpeed Mbps\n" +
                            "🌐 IP Asignada: $ipLocal"

                    barraSenal.progress = quality
                    tvEstado.text = "Estado: $estado"

                    if (quality > 70) {
                        tvEstado.setTextColor(Color.parseColor("#00E676"))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            barraSenal.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))
                        }
                    } else if (quality > 40) {
                        tvEstado.setTextColor(Color.parseColor("#FFCA28"))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            barraSenal.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFCA28"))
                        }
                    } else {
                        tvEstado.setTextColor(Color.parseColor("#FF5252"))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            barraSenal.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252"))
                        }
                    }

                } catch (e: Exception) {
                    tvInfo.text = "Error al leer hardware Wi-Fi."
                }

                // Refrescar en tiempo real cada 1 segundo
                handler.postDelayed(this, 1000)
            }
        }

        handler.post(updateTask)
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
        val contenedorLogs = ScrollView(this).apply {
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#111111"))
        }
        val textoLogs = TextView(this).apply {
            text = AppLog.obtenerLogs()
            setTextColor(Color.GREEN)
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        contenedorLogs.addView(textoLogs)

        AlertDialog.Builder(this)
            .setTitle("📋 Registro de Errores y Actividad (Logs)")
            .setView(contenedorLogs)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("📋 Copiar") { _, _ ->
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Logs Kiosco", AppLog.obtenerLogs())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "✅ ¡Logs copiados!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "❌ Error al copiar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("📤 SMS") { _, _ ->
                enviarLogsPorSms()
            }
            .setItems(arrayOf("📧 Enviar Logs por Email a IT")) { _, _ ->
                enviarLogsPorEmail()
            }
            .show()
    }

    private fun enviarLogsPorEmail() {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val csvCache = prefs.getString("csv_cache_data", "") ?: ""
            val correosSet = mutableSetOf<String>()

            if (csvCache.isNotEmpty()) {
                val lineas = csvCache.replace("\r", "").split("\n")
                for (linea in lineas) {
                    if (linea.isBlank()) continue
                    val partes = if (linea.contains(";")) linea.split(";") else linea.split(",")
                    if (partes.size >= 8) {
                        val correo = partes[7].trim().replace("\"", "")
                        if (correo.isNotEmpty() && correo.contains("@")) {
                            correosSet.add(correo)
                        }
                    }
                }
            }

            val correoITUnico = prefs.getString("correo_it", "") ?: ""
            if (correoITUnico.isNotEmpty() && correoITUnico.contains("@")) {
                correosSet.add(correoITUnico)
            }

            val arrayCorreos = correosSet.toTypedArray()
            if (arrayCorreos.isEmpty()) {
                Toast.makeText(this, "❌ No hay ningún correo IT configurado en la Columna 8 del Google Sheets", Toast.LENGTH_LONG).show()
                return
            }

            val archivoLog = File(filesDir, "kiosco_log_persistente.txt")
            val uriLog: Uri = if (archivoLog.exists()) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", archivoLog)
            } else {
                Uri.EMPTY
            }

            val intentEmail = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayCorreos)
                putExtra(Intent.EXTRA_SUBJECT, "🚨 Reporte de Logs - Kiosco TGT (${prefs.getString("ubicacion_dispositivo", "Terminal")})")
                putExtra(Intent.EXTRA_TEXT, "Adjunto encontrarás el archivo de registro de actividad y errores del terminal Kiosco.")
                if (uriLog != Uri.EMPTY) {
                    putExtra(Intent.EXTRA_STREAM, uriLog)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            startActivity(Intent.createChooser(intentEmail, "Enviar Logs por Email a IT..."))
            AppLog.registrar("📧 Diálogo de envío de logs por email abierto para: ${arrayCorreos.joinToString(", ")}")

        } catch (e: Exception) {
            AppLog.error("Error al preparar email de logs: ${e.message}")
            Toast.makeText(this, "❌ Error al abrir cliente de correo: ${e.message}", Toast.LENGTH_LONG).show()
        }
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