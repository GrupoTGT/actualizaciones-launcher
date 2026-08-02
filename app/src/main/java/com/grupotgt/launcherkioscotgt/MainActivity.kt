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
import android.graphics.drawable.GradientDrawable
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
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
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

class BootAndReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val accion = intent?.action
        if (accion == Intent.ACTION_MY_PACKAGE_REPLACED || accion == Intent.ACTION_BOOT_COMPLETED) {
            context?.let {
                AppLog.inicializar(it)
                AppLog.info("Evento detectado ($accion). Reiniciando Launcher automáticamente...")
                val i = Intent(it, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                it.startActivity(i)
            }
        }
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

@Suppress("DEPRECATION")
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null) {
            AppLog.inicializar(context)
        }
        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Sin mensaje adicional"

        if (context != null) {
            val apkFile = File(context.getExternalFilesDir(null), "update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
                AppLog.ota("Limpieza: Archivo APK de actualización eliminado.")
            }
        }

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.success("¡Actualización OTA aplicada con éxito!")
                Toast.makeText(context, "🎉 ¡Actualización aplicada con éxito!", Toast.LENGTH_LONG).show()
            }
            else -> {
                AppLog.error("FALLO INSTALACIÓN OTA [Cod: $status]: $msg")
                Toast.makeText(context, "❌ Error OTA [Cod: $status]: $msg", Toast.LENGTH_LONG).show()

                if (context != null) {
                    try {
                        val prefs = context.getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                        val ubicacion = prefs.getString("ubicacion_dispositivo", "Desconocida")
                        val numeroIT = prefs.getString("telefono_it", "") ?: ""

                        if (numeroIT.isNotEmpty()) {
                            val mensajeError = "🚨 ALERTA KIOSCO [$ubicacion]: Fallo crítico al instalar la OTA. Android ha restaurado la versión anterior. Motivo: $msg"

                            var smsManager: SmsManager? = null
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                smsManager = context.getSystemService(SmsManager::class.java)
                            } else {
                                smsManager = SmsManager.getDefault()
                            }

                            if (smsManager != null) {
                                val parts = smsManager.divideMessage(mensajeError)
                                smsManager.sendMultipartTextMessage(numeroIT, null, parts, null, null)
                            }
                            AppLog.info("Alerta de fallo OTA enviada por SMS a IT de forma automática.")
                        }
                    } catch (e: Exception) {
                        AppLog.error("Error enviando alerta de fallo OTA: ${e.message}")
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"
    private val URL_OTA_JSON = "https://grupotgt.github.io/actualizaciones-launcher/version.json"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnableConsola: Runnable

    private val intervaloSyncAgenda = 5 * 60 * 1000L
    private val intervaloCheckOTA = 2 * 60 * 1000L // ⏱️ Reducido a 2 minutos para agilizar comprobaciones

    private var wakeLockLlamada: android.os.PowerManager.WakeLock? = null

    private var contactosGuardados = listOf<Pair<String, String>>()
    private var whitelistGlobal = listOf<String>()

    // Variables para el Salvapantallas (Screensaver) Dinámico
    private var handlerInactividad = Handler(Looper.getMainLooper())
    private var minutosInactividadConfig = 10 // Valor por defecto de seguridad
    private var dialogScreensaver: Dialog? = null

    private val runnableScreensaver = Runnable {
        mostrarScreensaverTGT()
    }

    private val runnableAutoSyncAgenda = object : Runnable {
        override fun run() {
            AppLog.info("Sincronización automática periódica...")
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

    private var toquesSalida = 0
    private var toquesBateria = 0
    private var toquesWifi = 0
    private var contadorVolumenAbajo = 0
    private var contadorVolumenArriba = 0

    private var dialogLlamadaActiva: Dialog? = null
    private var dialogLlamadaEntrante: Dialog? = null

    private var ultimoReinicioEjecutado = ""

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
                            } else {
                                estaPermitido = false
                            }

                            if (!estaPermitido) {
                                AppLog.warning("🛡️ FIREWALL ACTIVO: Llamada bloqueada del número '${if(numeroEntrante.isEmpty()) "Oculto" else numeroEntrante}'.")
                                colgarLlamadaReal()
                                return
                            }
                        }

                        if (numLimpio.isNotEmpty()) {
                            val contactoMatch = contactosGuardados.find {
                                val numAgenda = it.second.replace(" ", "").replace("+34", "").replace("-", "")
                                numAgenda.isNotEmpty() && (numAgenda.contains(numLimpio) || numLimpio.contains(numAgenda))
                            }
                            if (contactoMatch != null) {
                                nombreCaller = contactoMatch.first
                            }
                        }

                        mostrarPantallaLlamadaEntrante(nombreCaller, numeroEntrante)
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
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
            AppLog.info("MainActivity iniciada (Versión 31 - OTA Inmediata y Visible)")
            Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                val errorMsg = "CRASH: ${throwable.localizedMessage}"
                AppLog.error(errorMsg)
                enviarAlertaITCRasante("⚠️ CRASH CRÍTICO: ${throwable.localizedMessage}")
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }

            // Recuperar configuración de inactividad guardada en caché local
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            minutosInactividadConfig = prefs.getInt("minutos_inactividad_custom", 10)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
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
            handler.postDelayed(runnableAutoCheckOTA, intervaloCheckOTA)

            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(callStateReceiver, filter)

            // Iniciar temporizador de inactividad al arrancar
            reiniciarTemporizadorInactividad()

        } catch (e: Exception) {
            AppLog.error("Error en onCreate: ${e.message}")
            e.printStackTrace()
        }
    }

    // --- GESTIÓN GLOBAL DE INACTIVIDAD (TOUCH LISTENER) ---
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        reiniciarTemporizadorInactividad()
        if (dialogScreensaver != null && dialogScreensaver!!.isShowing) {
            dialogScreensaver?.dismiss()
            return true // Intercepta el toque que despierta la pantalla
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

        // Si hay una llamada en curso o entrante, posponer salvapantallas
        if (dialogLlamadaEntrante?.isShowing == true || dialogLlamadaActiva?.isShowing == true) {
            reiniciarTemporizadorInactividad()
            return
        }

        runOnUiThread {
            try {
                dialogScreensaver = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    window?.let { win ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        } else {
                            @Suppress("DEPRECATION")
                            win.setType(WindowManager.LayoutParams.TYPE_PHONE)
                        }

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
                            intArrayOf(Color.parseColor("#020617"), Color.parseColor("#0F172A"))
                        )
                        setPadding(40, 40, 40, 40)
                    }

                    val tvHoraGigante = TextView(context).apply {
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        setTextColor(Color.WHITE)
                        textSize = 85f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                    }

                    val tvMarcaTGT = TextView(context).apply {
                        text = "GRUPO TGT • TERMINAL DE OPERACIONES"
                        setTextColor(Color.parseColor("#00E5FF"))
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        letterSpacing = 0.2f
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 20, 0, 60) }
                    }

                    val tvAvisoToque = TextView(context).apply {
                        text = "Toca cualquier parte de la pantalla para volver"
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 40, 0, 0) }
                    }

                    rootLayout.addView(tvHoraGigante)
                    rootLayout.addView(tvMarcaTGT)
                    rootLayout.addView(tvAvisoToque)

                    rootLayout.setOnClickListener {
                        dismiss()
                        reiniciarTemporizadorInactividad()
                    }

                    setContentView(rootLayout)
                    setCancelable(false)
                    show()
                }
            } catch (e: Exception) {
                AppLog.error("Error al mostrar Screensaver: ${e.message}")
            }
        }
    }

    private fun liberarPantalla() {
        try {
            wakeLockLlamada?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
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
                    enviarAlertaITCRasante("🔄 Reinicio automático programado a las $horaProgramada en curso.")

                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminName = ComponentName(this, MyAdminReceiver::class.java)

                    if (dpm.isDeviceOwnerApp(packageName)) {
                        AppLog.success("Privilegios Device Owner correctos. Reiniciando terminal...")
                        dpm.reboot(adminName)
                    } else {
                        AppLog.error("ERROR: La aplicación NO tiene permisos de Device Owner para reiniciar. Intentando fallback...")
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
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val csvCache = prefs.getString("csv_cache_data", "") ?: ""
            if (csvCache.isNotEmpty()) {
                AppLog.info("Agenda cargada desde Caché Local")
                procesarYConstruirCSV(csvCache)
                actualizarTextoUltimaSincro()
            }
        } catch (e: Exception) {
            AppLog.error("Error cargando caché: ${e.message}")
        }
    }

    private fun descargarAgendaNube(modoSilencioso: Boolean) {
        val client = OkHttpClient()
        val request = Request.Builder().url(URL_GOOGLE_SHEETS_CSV).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.error("Error red CSV: ${e.message}")
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
                        Toast.makeText(this@MainActivity, "✨ ¡Sincronizado!", Toast.LENGTH_SHORT).show()
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
            val listaNumerosPermitidos = mutableListOf<String>()

            var avisoEncontrado = ""
            var horaReinicioEncontrada = ""

            for (linea in lineas) {
                if (linea.isBlank()) continue
                val partes = if (linea.contains(";")) linea.split(";") else linea.split(",")

                if (partes.size >= 3) {
                    val grupoExcel = partes[0].trim().replace("\"", "").replace("\uFEFF", "")
                    val nombreExcel = partes[1].trim().replace("\"", "")
                    val telefonoExcel = partes[2].trim().replace("\"", "")

                    if (partes.size >= 5) {
                        val telefonoITGlobal = partes[3].trim().replace("\"", "")
                        val pinITGlobal = partes[4].trim().replace("\"", "")
                        prefs.edit().apply {
                            if (telefonoITGlobal.isNotEmpty()) putString("telefono_it", telefonoITGlobal)
                            if (pinITGlobal.isNotEmpty()) putString("pin_it", pinITGlobal)
                        }.apply()
                    }

                    if (partes.size >= 8) {
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

                    // ⏱️ LECTURA DINÁMICA DE LA COLUMNA DE INACTIVIDAD (Columna 10 / Índice 9)
                    if (partes.size >= 10) {
                        val minutosExcel = partes[9].trim().replace("\"", "").toIntOrNull()
                        if (minutosExcel != null && minutosExcel > 0) {
                            minutosInactividadConfig = minutosExcel
                            prefs.edit().putInt("minutos_inactividad_custom", minutosExcel).apply()
                        }
                    }

                    if (grupoExcel.equals(grupoFiltro.trim(), ignoreCase = true)) {
                        if (partes.size >= 6) {
                            val textoAviso = partes[5].trim().replace("\"", "")
                            if (textoAviso.isNotEmpty()) {
                                avisoEncontrado = textoAviso
                            }
                        }

                        if (partes.size >= 7) {
                            val horaRein = partes[6].trim().replace("\"", "")
                            if (horaRein.isNotEmpty()) {
                                horaReinicioEncontrada = horaRein
                            }
                        }

                        if (nombreExcel.isNotEmpty() && telefonoExcel.isNotEmpty()) {
                            nuevosBotones.add(Pair(nombreExcel, telefonoExcel))
                        }
                    }
                }
            }

            if (horaReinicioEncontrada.isNotEmpty()) {
                prefs.edit().putString("hora_reinicio_seccion", horaReinicioEncontrada).apply()
            }

            AppLog.info("Grupo: '$grupoFiltro' | Botones: ${nuevosBotones.size} | Inactividad Config: ${minutosInactividadConfig}m")

            contactosGuardados = nuevosBotones.toList()
            whitelistGlobal = listaNumerosPermitidos.toList()

            runOnUiThread {
                construirPanelDesdeNube(nuevosBotones)
                actualizarBannerUrgencia(avisoEncontrado)
            }
        } catch (e: Exception) {
            AppLog.error("Error procesando CSV: ${e.message}")
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
            Toast.makeText(this, "🔄 Sincro: $ultimaSincro\n⏱️ Inactividad: ${minutosInactividadConfig} min", Toast.LENGTH_LONG).show()
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun colgarLlamadaReal() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall()
                }
            }
        } catch (e: Exception) {
            AppLog.error("Excepción al colgar llamada: ${e.message}")
        }
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
        } catch (e: Exception) {
            AppLog.error("Excepción al contestar llamada: ${e.message}")
        }
    }

    private fun mostrarPantallaLlamadaEntrante(nombreCaller: String, numeroCaller: String) {
        if (dialogLlamadaEntrante != null && dialogLlamadaEntrante!!.isShowing) return

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
        } catch (e: Exception) {
            AppLog.error("Error forzando WakeLock: ${e.message}")
        }

        runOnUiThread {
            try {
                dialogLlamadaEntrante = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    window?.let { win ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        } else {
                            @Suppress("DEPRECATION")
                            win.setType(WindowManager.LayoutParams.TYPE_PHONE)
                        }

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
                            cornerRadius = 50f
                            setStroke(2, Color.parseColor("#334155"))
                        }
                        setPadding(40, 80, 40, 80)

                        layoutParams = LinearLayout.LayoutParams(
                            (resources.displayMetrics.widthPixels * 0.90).toInt(),
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        elevation = 30f
                    }

                    val iconoPersona = TextView(context).apply {
                        text = "👤"
                        textSize = 80f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 0, 30) }

                        val pulseAnim = ScaleAnimation(
                            0.95f, 1.05f, 0.95f, 1.05f,
                            Animation.RELATIVE_TO_SELF, 0.5f,
                            Animation.RELATIVE_TO_SELF, 0.5f
                        ).apply {
                            duration = 800
                            repeatMode = Animation.REVERSE
                            repeatCount = Animation.INFINITE
                        }
                        startAnimation(pulseAnim)
                    }

                    val tituloLlamada = TextView(context).apply {
                        text = "LLAMADA ENTRANTE"
                        setTextColor(Color.parseColor("#00E5FF"))
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        letterSpacing = 0.1f
                    }

                    val textoNombre = TextView(context).apply {
                        text = nombreCaller
                        setTextColor(Color.WHITE)
                        textSize = 42f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 20, 0, 5) }
                    }

                    val textoNumero = TextView(context).apply {
                        text = numeroCaller
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 22f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 80) }
                    }

                    val buttonLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }

                    val btnRechazar = Button(context).apply {
                        text = "  COLGAR"
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#E11D48"))
                            cornerRadius = 100f
                        }
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, 40, 0, 40)

                        val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(20, 0, 20, 0)
                        }
                        layoutParams = p
                    }

                    val btnContestar = Button(context).apply {
                        text = "  CONTESTAR"
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#10B981"))
                            cornerRadius = 100f
                        }
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, 40, 0, 40)

                        val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(20, 0, 20, 0)
                        }
                        layoutParams = p
                    }

                    btnContestar.setOnClickListener {
                        AppLog.success("Llamada contestada desde UI Pro")
                        contestarLlamadaReal()
                        dismiss()
                        mostrarPantallaLlamando(nombreCaller, numeroCaller)
                    }

                    btnRechazar.setOnClickListener {
                        AppLog.warning("Llamada colgada desde UI Pro")
                        colgarLlamadaReal()
                        dismiss()
                        liberarPantalla()
                    }

                    buttonLayout.addView(btnRechazar)
                    buttonLayout.addView(btnContestar)

                    cardLayout.addView(iconoPersona)
                    cardLayout.addView(tituloLlamada)
                    cardLayout.addView(textoNombre)
                    cardLayout.addView(textoNumero)
                    cardLayout.addView(buttonLayout)

                    rootLayout.addView(cardLayout)

                    setContentView(rootLayout)
                    setCancelable(false)
                    show()
                }
            } catch (e: Exception) {
                AppLog.error("Error al mostrar UI de llamada entrante: ${e.message}")
            }
        }
    }

    private fun mostrarPantallaLlamando(nombre: String, numero: String) {
        if (dialogLlamadaActiva != null && dialogLlamadaActiva!!.isShowing) return

        runOnUiThread {
            try {
                dialogLlamadaActiva = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    window?.let { win ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        } else {
                            @Suppress("DEPRECATION")
                            win.setType(WindowManager.LayoutParams.TYPE_PHONE)
                        }

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
                            intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
                        )
                    }

                    val cardLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#1E293B"))
                            cornerRadius = 50f
                            setStroke(2, Color.parseColor("#10B981"))
                        }
                        setPadding(40, 80, 40, 80)

                        layoutParams = LinearLayout.LayoutParams(
                            (resources.displayMetrics.widthPixels * 0.85).toInt(),
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        elevation = 30f
                    }

                    val tituloEstado = TextView(context).apply {
                        text = "EN LLAMADA"
                        setTextColor(Color.parseColor("#10B981"))
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        letterSpacing = 0.1f
                    }

                    val textoNombre = TextView(context).apply {
                        text = nombre
                        setTextColor(Color.WHITE)
                        textSize = 38f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 20, 0, 5) }
                    }

                    val textoNumero = TextView(context).apply {
                        text = numero
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 20f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 80) }
                    }

                    val btnColgar = Button(context).apply {
                        text = "FINALIZAR LLAMADA"
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#E11D48"))
                            cornerRadius = 100f
                        }
                        setTextColor(Color.WHITE)
                        textSize = 22f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, 45, 0, 45)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }

                    if (numero.isNotEmpty()) {
                        try {
                            val intentCall = Intent(Intent.ACTION_CALL, Uri.parse("tel:$numero"))
                            context.startActivity(intentCall)
                        } catch (e: Exception) {
                            AppLog.error("Error marcando: ${e.message}")
                        }
                    }

                    btnColgar.setOnClickListener {
                        AppLog.info("Colgado manual desde UI Activa Pro")
                        colgarLlamadaReal()
                        dismiss()
                        liberarPantalla()
                    }

                    cardLayout.addView(tituloEstado)
                    cardLayout.addView(textoNombre)
                    cardLayout.addView(textoNumero)
                    cardLayout.addView(btnColgar)

                    rootLayout.addView(cardLayout)

                    setContentView(rootLayout)
                    setCancelable(false)
                    show()
                }
            } catch (e: Exception) {
                AppLog.error("Error al mostrar UI Activa: ${e.message}")
            }
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
            val iconoBateria = if (isCharging) "⚡" else "🔋"
            findViewById<TextView>(R.id.tvBateria)?.text = "$iconoBateria $pct%"
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val tvWifi = findViewById<TextView>(R.id.tvWifi)

            var estadoWifi = "❌ Wi-Fi"
            var estadoDatos = "❌ Datos"
            var wifiActivo = false
            var datosActivos = false

            val networks = cm.allNetworks
            for (network in networks) {
                val capabilities = cm.getNetworkCapabilities(network) ?: continue

                val tieneInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (tieneInternet || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                        estadoWifi = "🟢 Wi-Fi"
                        wifiActivo = true
                    } else if (!wifiActivo) {
                        estadoWifi = "🟠 Wi-Fi (!)"
                    }
                }

                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    if (tieneInternet || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                        estadoDatos = "📶 Datos"
                        datosActivos = true
                    } else if (!datosActivos) {
                        estadoDatos = "🟠 Datos (!)"
                    }
                }
            }

            tvWifi?.text = "$estadoWifi | $estadoDatos"

            if (wifiActivo || datosActivos) {
                tvWifi?.setTextColor(Color.parseColor("#00C853"))
            } else {
                tvWifi?.setTextColor(Color.parseColor("#C8102E"))
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
        try { unregisterReceiver(callStateReceiver) } catch (e: Exception) {}

        try { dialogoOTA?.dismiss() } catch (e: Exception) {}
        try { dialogScreensaver?.dismiss() } catch (e: Exception) {}
        liberarPantalla()
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
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CALL_LOG
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
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
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
                Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG
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
        AppLog.warning("Activada broma llamada del Jefe")
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

                var smsManager: SmsManager? = null
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    smsManager = getSystemService(SmsManager::class.java)
                } else {
                    smsManager = SmsManager.getDefault()
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

            var smsManager: SmsManager? = null
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager::class.java)
            } else {
                smsManager = SmsManager.getDefault()
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
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Panel de Acceso IT")
            .setMessage("Introduce Código de Acceso:")
            .setView(input)
            .setPositiveButton("Entrar") { _, _ ->
                val codigoMetido = input.text.toString()

                if (codigoMetido == "*###9999#" || codigoMetido == pinCorrecto) {
                    prefs.edit().putInt("intentos_fallidos_pin", 0).apply()

                    try { stopLockTask() } catch (e: Exception) {}

                    if (codigoMetido == "*###9999#") {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                        finish()
                    } else {
                        startActivity(Intent(this, ItActivity::class.java))
                    }
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

                    iniciarHackeoConsola()
                }
            }
            .setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun iniciarHackeoConsola() {
        AppLog.warning("Intento fallido de PIN IT - Consola simulada")
        enviarAlertaIT("⚠️ ALERTA: Intento de violación de seguridad en menú IT.")
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
        AppLog.info("Iniciado juego de ratones")
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
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        } else {
                            @Suppress("DEPRECATION")
                            win.setType(WindowManager.LayoutParams.TYPE_PHONE)
                        }

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
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
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

    private fun comprobarActualizacionOTA() {
        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder().url(URL_OTA_JSON).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.error("OTA Error Red consultando version.json: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val jsonStr = response.body?.string()
                    if (!response.isSuccessful || jsonStr.isNullOrEmpty()) {
                        AppLog.error("OTA JSON Falló: HTTP ${response.code}")
                        return
                    }

                    val jsonObject = JSONObject(jsonStr)
                    val versionNube = jsonObject.getInt("versionCode")
                    val apkUrl = jsonObject.getString("apkUrl")

                    val pInfo = packageManager.getPackageInfo(packageName, 0)

                    var versionActual: Int = 0
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        versionActual = pInfo.longVersionCode.toInt()
                    } else {
                        versionActual = pInfo.versionCode
                    }

                    AppLog.info("OTA Check -> Local: $versionActual | Nube: $versionNube")

                    if (versionNube > versionActual) {
                        AppLog.ota("Versión nueva detectada en nube (v$versionNube). Descargando APK desde: $apkUrl")
                        descargarYInstalarAPK(apkUrl)
                    } else {
                        AppLog.success("Aplicación al día (Local: $versionActual >= Nube: $versionNube).")
                    }
                } catch (e: Exception) {
                    AppLog.error("Excepción procesando JSON OTA: ${e.message}")
                }
            }
        })
    }

    private fun descargarYInstalarAPK(url: String) {
        // Asegurar que la pantalla de descarga se lanza inmediatamente en el hilo principal
        mostrarPantallaOTA()

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.error("Error de red descargando APK: ${e.message}")
                actualizarProgresoOTA(0, "Error de red al descargar. Reintentando luego.", false)
                handler.postDelayed({ runOnUiThread { try { dialogoOTA?.dismiss() } catch(e:Exception){} } }, 4000)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        AppLog.error("Error HTTP descargando APK: Código ${response.code}")
                        actualizarProgresoOTA(0, "Error en el servidor de descargas.", false)
                        handler.postDelayed({ runOnUiThread { try { dialogoOTA?.dismiss() } catch(e:Exception){} } }, 4000)
                        return
                    }

                    val body = response.body
                    if (body == null) {
                        AppLog.error("Error crítico: El cuerpo de respuesta está vacío")
                        return
                    }

                    val file = File(getExternalFilesDir(null), "update.apk")
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(file)
                    val contentLength = body.contentLength()

                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val mbLeidos = String.format(Locale.US, "%.2f", totalBytesRead / (1024.0 * 1024.0))

                        if (contentLength > 0) {
                            val progreso = ((totalBytesRead * 100) / contentLength).toInt()
                            val mbTotales = String.format(Locale.US, "%.2f", contentLength / (1024.0 * 1024.0))
                            actualizarProgresoOTA(progreso, "Descargando actualización... $progreso%\n($mbLeidos MB / $mbTotales MB)", false)
                        } else {
                            actualizarProgresoOTA(0, "Descargando actualización...\n($mbLeidos MB descargados)", true)
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    AppLog.success("APK descargado con éxito (${file.length()} bytes). Iniciando PackageInstaller...")
                    actualizarProgresoOTA(100, "⚙️ Instalando nueva versión en segundo plano...\nNo apague el dispositivo.", true)
                    instalarApkSilenciosa(file)

                } catch (e: Exception) {
                    AppLog.error("Excepción guardando archivo APK local: ${e.message}")
                    actualizarProgresoOTA(0, "Error al guardar el archivo.", false)
                    handler.postDelayed({ runOnUiThread { try { dialogoOTA?.dismiss() } catch(e:Exception){} } }, 4000)
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

            var flagMutable = PendingIntent.FLAG_UPDATE_CURRENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                flagMutable = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            }

            val intent = Intent(this, ApkInstallReceiver::class.java).apply {
                action = "com.grupotgt.launcherkioscotgt.INSTALL_COMPLETE"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                flagMutable
            )

            session.commit(pendingIntent.intentSender)
            AppLog.ota("Sesión de PackageInstaller enviada a Android con éxito. Esperando broadcast...")

        } catch (e: Exception) {
            AppLog.error("Fallo crítico al invocar PackageInstaller: ${e.message}")
            actualizarProgresoOTA(0, "Fallo al iniciar la instalación.", false)
            handler.postDelayed({ runOnUiThread { try { dialogoOTA?.dismiss() } catch(e:Exception){} } }, 4000)
        }
    }
}