package com.grupotgt.launcherkioscotgt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SmsManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
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
        val btnPurgarApks = findViewById<Button>(R.id.btnPurgarApks) // 🧹 Botón de Purga APKs
        val btnAbrirAjustesAndroid = findViewById<Button>(R.id.btnAbrirAjustesAndroid)
        val btnCerrarPanelIT = findViewById<Button>(R.id.btnCerrarPanelIT)
        val tvEstadoKiosco = findViewById<TextView>(R.id.tvEstadoKiosco)
        val tvVersionApp = findViewById<TextView>(R.id.tvVersionApp)

        val tvTelemetriaUptime = findViewById<TextView>(R.id.tvTelemetriaUptime)
        val tvTelemetriaAlmacenamiento = findViewById<TextView>(R.id.tvTelemetriaAlmacenamiento)

        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val ubicacionActual = prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4")
        etUbicacion?.setText(ubicacionActual)

        cargarSeccionesEnDesplegable(etUbicacion, prefs)

        // CARGAR VERSIÓN REAL DE LA APLICACIÓN
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "1.0"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            tvVersionApp?.text = "📱 Versión instalada: v$versionName (Code: $versionCode)"
        } catch (e: Exception) {
            tvVersionApp?.text = "📱 Versión instalada: Desconocida"
        }

        tvEstadoKiosco?.text = "📦 Paquete: $packageName\n🔒 Estado: Kiosco Blindado Activo"

        // CALCULAR UPTIME (TIEMPO ENCENDIDO)
        val uptimeMillis = SystemClock.elapsedRealtime()
        val dias = TimeUnit.MILLISECONDS.toDays(uptimeMillis)
        val horas = TimeUnit.MILLISECONDS.toHours(uptimeMillis) % 24
        val minutos = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60
        tvTelemetriaUptime?.text = "⏱️ Tiempo activo: $dias días, $horas hrs, $minutos min"

        // CALCULAR TELEMETRÍA AVANZADA: ALMACENAMIENTO, WI-FI, BATERÍA Y TEMPERATURA
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val bytesDisponibles = stat.availableBlocksLong * stat.blockSizeLong
            val gbDisponibles = bytesDisponibles / (1024 * 1024 * 1024)

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val rssi = wifiInfo?.rssi ?: -99
            val nivelWifi = WifiManager.calculateSignalLevel(rssi, 5)
            val calidades = listOf("Muy baja", "Baja", "Buena", "Excelente", "Máxima")
            val calDesc = if (nivelWifi in 0..4) calidades[nivelWifi] else "Desconocida"
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Desconocida"

            val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryPct = batteryStatus?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) (level * 100) / scale else -1
            } ?: -1

            val tempInt = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val temperaturaC = tempInt / 10.0
            val statusCarga = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val estaCargando = statusCarga == BatteryManager.BATTERY_STATUS_CHARGING || statusCarga == BatteryManager.BATTERY_STATUS_FULL
            val iconoCarga = if (estaCargando) "⚡ (Cargando)" else "🔌 (Descargando)"

            tvTelemetriaAlmacenamiento?.text = "💾 Alm. Libre: $gbDisponibles GB\n" +
                    "📶 Wi-Fi: $ssid ($rssi dBm - $calDesc)\n" +
                    "🔋 Batería: $batteryPct% $iconoCarga\n" +
                    "🌡️ Temp. Hardware: $temperaturaC °C"

        } catch (e: Exception) {
            tvTelemetriaAlmacenamiento?.text = "💾 Almacenamiento y Sensores: No disponibles"
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

        // Forzar Sincro con Excel real + 🏓 TEST PING DIAGNÓSTICO
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

        // Forzar OTA conectado al motor real de red
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
                        val apkUrl = jsonObject.getString("apkUrl")

                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        val versionActual = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
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

        // 🧹 BOTÓN DE PURGA DE APKs RESIDUALES
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

    // 🧹 LÓGICA DE PURGA DE ARCHIVOS RESIDUALES
    private fun purgarApksResiduales() {
        try {
            var bytesLiberados: Long = 0
            var archivosBorrados = 0

            // 1. Revisar directorio de archivos externos de la app (Donde se descarga update.apk)
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

            // 2. Revisar directorio interno de caché de la app
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
            .setNeutralButton("📋 Copiar Logs") { _, _ ->
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Logs Kiosco", AppLog.obtenerLogs())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "✅ ¡Logs copiados al portapapeles!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "❌ Error al copiar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("📤 Enviar por SMS") { _, _ ->
                enviarLogsPorSms()
            }
            .show()
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

                val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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