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

    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"
    private val URL_OTA_JSON = "https://grupotgt.github.io/actualizaciones-launcher/version.json"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnableConsola: Runnable
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
            comprobarActualizacionOTA()

            configurarBotonSecreto()
            configurarBotonRatones()
            configurarBromaJefe()

            handler.post(runnableEstadoDispositivo)
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(callStateReceiver, filter)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // --- Motor de Actualización OTA ---
    private fun comprobarActualizacionOTA() {
        val client = OkHttpClient()
        val request = Request.Builder().url(URL_OTA_JSON).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                try {
                    val jsonStr = response.body?.string() ?: return
                    val jsonObject = JSONObject(jsonStr)
                    val versionNube = jsonObject.getInt("versionCode")
                    val apkUrl = jsonObject.getString("apkUrl")
                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    val versionActual = pInfo.versionCode
                    if (versionNube > versionActual) {
                        descargarYInstalarAPK(apkUrl)
                    }
                } catch (e: Exception) { e.printStackTrace() }
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
                    val file = File(getExternalFilesDir(null), "update.apk")
                    FileOutputStream(file).use { it.write(response.body?.bytes()) }
                    instalarApkSilenciosa(file)
                } catch (e: Exception) { e.printStackTrace() }
            }
        })
    }

    private fun instalarApkSilenciosa(apkFile: File) {
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            session.openWrite("update", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { it.copyTo(out) }
            }
            val intent = Intent(this, MainActivity::class.java)
            val sender = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            session.commit(sender.intentSender)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ... (El resto de tus funciones auxiliares: descargarAgendaNube, mostrarPantallaLlamadaEntrante, etc. se mantienen igual)
    // Nota: Como el código es muy largo, he incluido las partes críticas de actualización.
    // Asegúrate de pegar esto sobre tu clase actual manteniendo el resto de métodos.
}