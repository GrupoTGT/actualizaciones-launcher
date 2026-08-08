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
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.NetworkInterface
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

                            val smsManager: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.getSystemService(SmsManager::class.java)
                            } else {
                                SmsManager.getDefault()
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
    private val intervaloCheckOTA = 2 * 60 * 1000L

    private var wakeLockLlamada: android.os.PowerManager.WakeLock? = null

    private var contactosGuardados = listOf<Pair<String, String>>()
    private var whitelistGlobal = listOf<String>()

    private var handlerInactividad = Handler(Looper.getMainLooper())
    private var minutosInactividadConfig = 10
    private var dialogScreensaver: Dialog? = null

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

    private var toquesSalida = 0
    private var toquesBateria = 0
    private var toquesWifi = 0
    private var contadorVolumenAbajo = 0
    private var contadorVolumenArriba = 0

    private var dialogLlamadaActiva: Dialog? = null
    private var dialogLlamadaEntrante: Dialog? = null
    private var dialogBloqueoRobo: Dialog? = null

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
            AppLog.info("MainActivity iniciada (Versión Definitiva MDM Hardware)")
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

            reiniciarTemporizadorInactividad()
            // La sincronización inicial ya enviará la telemetría una vez aplicada la configuración
            // recibida de Google Sheets. Evitamos un heartbeat prematuro con valores antiguos.

        } catch (e: Exception) {
            AppLog.error("Error en onCreate: ${e.message}")
            e.printStackTrace()
        }
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

                dialogScreensaver = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    window?.let { win ->
                        win.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
                        @Suppress("DEPRECATION")
                        win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                    }

                    val rootLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#020617"), Color.parseColor("#0F172A")))
                    }

                    val topLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER or Gravity.TOP
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                        setPadding(0, 120, 0, 0)
                    }

                    if (!logoUriString.isNullOrEmpty()) {
                        try {
                            val ivLogo = ImageView(context).apply {
                                setImageURI(Uri.parse(logoUriString))
                                layoutParams = LinearLayout.LayoutParams(600, 250).apply { setMargins(0, 0, 0, 30) }
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }
                            topLayout.addView(ivLogo)
                        } catch (e: Exception) {
                            val tvMarcaFallback = TextView(context).apply {
                                text = "GRUPO TGT"; setTextColor(Color.parseColor("#C8102E")); textSize = 38f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); letterSpacing = 0.2f
                                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 0, 20) }
                            }
                            topLayout.addView(tvMarcaFallback)
                        }
                    } else {
                        val tvMarcaTGT = TextView(context).apply {
                            text = "GRUPO TGT"; setTextColor(Color.parseColor("#C8102E")); textSize = 38f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); letterSpacing = 0.2f
                            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 0, 20) }
                        }
                        topLayout.addView(tvMarcaTGT)
                    }

                    val tvSeccion = TextView(context).apply {
                        text = nombreSeccion; setTextColor(Color.parseColor("#F59E0B")); textSize = 28f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); letterSpacing = 0.1f
                    }
                    topLayout.addView(tvSeccion)

                    val centerLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }

                    val tvHoraGigante = TextClock(context).apply {
                        format24Hour = "HH:mm"; format12Hour = "HH:mm"; setTextColor(Color.WHITE); textSize = 115f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setShadowLayer(30f, 0f, 0f, Color.parseColor("#00E5FF"))
                    }
                    centerLayout.addView(tvHoraGigante)

                    val bottomLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); setPadding(0, 0, 0, 50)
                    }

                    val tvAvisoToque = TextView(context).apply {
                        text = "Toca la pantalla para volver"; setTextColor(Color.parseColor("#64748B")); textSize = 18f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 0, 40) }
                        val alphaAnim = android.view.animation.AlphaAnimation(0.4f, 1.0f).apply { duration = 1500; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }
                        startAnimation(alphaAnim)
                    }

                    val tvFirma = TextView(context).apply { text = "Creado por Marco Carpi."; setTextColor(Color.parseColor("#1E293B")); textSize = 11f; gravity = Gravity.CENTER }

                    bottomLayout.addView(tvAvisoToque)
                    bottomLayout.addView(tvFirma)

                    rootLayout.addView(topLayout)
                    rootLayout.addView(centerLayout)
                    rootLayout.addView(bottomLayout)

                    rootLayout.setOnClickListener { dismiss(); reiniciarTemporizadorInactividad() }

                    setContentView(rootLayout)
                    setCancelable(false)
                    show()
                }
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

    private fun descargarAgendaNube(modoSilencioso: Boolean) {
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
                    procesarYConstruirCSV(csvData, ejecutarAccionesRemotas = true)
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

    private fun procesarYConstruirCSV(csvData: String, ejecutarAccionesRemotas: Boolean = true) {
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

            if (horaReinicioEncontrada.isNotEmpty()) {
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
                actualizarBannerUrgencia(avisoEncontrado)

                if (volumenCmd != null) aplicarVolumenRemoto(volumenCmd!!)
                if (brilloCmd != null) aplicarBrilloRemoto(brilloCmd!!)
                if (timeoutCmd.isNotEmpty() && timeoutCmd != ultimoTimeoutEjecutado) {
                    aplicarTimeoutPantalla(timeoutCmd)
                    ultimoTimeoutEjecutado = timeoutCmd
                    prefs.edit().putString("mdm_ultimo_timeout", timeoutCmd).apply()
                }

                if (ejecutarAccionesRemotas) {
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
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                        elevation = 8f

                        val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(8, 8, 8, 8)
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
            Toast.makeText(this, "🔄 Sincro: $ultimaSincro\n⏱️ Inactividad: ${minutosInactividadConfig} min\n🛡️ Whitelist: ${whitelistGlobal.size} núms", Toast.LENGTH_LONG).show()
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun colgarLlamadaReal() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
            Handler(Looper.getMainLooper()).postDelayed({ startActivity(intentBring) }, 800)
            Handler(Looper.getMainLooper()).postDelayed({ startActivity(intentBring) }, 2000)

        } catch (e: Exception) {
            AppLog.error("Error forzando WakeLock: ${e.message}")
        }

        runOnUiThread {
            try {
                dialogLlamadaEntrante = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
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
                        mostrarPantallaLlamando(nombreCaller, numeroCaller, false)
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

    private fun mostrarPantallaLlamando(nombre: String, numero: String, esSaliente: Boolean = true) {
        if (dialogLlamadaActiva != null && dialogLlamadaActiva!!.isShowing) return

        runOnUiThread {
            try {
                dialogLlamadaActiva = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
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

                if (esSaliente && numero.isNotEmpty()) {
                    val numLimpio = numero.replace(" ", "")
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val intentCall = Intent(Intent.ACTION_CALL, Uri.parse("tel:$numLimpio"))
                            this@MainActivity.startActivity(intentCall)

                            val intentBring = Intent(this@MainActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            Handler(Looper.getMainLooper()).postDelayed({ this@MainActivity.startActivity(intentBring) }, 500)
                            Handler(Looper.getMainLooper()).postDelayed({ this@MainActivity.startActivity(intentBring) }, 1500)
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
        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, MyAdminReceiver::class.java)

            if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val appsStr = prefs.getString("apps_permitidas", "") ?: ""
                val appsList = if (appsStr.isNotEmpty()) appsStr.split(",") else emptyList()

                val paquetesAutorizados = mutableListOf(packageName)
                paquetesAutorizados.addAll(appsList)

                devicePolicyManager.setLockTaskPackages(componentName, paquetesAutorizados.toTypedArray())

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
                Manifest.permission.SEND_SMS, Manifest.permission.CAMERA,
                Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG
            )
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
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

    private fun comprobarActualizacionOTA() {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val urlOtaSinCache = "$URL_OTA_JSON?t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(urlOtaSinCache).build()

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
                    val versionActual: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
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

            try {
                val sessions = packageInstaller.mySessions
                for (sessionInfo in sessions) {
                    try {
                        val openSession = packageInstaller.openSession(sessionInfo.sessionId)
                        openSession.abandon()
                        AppLog.ota("Sesión PackageInstaller anterior (${sessionInfo.sessionId}) abortada correctamente.")
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    // --- SISTEMA DE INVENTARIO Y TELEMETRÍA MDM TGT ---
    @SuppressLint("MissingPermission")
    private fun enviarTelemetriaMDM() {
        Thread {
            try {
                val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
                val ubicacion = prefs.getString("ubicacion_dispositivo", "Desconocida") ?: "Desconocida"
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "Desconocido"

                val fabricante = Build.MANUFACTURER.uppercase(Locale.getDefault())
                val modelo = Build.MODEL ?: "Desconocido"
                val dispositivoFull = "$fabricante $modelo"
                val androidOS = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val versionApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode.toString() else pInfo.versionCode.toString()

                val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                val pctBateria = if (scale > 0) (level * 100) / scale else 0
                val estadoBat = if (isCharging) "🔌 $pctBateria%" else "🔋 $pctBateria%"

                val tempBat = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val temperaturaFinal = "${tempBat / 10.0}ºC"

                val health = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
                val saludBat = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "❤️ Buena"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "🔥 Sobrecalentada"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "💀 Muerta"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "⚡ Sobretensión"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "❌ Fallo"
                    BatteryManager.BATTERY_HEALTH_COLD -> "❄️ Fría"
                    else -> "❓ Desconocida"
                }

                var macReal = "02:00:00:00:00:00"
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminName = ComponentName(this@MainActivity, MyAdminReceiver::class.java)
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        val macDpm = dpm.getWifiMacAddress(adminName)
                        if (!macDpm.isNullOrEmpty()) {
                            macReal = macDpm
                        }
                    }
                } catch (e: Exception) {}

                if (macReal == "02:00:00:00:00:00") {
                    try {
                        val interfaces = NetworkInterface.getNetworkInterfaces()
                        while (interfaces.hasMoreElements()) {
                            val intf = interfaces.nextElement()
                            if (intf.name.equals("wlan0", ignoreCase = true)) {
                                val macBytes = intf.hardwareAddress
                                if (macBytes != null) {
                                    val res1 = StringBuilder()
                                    for (b in macBytes) res1.append(String.format("%02X:", b))
                                    if (res1.isNotEmpty()) res1.deleteCharAt(res1.length - 1)
                                    macReal = res1.toString()
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                var ipLocal = "Desconectado"
                var tipoConexionStr = "Ninguna"
                try {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNetwork = cm.activeNetwork
                    if (activeNetwork != null) {
                        val caps = cm.getNetworkCapabilities(activeNetwork)
                        if (caps != null) {
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) tipoConexionStr = "Wi-Fi"
                            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) tipoConexionStr = "Datos Móviles"
                            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) tipoConexionStr = "Ethernet"
                        }
                    }

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

                var nombreRedStr = "Desconectado"
                try {
                    val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wifiMgr.connectionInfo
                    if (info != null && info.networkId != -1) {
                        nombreRedStr = info.ssid.replace("\"", "")
                        if (nombreRedStr == "<unknown ssid>") nombreRedStr = "Oculta/Desconocida"
                    }
                } catch (e: Exception) {}

                var operadoraFinal = "Sin SIM / Desconocida"
                try {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    val opName = tm.networkOperatorName
                    if (!opName.isNullOrBlank()) {
                        operadoraFinal = opName
                    }
                } catch (e: Exception) {}

                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val rssi = wifiManager?.connectionInfo?.rssi ?: 0

                var espacioLibre = "Desconocido"
                try {
                    val stat = StatFs(Environment.getExternalStorageDirectory().path)
                    val gbDisponibles = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                    val gbTotales = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                    espacioLibre = "$gbDisponibles GB libres (de $gbTotales GB)"
                } catch (e: Exception) {}

                var ramEstado = "Desconocida"
                try {
                    val actManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    val ramLibreGB = String.format(Locale.US, "%.2f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
                    val ramTotalGB = String.format(Locale.US, "%.2f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
                    ramEstado = "$ramLibreGB GB libres (de $ramTotalGB GB)"
                } catch (e: Exception) {}

                var brilloPantalla = "Desconocido"
                try {
                    val brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                    val pctBrillo = (brightness * 100) / 255
                    brilloPantalla = "$pctBrillo%"
                    prefs.edit().putInt("mdm_ultimo_brillo_real_pct", pctBrillo).apply()
                } catch (e: Exception) {
                    val ultimo = prefs.getInt("mdm_ultimo_brillo_real_pct", -1)
                    if (ultimo >= 0) brilloPantalla = "$ultimo%"
                    AppLog.error("Telemetría: no se pudo leer brillo actual: ${e.message}")
                }

                var volumenApp = "Desconocido"
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val volActual = am.getStreamVolume(AudioManager.STREAM_MUSIC)

                    // Durante una locución TTS subimos MUSIC temporalmente al máximo. Para el
                    // Inventario interesa el nivel estable del terminal, no ese pico transitorio.
                    val volEstable = volumenAntesDeTts ?: volActual
                    val pctVol = if (maxVol > 0) (volEstable * 100) / maxVol else 0
                    prefs.edit().putInt("mdm_ultimo_volumen_real_pct", pctVol).apply()
                    volumenApp = if (pctVol == 0) "🔇 Silenciado" else "🔊 $pctVol%"
                } catch (e: Exception) {
                    val ultimo = prefs.getInt("mdm_ultimo_volumen_real_pct", -1)
                    if (ultimo >= 0) volumenApp = if (ultimo == 0) "🔇 Silenciado" else "🔊 $ultimo%"
                    AppLog.error("Telemetría: no se pudo leer volumen actual: ${e.message}")
                }

                AppLog.info("Telemetría MDM -> Brillo=$brilloPantalla | Volumen=$volumenApp")

                var estadoKiosco = "Desconocido"
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val lockTaskMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.lockTaskModeState
                    } else {
                        @Suppress("DEPRECATION")
                        if (am.isInLockTaskMode) 1 else 0
                    }
                    estadoKiosco = if (lockTaskMode != 0) "🔒 Activo" else "🔓 ROTO / Inactivo"
                } catch (e: Exception) {}

                var esOwner = "❌ No"
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    if (dpm.isDeviceOwnerApp(packageName)) esOwner = "✅ Sí"
                } catch (e: Exception) {}

                val uptimeMillis = SystemClock.elapsedRealtime()
                val dias = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(uptimeMillis)
                val horas = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(uptimeMillis) % 24
                val uptimeFinal = "${dias}d ${horas}h"

                val intrusos = prefs.getInt("intentos_fallidos_pin", 0).toString()
                val appsActivas = prefs.getString("apps_permitidas", "Ninguna") ?: "Ninguna"
                val ultimaSincro = prefs.getString("ultima_sincro", "Nunca") ?: "Nunca"

                val logsCompletos = AppLog.obtenerLogs().lines()
                val ultimosLogs = if (logsCompletos.size > 3) {
                    logsCompletos.takeLast(3).joinToString("\n")
                } else {
                    logsCompletos.joinToString("\n")
                }

                val json = JSONObject()
                json.put("fecha", SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
                json.put("ubicacion", ubicacion)
                json.put("dispositivo", dispositivoFull)
                json.put("androidOS", androidOS)
                json.put("mac", macReal)
                json.put("androidId", androidId)
                json.put("ip", ipLocal)
                json.put("conexion", tipoConexionStr)
                json.put("redSSID", nombreRedStr)
                json.put("operadora", operadoraFinal)
                json.put("rssi", "$rssi dBm")
                json.put("bateria", estadoBat)
                json.put("saludBateria", saludBat)
                json.put("temperatura", temperaturaFinal)
                json.put("ram", ramEstado)
                json.put("almacenamiento", espacioLibre)
                json.put("brillo", brilloPantalla)
                json.put("volumen", volumenApp)
                json.put("uptime", uptimeFinal)
                json.put("estadoKiosco", estadoKiosco)
                json.put("deviceOwner", esOwner)
                json.put("version", "v$versionApp")
                json.put("intrusiones", intrusos)
                json.put("apps", appsActivas)
                json.put("ultimaSincro", ultimaSincro)
                json.put("logs", ultimosLogs)

                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = json.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("https://script.google.com/macros/s/AKfycbzs4U3PgP7gYTBGxFql0TyLY7fC6XQxzYLyPNOAn8nQlqQQFXAHIMQrXDyo5zwavg3etA/exec")
                    .post(body)
                    .build()

                OkHttpClient().newCall(request).execute().use { response ->
                    val respuestaScript = response.body?.string()?.trim().orEmpty()
                    if (response.isSuccessful && respuestaScript.equals("OK", ignoreCase = true)) {
                        AppLog.info("📡 Inventario MDM actualizado correctamente. Brillo=$brilloPantalla | Volumen=$volumenApp")
                    } else {
                        // Apps Script puede devolver HTTP 200 incluso cuando su doPost() responde
                        // con texto "ERROR: ...". Antes lo registrábamos falsamente como éxito.
                        AppLog.error("Inventario MDM NO confirmado. HTTP=${response.code} respuesta=[$respuestaScript]")
                    }
                }
            } catch (e: Exception) {
                AppLog.error("Fallo al ejecutar telemetría MDM: ${e.message}")
            }
        }.start()
    }
}