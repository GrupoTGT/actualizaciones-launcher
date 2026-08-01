package com.grupotgt.launcherkioscotgt

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.app.Dialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.hardware.Camera
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =======================================================
// UTILIDAD PRO: REGISTRADOR DE LOGS GLOBAL (LOGCAT + MEMORIA)
// =======================================================
object AppLog {
    private val historial = mutableListOf<String>()
    private const val MAX_LOGS = 50

    fun registrar(mensaje: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val linea = "[$timeStamp] $mensaje"
        synchronized(historial) {
            if (historial.size >= MAX_LOGS) {
                historial.removeAt(0)
            }
            historial.add(linea)
        }
        android.util.Log.d("TGT_LOG", linea)
    }

    fun obtenerLogs(): String {
        synchronized(historial) {
            return if (historial.isEmpty()) "No hay registros de eventos todavía." else historial.joinToString("\n")
        }
    }
}

// =======================================================
// RECEPTOR ESTÁTICO CON LOGS
// =======================================================
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            AppLog.registrar("🎉 ¡Actualización aplicada con éxito!")
            Toast.makeText(context, "🎉 ¡Actualización aplicada con éxito!", Toast.LENGTH_LONG).show()
        } else {
            val msg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Desconocido"
            val errorCode = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -99) ?: -99
            AppLog.registrar("❌ FALLO INSTALACIÓN [Cod: $errorCode]: $msg")
            Toast.makeText(context, "❌ FALLO INSTALACIÓN [Cod: $errorCode]: $msg", Toast.LENGTH_LONG).show()
        }
    }
}

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    // ==========================================
    // ⚙️ CONFIGURACIÓN
    // ==========================================
    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"
    private val URL_OTA_JSON = "https://grupotgt.github.io/actualizaciones-launcher/version.json"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnableConsola: Runnable

    // TEMPORIZADOR AUTOMÁTICO CADA 5 MINUTOS (Actualiza teléfonos, avisos y hora de reinicio)
    private val intervaloSyncAgenda = 5 * 60 * 1000L
    private val runnableAutoSyncAgenda = object : Runnable {
        override fun run() {
            AppLog.registrar("🔄 Sincronización automática periódica (Agenda, Avisos y Reinicio)...")
            descargarAgendaNube(modoSilencioso = true)
            handler.postDelayed(this, intervaloSyncAgenda)
        }
    }

    // RELOJ EN TIEMPO REAL CADA SEGUNDO (Comprueba también si toca reinicio automático)
    private val runnableReloj = object : Runnable {
        override fun run() {
            actualizarRelojEnPantalla()
            verificarHoraReinicioAutomatico()
            handler.postDelayed(this, 1000)
        }
    }

    private var toquesSalida = 0
    private var toquesBateria = 0
    private var toquesWifi = 0

    private var contadorVolumenAbajo = 0
    private var contadorVolumenArriba = 0

    private var dialogLlamadaActiva: Dialog? = null
    private var dialogLlamadaEntrante: Dialog? = null

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
                    TelephonyManager.EXTRA_STATE_RINGING -> { mostrarPantallaLlamadaEntrante() }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        dialogLlamadaActiva?.dismiss()
                        dialogLlamadaEntrante?.dismiss()
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> { dialogLlamadaEntrante?.dismiss() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            AppLog.registrar("🚀 MainActivity iniciada (Versión 11 con Avisos y Reinicio Programado)")
            Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                val errorMsg = "CRASH: ${throwable.localizedMessage}"
                AppLog.registrar("💥 $errorMsg")
                enviarAlertaITCRasante("⚠️ CRASH CRÍTICO: ${throwable.localizedMessage}")
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }

            setContentView(R.layout.activity_main)

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
            })

            solicitarPermisos()
            solicitarAdministradorDispositivo()
            forzarVolumenMaximo()

            cargarIdentificacionYLogo()
            cargarAgendaDesdeCache()
            descargarAgendaNube(modoSilencioso = true)

            comprobarActualizacionOTA()

            configurarBotonSecreto()
            configurarBotonRatones()
            configurarBromaJefe()
            configurarChivatoSincro()

            handler.post(runnableEstadoDispositivo)
            handler.post(runnableReloj)
            handler.postDelayed(runnableAutoSyncAgenda, intervaloSyncAgenda)

            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(callStateReceiver, filter)

        } catch (e: Exception) {
            AppLog.registrar("❌ Error en onCreate: ${e.message}")
            e.printStackTrace()
        }
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
            val horaProgramada = prefs.getString("hora_reinicio_seccion", "") ?: ""
            if (horaProgramada.isNotEmpty()) {
                val horaActualMinuto = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (horaActualMinuto == horaProgramada) {
                    AppLog.registrar("🔄 ¡Hora de reinicio automático alcanzada ($horaProgramada)! Reiniciando dispositivo...")
                    enviarAlertaITCRasante("🔄 Reinicio automático nocturno ejecutado según programación.")

                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminName = ComponentName(this, MyAdminReceiver::class.java)
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        dpm.reboot(adminName)
                    } else {
                        // Fallback si no es device owner completo
                        val runtime = Runtime.getRuntime()
                        runtime.exec("reboot")
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.registrar("❌ Error en verificación de reinicio: ${e.message}")
        }
    }

    // ==========================================
    // ☁️ MOTOR DE AGENDA, AVISOS Y REINICIO (CSV)
    // ==========================================
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
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val csvCache = prefs.getString("csv_cache_data", "") ?: ""
            if (csvCache.isNotEmpty()) {
                AppLog.registrar("📦 Agenda y Avisos cargados desde Caché Local")
                procesarYConstruirCSV(csvCache)
                actualizarTextoUltimaSincro()
            }
        } catch (e: Exception) {
            AppLog.registrar("❌ Error cargando caché: ${e.message}")
        }
    }

    private fun descargarAgendaNube(modoSilencioso: Boolean) {
        val client = OkHttpClient()
        val request = Request.Builder().url(URL_GOOGLE_SHEETS_CSV).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.registrar("❌ Error red CSV: ${e.message}")
                if (!modoSilencioso) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "❌ Error de red al sincronizar", Toast.LENGTH_SHORT).show() }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val csvData = response.body?.string()
                if (!response.isSuccessful || csvData.isNullOrEmpty()) return
                try {
                    val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                    val fechaActual = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                    prefs.edit().putString("csv_cache_data", csvData).putString("ultima_sincro", fechaActual).apply()
                } catch (e: Exception) {}

                runOnUiThread {
                    procesarYConstruirCSV(csvData)
                    actualizarTextoUltimaSincro()
                    if (!modoSilencioso) {
                        Toast.makeText(this@MainActivity, "✨ ¡Sincronizado con éxito!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun procesarYConstruirCSV(csvData: String) {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val grupoFiltro = prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4") ?: "Seccion Finales linea 4"

            val lineas = csvData.replace("\r", "").split("\n")
            val nuevosBotones = mutableListOf<Pair<String, String>>()
            var avisoEncontrado = ""
            var horaReinicioEncontrada = ""

            AppLog.registrar("📄 Total líneas leídas del CSV: ${lineas.size} | Buscando sección: '$grupoFiltro'")

            for (linea in lineas) {
                if (linea.isBlank()) continue

                val partes = if (linea.contains(";")) {
                    linea.split(";")
                } else {
                    linea.split(",")
                }

                if (partes.size >= 3) {
                    val grupoExcel = partes[0].trim().replace("\"", "")
                    val nombreExcel = partes[1].trim().replace("\"", "")
                    val telefonoExcel = partes[2].trim().replace("\"", "")

                    // Columna 4 y 5: Configuración global IT (TelefonoIT y PinIT)
                    if (partes.size >= 5) {
                        val telefonoITGlobal = partes[3].trim().replace("\"", "")
                        val pinITGlobal = partes[4].trim().replace("\"", "")

                        if (telefonoITGlobal.isNotEmpty() || pinITGlobal.isNotEmpty()) {
                            prefs.edit().apply {
                                if (telefonoITGlobal.isNotEmpty()) putString("telefono_it", telefonoITGlobal)
                                if (pinITGlobal.isNotEmpty()) putString("pin_it", pinITGlobal)
                            }.apply()
                        }
                    }

                    // Columna 6: MensajeAviso por sección
                    if (partes.size >= 6) {
                        val textoAviso = partes[5].trim().replace("\"", "")
                        if (textoAviso.isNotEmpty() && grupoExcel.equals(grupoFiltro.trim(), ignoreCase = true)) {
                            avisoEncontrado = textoAviso
                        }
                    }

                    // Columna 7: HoraReinico (Opcional, formato HH:mm ej: 03:30)
                    if (partes.size >= 7) {
                        val horaRein = partes[6].trim().replace("\"", "")
                        if (horaRein.isNotEmpty() && grupoExcel.equals(grupoFiltro.trim(), ignoreCase = true)) {
                            horaReinicioEncontrada = horaRein
                        }
                    }

                    // Filtrado de botones específicos para esta sección
                    if (grupoExcel.equals(grupoFiltro.trim(), ignoreCase = true)) {
                        nuevosBotones.add(Pair(nombreExcel, telefonoExcel))
                    }
                }
            }

            // Guardar hora de reinicio en preferencias locales si se especificó en el Excel
            if (horaReinicioEncontrada.isNotEmpty()) {
                prefs.edit().putString("hora_reinicio_seccion", horaReinicioEncontrada).apply()
            }

            AppLog.registrar("🔍 Grupo filtrado: '$grupoFiltro' | Botones: ${nuevosBotones.size} | Aviso: '$avisoEncontrado' | Reinicio: '$horaReinicioEncontrada'")
            runOnUiThread {
                construirPanelDesdeNube(nuevosBotones)
                actualizarBannerUrgencia(avisoEncontrado)
            }
        } catch (e: Exception) {
            AppLog.registrar("❌ Error procesando CSV: ${e.message}")
            e.printStackTrace()
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

    private fun construirPanelDesdeNube(listaContactos: List<Pair<String, String>>) {
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
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C8102E"))
                    elevation = 8f

                    val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(8, 8, 8, 8)
                    }
                    layoutParams = p

                    setOnClickListener {
                        if (numero.isNotEmpty()) {
                            mostrarPantallaLlamando(nombre, numero)
                        } else {
                            Toast.makeText(this@MainActivity, "Número no válido", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                listaBotonesUI.add(btn)
            }

            var filaActual: LinearLayout? = null
            for (index in listaBotonesUI.indices) {
                if (index % 2 == 0) {
                    filaActual = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 180)
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
            val horaRein = prefs.getString("hora_reinicio_seccion", "No programado")
            Toast.makeText(this, "🔄 Sincro: $ultimaSincro\n🔄 Reinicio auto: $horaRein", Toast.LENGTH_LONG).show()
            true
        }
    }

    // ==========================================
    // CONTROL REAL DE TELEFONÍA
    // ==========================================
    @SuppressLint("MissingPermission")
    private fun colgarLlamadaReal() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall()
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    @SuppressLint("MissingPermission")
    private fun contestarLlamadaReal() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall()
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun mostrarPantallaLlamadaEntrante() {
        if (dialogLlamadaEntrante != null && dialogLlamadaEntrante!!.isShowing) return
        dialogLlamadaEntrante = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#222222")); setPadding(40, 40, 40, 40)
        }
        val textoLlamando = TextView(this).apply {
            text = "📞 Llamada Entrante..."
            setTextColor(Color.WHITE); textSize = 35f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 100) }
        }
        val btnContestar = Button(this).apply {
            text = "🟢 CONTESTAR"
            setBackgroundColor(Color.parseColor("#00C853")); setTextColor(Color.WHITE); textSize = 24f; setTypeface(null, Typeface.BOLD); setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 40) }
        }
        val btnRechazar = Button(this).apply {
            text = "🔴 RECHAZAR"
            setBackgroundColor(Color.parseColor("#C8102E")); setTextColor(Color.WHITE); textSize = 24f; setTypeface(null, Typeface.BOLD); setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        btnContestar.setOnClickListener {
            contestarLlamadaReal()
            dialogLlamadaEntrante?.dismiss()
            mostrarPantallaLlamando("Llamada en curso", "")
        }
        btnRechazar.setOnClickListener {
            colgarLlamadaReal()
            dialogLlamadaEntrante?.dismiss()
        }
        layout.addView(textoLlamando); layout.addView(btnContestar); layout.addView(btnRechazar)
        dialogLlamadaEntrante?.setContentView(layout)
        dialogLlamadaEntrante?.show()
    }

    private fun mostrarPantallaLlamando(nombre: String, numero: String) {
        if (dialogLlamadaActiva != null && dialogLlamadaActiva!!.isShowing) return
        dialogLlamadaActiva = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#222222")); setPadding(40, 40, 40, 40)
        }
        val textoLlamando = TextView(this).apply {
            text = if (numero.isNotEmpty()) "Llamando a\n$nombre..." else "Llamada Activa\n$nombre"
            setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 100) }
        }
        val btnColgar = Button(this).apply {
            text = "☎ COLGAR"
            setBackgroundColor(Color.parseColor("#C8102E")); setTextColor(Color.WHITE); textSize = 24f; setTypeface(null, Typeface.BOLD); setPadding(40, 40, 40, 40)
        }
        layout.addView(textoLlamando); layout.addView(btnColgar)
        dialogLlamadaActiva?.setContentView(layout)
        if (numero.isNotEmpty()) {
            try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$numero"))) } catch (e: Exception) {}
        }
        btnColgar.setOnClickListener { colgarLlamadaReal(); dialogLlamadaActiva?.dismiss() }
        dialogLlamadaActiva?.show()
    }

    private fun actualizarIndicadoresReales() {
        try {
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (scale > 0) (level * 100) / scale else 0
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val iconoBateria = if (isCharging) "⚡" else "🔋"
            findViewById<TextView>(R.id.tvBateria)?.text = "$iconoBateria $pct%"
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            val tvWifi = findViewById<TextView>(R.id.tvWifi)

            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val tieneInternetWifi = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                    if (tieneInternetWifi || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                        tvWifi?.text = "🟢 Wi-Fi Operativo"
                        tvWifi?.setTextColor(Color.parseColor("#00C853"))
                    } else {
                        tvWifi?.text = "🟠 Wi-Fi (Sin validación)"
                        tvWifi?.setTextColor(Color.parseColor("#E65100"))
                    }
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    tvWifi?.text = "📶 Red Móvil OK"
                    tvWifi?.setTextColor(Color.parseColor("#0288D1"))
                } else {
                    tvWifi?.text = "❌ Sin Conexión"
                    tvWifi?.setTextColor(Color.parseColor("#C8102E"))
                }
            } else {
                tvWifi?.text = "❌ Sin Conexión"
                tvWifi?.setTextColor(Color.parseColor("#C8102E"))
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnableEstadoDispositivo)
        handler.removeCallbacks(runnableAutoSyncAgenda)
        handler.removeCallbacks(runnableReloj)
        try { unregisterReceiver(callStateReceiver) } catch (e: Exception) {}
    }

    private fun configurarModoKioscoEstricto() {
        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, MyAdminReceiver::class.java)

            if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                devicePolicyManager.setLockTaskPackages(componentName, arrayOf(packageName))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val permisosCriticos = arrayOf(
                        Manifest.permission.SEND_SMS, Manifest.permission.CAMERA,
                        Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    for (permiso in permisosCriticos) {
                        devicePolicyManager.setPermissionGrantState(componentName, packageName, permiso, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun esSeguroBloquearPantalla(): Boolean {
        val permisosRequeridos = mutableListOf(
            Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS, Manifest.permission.CAMERA,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE
        )
        val faltanPermisos = permisosRequeridos.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, MyAdminReceiver::class.java)
        val faltaAdmin = !devicePolicyManager.isAdminActive(componentName)
        return !faltanPermisos && !faltaAdmin
    }

    override fun onResume() {
        super.onResume()
        forzarVolumenMaximo()
        configurarModoKioscoEstricto()
        if (esSeguroBloquearPantalla()) {
            try { startLockTask() } catch (e: Exception) {}
        } else {
            try { stopLockTask() } catch (e: Exception) {}
        }
        cargarIdentificacionYLogo()
        cargarAgendaDesdeCache()
        descargarAgendaNube(modoSilencioso = true)
        comprobarActualizacionOTA()
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
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) contadorVolumenAbajo = 0
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) contadorVolumenArriba = 0
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun forzarVolumenMaximo() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
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
                Manifest.permission.SEND_SMS, Manifest.permission.CAMERA,
                Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE
            )
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                permisosRequeridos.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
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

    private fun configurarBotonRatones() {
        findViewById<TextView>(R.id.tvBateria)?.setOnClickListener {
            toquesBateria++
            if (toquesBateria >= 4) { toquesBateria = 0; iniciarJuegoRatones() }
        }
    }

    private fun configurarBromaJefe() {
        findViewById<TextView>(R.id.tvWifi)?.setOnClickListener {
            toquesWifi++
            if (toquesWifi >= 5) { toquesWifi = 0; iniciarLlamadaJefe() }
        }
    }

    private fun iniciarLlamadaJefe() {
        AppLog.registrar("⚠️ Activada broma llamada del Jefe")
        enviarAlertaIT("⚠️ ALERTA: Activada la broma de la llamada del JEFE.")
        val layoutBase = findViewById<ImageView>(R.id.logoEmpresa)?.parent as? LinearLayout ?: return
        layoutBase.removeAllViews(); layoutBase.setBackgroundColor(Color.BLACK)

        val tvIncoming = TextView(this).apply {
            text = "📞 Llamada Entrante...\n\nEL JEFE (DIRECCIÓN)"; setTextColor(Color.WHITE); textSize = 35f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 100, 0, 100) }
        }
        layoutBase.addView(tvIncoming)
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000), -1)

        val btnContestar = Button(this).apply {
            text = "CONTESTAR"; setBackgroundColor(Color.parseColor("#00C853")); setTextColor(Color.WHITE); textSize = 24f; setPadding(40, 40, 40, 40)
        }
        layoutBase.addView(btnContestar)

        btnContestar.setOnClickListener {
            vibrator.cancel(); layoutBase.removeAllViews(); layoutBase.setBackgroundColor(Color.parseColor("#C8102E"))
            val tvYell = TextView(this@MainActivity).apply {
                text = "¡PONTE A TRABAJAR\nY DEJA DE JUGAR\nCON EL MÓVIL!\n\n🧀🧀🧀"; setTextColor(Color.WHITE); textSize = 40f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(-1, -1)
            }
            layoutBase.addView(tvYell)
            handler.postDelayed({ recreate() }, 4000)
        }
    }

    private fun enviarAlertaIT(mensajeBase: String) {
        Thread {
            try {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val numeroIT = prefs.getString("telefono_it", "")
                val ubicacion = prefs.getString("ubicacion_dispositivo", "Ubicación Desconocida")

                if (numeroIT.isNullOrEmpty()) return@Thread
                val mensajeFinal = "[$ubicacion] $mensajeBase"
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                } else {
                    @Suppress("DEPRECATION") SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(mensajeFinal)
                smsManager.sendMultipartTextMessage(numeroIT, null, parts, null, null)
                AppLog.registrar("📤 Alerta enviada por SMS a IT")
            } catch (e: Exception) {
                AppLog.registrar("❌ Error enviando SMS: ${e.message}")
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
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            smsManager.sendTextMessage(numeroIT, null, mensajeFinal, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun mostrarDialogoPIN() {
        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val pinCorrecto = prefs.getString("pin_it", "1234")
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; gravity = Gravity.CENTER }

        val builder = AlertDialog.Builder(this)
            .setTitle("Panel de Acceso IT")
            .setMessage("Introduce Código o consulta Logs:")
            .setView(input)
            .setPositiveButton("Entrar") { _, _ ->
                val codigoMetido = input.text.toString()
                if (codigoMetido == "*###9999#") {
                    try { stopLockTask() } catch (e: Exception) {}
                    startActivity(Intent(Settings.ACTION_SETTINGS)); finish()
                } else if (codigoMetido == pinCorrecto) {
                    try { stopLockTask() } catch (e: Exception) {}
                    startActivity(Intent(this, ItActivity::class.java))
                } else { iniciarHackeoConsola() }
            }
            .setNeutralButton("📋 Ver Logs del Sistema") { _, _ ->
                mostrarDialogoLogsEnPantalla()
            }
            .setNegativeButton("Cancelar", null)
        builder.show()
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
            .setNegativeButton("📤 Enviar Logs por SMS a IT") { _, _ ->
                enviarLogsPorSms()
            }
            .show()
    }

    private fun enviarLogsPorSms() {
        Thread {
            try {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val numeroIT = prefs.getString("telefono_it", "")
                if (numeroIT.isNullOrEmpty()) {
                    runOnUiThread { Toast.makeText(this, "❌ No hay teléfono IT configurado", Toast.LENGTH_SHORT).show() }
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
                    Toast.makeText(this, "✅ Logs enviados por SMS al número IT", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "❌ Error enviando logs por SMS: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun iniciarHackeoConsola() {
        AppLog.registrar("⚠️ Intento fallido de PIN IT - Activada consola simulada")
        enviarAlertaIT("⚠️ ALERTA: Intento de violación de seguridad en menú IT. Captura guardada.")
        val layoutBase = findViewById<ImageView>(R.id.logoEmpresa)?.parent as? LinearLayout ?: return
        layoutBase.setBackgroundColor(Color.BLACK); layoutBase.removeAllViews()
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(2000)

        val textoConsola = TextView(this).apply { setTextColor(Color.GREEN); textSize = 12f; typeface = Typeface.MONOSPACE; layoutParams = LinearLayout.LayoutParams(-1, -1) }
        layoutBase.addView(textoConsola)
        val logs = listOf("[ OK ] Booting...", "Unauthorized access!", "Bypassing firewall...", "[ERROR] System breach!", "Formatting storage...")
        var logAcumulado = ""
        runnableConsola = object : Runnable {
            override fun run() { logAcumulado += logs.random() + "\n"; textoConsola.text = logAcumulado; handler.postDelayed(this, 300) }
        }
        handler.post(runnableConsola)

        prepararCamaraOcultaYDisparar(layoutBase)
        val mostrarWarning = { msg: String ->
            val w = TextView(this).apply {
                text = msg; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#C8102E"))
                textSize = 18f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(40, 50, 40, 50)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(40, 40, 40, 0) }
            }
            layoutBase.addView(w, 0)
        }
        handler.postDelayed({ mostrarWarning("⚠️ INICIANDO CÁMARA FRONTAL...") }, 1500)
        handler.postDelayed({ mostrarWarning("⚠️ COMUNICANDO A DIRECCIÓN...") }, 3000)

        toquesSalida = 0
        layoutBase.setOnClickListener { toquesSalida++; if (toquesSalida >= 5) { handler.removeCallbacksAndMessages(null); recreate() } }
    }

    private fun prepararCamaraOcultaYDisparar(layoutBase: LinearLayout) {
        var mCamera: Camera? = null
        try {
            val info = Camera.CameraInfo()
            var frontId = -1
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) { frontId = i; break }
            }
            if (frontId != -1) {
                mCamera = Camera.open(frontId)
                val params = mCamera.parameters
                val sizes = params.supportedPictureSizes
                if (sizes.isNotEmpty()) { params.setPictureSize(sizes[0].width, sizes[0].height); mCamera.parameters = params }
            }
        } catch (e: Exception) {}

        if (mCamera != null) {
            val surfaceView = SurfaceView(this)
            layoutBase.addView(surfaceView, 0, LinearLayout.LayoutParams(1, 1))
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    try {
                        mCamera.setPreviewDisplay(holder); mCamera.startPreview()
                        handler.postDelayed({
                            try {
                                mCamera.takePicture(null, null) { data, cam ->
                                    cam.release()
                                    val bmpBruto = BitmapFactory.decodeByteArray(data, 0, data.size)
                                    val matrix = Matrix().apply { postRotate(270f) }
                                    val bmpFinal = Bitmap.createBitmap(bmpBruto, 0, 0, bmpBruto.width, bmpBruto.height, matrix, true)
                                    guardarBitmapEnGaleria(bmpFinal)
                                    handler.postDelayed({
                                        handler.removeCallbacks(runnableConsola)
                                        mostrarPantallazoFinal(layoutBase, bmpFinal)
                                    }, 4000)
                                }
                            } catch (e: Exception) {
                                mCamera.release()
                                handler.postDelayed({ handler.removeCallbacks(runnableConsola); mostrarPantallazoFinal(layoutBase, null) }, 4000)
                            }
                        }, 300)
                    } catch (e: Exception) {
                        mCamera.release()
                        handler.postDelayed({ handler.removeCallbacks(runnableConsola); mostrarPantallazoFinal(layoutBase, null) }, 4000)
                    }
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        } else {
            handler.postDelayed({ handler.removeCallbacks(runnableConsola); mostrarPantallazoFinal(layoutBase, null) }, 4500)
        }
    }

    private fun guardarBitmapEnGaleria(bitmap: Bitmap) {
        try {
            val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "Intruso_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { contentResolver.openOutputStream(it)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) } }
        } catch (e: Exception) {}
    }

    private fun mostrarPantallazoFinal(layoutBase: LinearLayout, fotoCapturada: Bitmap?) {
        layoutBase.removeAllViews(); layoutBase.setOnClickListener(null)
        layoutBase.setBackgroundColor(Color.WHITE)
        handler.postDelayed({
            layoutBase.setBackgroundColor(Color.BLACK)
            if (fotoCapturada != null) layoutBase.background = BitmapDrawable(resources, fotoCapturada)
            val txt = TextView(this).apply {
                text = "📸 CAPTURA GUARDADA\n\nTu identidad ha sido registrada.\n\n¡Isso no se hace!"; setTextColor(Color.RED); textSize = 24f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#CC000000")); layoutParams = LinearLayout.LayoutParams(-1, -1)
            }
            layoutBase.addView(txt)
            handler.postDelayed({ recreate() }, 6000)
        }, 150)
    }

    private fun iniciarJuegoRatones() {
        AppLog.registrar("🐭 Iniciado juego de ratones")
        val layoutBase = findViewById<ImageView>(R.id.logoEmpresa)?.parent as? LinearLayout ?: return
        layoutBase.removeAllViews(); layoutBase.setBackgroundColor(Color.parseColor("#F5DEB3"))
        val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -1) }
        layoutBase.addView(frame)
        var vivos = 15; val random = java.util.Random()
        for (i in 0 until 15) {
            val r = TextView(this).apply { text = "🐁"; textSize = 50f; x = random.nextInt(400).toFloat(); y = random.nextInt(800).toFloat() }
            r.setOnClickListener { frame.removeView(r); vivos--; if (vivos <= 0) recreate() }
            frame.addView(r)
        }
    }

    // =======================================================
    // MOTOR OTA CON LOGS DETALLADOS
    // =======================================================
    private fun comprobarActualizacionOTA() {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder().url(URL_OTA_JSON).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.registrar("❌ OTA Error Red JSON: ${e.message}")
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

                    AppLog.registrar("ℹ️ OTA Check -> Local: $versionActual | Nube: $versionNube")

                    if (versionNube > versionActual) {
                        AppLog.registrar("🔄 Versión nueva detectada en nube. Descargando...")
                        descargarYInstalarAPK(apkUrl)
                    } else {
                        AppLog.registrar("✅ Aplicación actualizada (versión más reciente).")
                    }
                } catch (e: Exception) {
                    AppLog.registrar("❌ Excepción leyendo JSON OTA: ${e.message}")
                }
            }
        })
    }

    private fun descargarYInstalarAPK(url: String) {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.registrar("❌ Error descargando APK: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        AppLog.registrar("❌ Error HTTP en APK: Código ${response.code}")
                        return
                    }

                    val apkData = response.body?.bytes()
                    if (apkData == null || apkData.isEmpty()) {
                        AppLog.registrar("❌ Error: El APK descargado está vacío (0 bytes)")
                        return
                    }

                    val file = File(getExternalFilesDir(null), "update.apk")
                    val fos = FileOutputStream(file)
                    fos.write(apkData)
                    fos.flush()
                    fos.close()

                    AppLog.registrar("✅ APK descargado (${file.length()} bytes). Iniciando instalación...")
                    instalarApkSilenciosa(file)
                } catch (e: Exception) {
                    AppLog.registrar("❌ Excepción guardando APK: ${e.message}")
                }
            }
        })
    }

    private fun instalarApkSilenciosa(apkFile: File) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val out = session.openWrite("LauncherUpdate", 0, apkFile.length())
            val fis = FileInputStream(apkFile)
            val buffer = ByteArray(65536)
            var length: Int
            while (fis.read(buffer).also { length = it } != -1) {
                out.write(buffer, 0, length)
            }
            session.fsync(out)
            out.close()
            fis.close()

            val intent = Intent("com.grupotgt.launcherkioscotgt.INSTALL_COMPLETE")
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)

            AppLog.registrar("🚀 Sesión de PackageInstaller enviada con éxito.")
        } catch (e: Exception) {
            AppLog.registrar("❌ Fallo crítico en PackageInstaller: ${e.message}")
        }
    }
}