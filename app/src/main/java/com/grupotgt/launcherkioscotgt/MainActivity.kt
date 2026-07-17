package com.grupotgt.launcherkioscotgt

import android.Manifest
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

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    // ==========================================
    // ⚙️ CONFIGURACIÓN DE LA AGENDA EN LA NUBE
    // ==========================================
    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"

    // ==========================================
    // 🔄 SERVIDOR DE ACTUALIZACIONES OTA
    // ==========================================
    private val URL_OTA_JSON = "https://grupotgt.github.io/actualizaciones-launcher/version.json"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnableConsola: Runnable
    private var runnableAutoApagado: Runnable? = null
    private var toquesSalida = 0
    private var toquesBateria = 0
    private var toquesWifi = 0

    private var dialogLlamadaActiva: Dialog? = null
    private var dialogLlamadaEntrante: Dialog? = null

    private val runnableEstadoDispositivo = object : Runnable {
        override fun run() {
            actualizarIndicadoresReales()
            handler.postDelayed(this, 5000)
        }
    }

    private val callStateReceiver = object : android.content.BroadcastReceiver() {
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
            setContentView(R.layout.activity_main)

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
            })

            solicitarPermisos()
            solicitarAdministradorDispositivo()
            forzarVolumenMaximo()

            cargarIdentificacionYLogo()

            descargarAgendaNube()
            comprobarActualizacionOTA() // <- Llama al motor de actualizaciones

            configurarBotonSecreto()
            configurarBotonRatones()
            configurarBromaJefe()

            handler.post(runnableEstadoDispositivo)

            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(callStateReceiver, filter)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==========================================
    // ☁️ MOTOR DE DESCARGA DE AGENDA NUBE
    // ==========================================
    private fun descargarAgendaNube() {
        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val grupoFiltro = prefs.getString("ubicacion_dispositivo", "") ?: ""

        if (URL_GOOGLE_SHEETS_CSV == "AQUI_PEGAR_EL_ENLACE_CSV_PUBLICO_DE_TU_EXCEL") {
            runOnUiThread {
                Toast.makeText(this, "Aviso: Enlace de nube no configurado", Toast.LENGTH_LONG).show()
            }
            return
        }

        val client = OkHttpClient()
        val request = Request.Builder().url(URL_GOOGLE_SHEETS_CSV).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Sin conexión: Usando caché local", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val csvData = response.body?.string() ?: return
                val lineas = csvData.split("\n")
                val nuevosBotones = mutableListOf<Pair<String, String>>()

                for (linea in lineas) {
                    val partes = linea.split(",")
                    if (partes.size >= 3) {
                        val grupoExcel = partes[0].trim()
                        val nombreExcel = partes[1].trim()
                        val telefonoExcel = partes[2].trim()

                        if (grupoExcel.equals(grupoFiltro, ignoreCase = true)) {
                            nuevosBotones.add(Pair(nombreExcel, telefonoExcel))
                        }
                    }
                }

                runOnUiThread {
                    construirPanelDesdeNube(nuevosBotones)
                }
            }
        })
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

            if (listaContactos.isEmpty()) {
                val aviso = TextView(this).apply {
                    text = "No hay contactos en la nube para este grupo"
                    gravity = Gravity.CENTER
                    setPadding(0,50,0,0)
                }
                panelBase?.addView(aviso)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==========================================
    // CONTROL REAL DE TELEFONÍA
    // ==========================================
    @android.annotation.SuppressLint("MissingPermission")
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

    @android.annotation.SuppressLint("MissingPermission")
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

        btnColgar.setOnClickListener {
            colgarLlamadaReal()
            dialogLlamadaActiva?.dismiss()
        }
        dialogLlamadaActiva?.show()
    }

    // =======================================================
    // LECTURA DE BATERÍA Y CHIVATO AVANZADO VoWiFi (CORREGIDO)
    // =======================================================
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
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            val tvWifi = findViewById<TextView>(R.id.tvWifi)

            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {

                    // Comprobación segura y compatible con el compilador público de Android Studio
                    val llamadasWifiActivas = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)

                    if (llamadasWifiActivas) {
                        tvWifi?.text = "🟢 Llamadas Wi-Fi: ACTIVAS"
                        tvWifi?.setTextColor(Color.parseColor("#00C853"))
                    } else {
                        tvWifi?.text = "🟠 Wi-Fi OK (Sin Llamadas)"
                        tvWifi?.setTextColor(Color.parseColor("#E65100"))
                    }
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    tvWifi?.text = "📶 4G/5G: OK"
                    tvWifi?.setTextColor(Color.parseColor("#5F6368"))
                } else {
                    tvWifi?.text = "❌ Sin Conexión"
                    tvWifi?.setTextColor(Color.parseColor("#C8102E"))
                }
            } else {
                tvWifi?.text = "❌ Sin Conexión"
                tvWifi?.setTextColor(Color.parseColor("#C8102E"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnableEstadoDispositivo)
        try { unregisterReceiver(callStateReceiver) } catch (e: Exception) {}
    }

    // =======================================================
    // BLINDAJE KIOSCO
    // =======================================================
    private fun configurarModoKioscoEstricto() {
        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, MyAdminReceiver::class.java)

            if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                devicePolicyManager.setLockTaskPackages(componentName, arrayOf(packageName))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val permisosCriticos = arrayOf(
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.CAMERA,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE,
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
        descargarAgendaNube()
        comprobarActualizacionOTA() // <- Chequeo de seguridad al volver a la app
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
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            forzarVolumenMaximo()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
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

    // =======================================================
    // MOTOR DE SMS MEJORADO
    // =======================================================
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

            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    private fun mostrarDialogoPIN() {
        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val pinCorrecto = prefs.getString("pin_it", "1234")
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; gravity = Gravity.CENTER }
        AlertDialog.Builder(this)
            .setTitle("Acceso IT").setMessage("Introduce Código:").setView(input)
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
            .setNegativeButton("Cancelar", null).show()
    }

    private fun iniciarHackeoConsola() {
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
                text = "📸 CAPTURA GUARDADA\n\nTu identidad ha sido registrada.\n\n¡Eso no se hace!"; setTextColor(Color.RED); textSize = 24f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(40, 40, 40, 40); setBackgroundColor(Color.parseColor("#CC000000")); layoutParams = LinearLayout.LayoutParams(-1, -1)
            }
            layoutBase.addView(txt)
            handler.postDelayed({ recreate() }, 6000)
        }, 150)
    }

    private fun iniciarJuegoRatones() {
        enviarAlertaIT("⚠️ ALERTA: Intrusismo detectado. Jugando a RATONES.")

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
    // MOTOR DE ACTUALIZACIÓN INVISIBLE (OTA)
    // =======================================================
    private fun comprobarActualizacionOTA() {
        val client = OkHttpClient()
        val request = Request.Builder().url(URL_OTA_JSON).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Fallo silencioso si no hay internet. Lo volverá a intentar en el próximo arranque.
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val jsonStr = response.body?.string() ?: return
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

                    if (versionNube > versionActual) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "🔄 Actualización detectada. Instalando en segundo plano...", Toast.LENGTH_LONG).show()
                        }
                        descargarYInstalarAPK(apkUrl)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private fun descargarYInstalarAPK(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                try {
                    val apkData = response.body?.bytes() ?: return
                    val file = File(getExternalFilesDir(null), "update.apk")
                    val fos = FileOutputStream(file)
                    fos.write(apkData)
                    fos.flush()
                    fos.close()

                    instalarApkSilenciosa(file)
                } catch (e: Exception) {
                    e.printStackTrace()
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

            // Disparamos la instalación. Al ser DeviceOwner, se instalará sola y reiniciará la app.
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}