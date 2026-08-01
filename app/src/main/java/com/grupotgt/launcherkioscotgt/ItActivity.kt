package com.grupotgt.launcherkioscotgt

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.SmsManager
import android.widget.Button
import android.widget.EditText
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ItActivity : AppCompatActivity() {

    private val URL_GOOGLE_SHEETS_CSV = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSye0TO9CYH8xXSPy-rCNDOO4UjiNdmp32SiOWLwxsUPI25ZW9rHW44JlAPn38_4vVpJK5Pw6tu5Ct0/pub?output=csv"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_it)

        val etUbicacion = findViewById<EditText>(R.id.etUbicacion)
        val btnGuardar = findViewById<Button>(R.id.btnGuardarConfig)
        val btnTestSincro = findViewById<Button>(R.id.btnTestSincro)
        val btnVerLogsIt = findViewById<Button>(R.id.btnVerLogsIt)
        val btnForzarOTA = findViewById<Button>(R.id.btnForzarOTA)
        val btnAbrirAjustesAndroid = findViewById<Button>(R.id.btnAbrirAjustesAndroid)
        val btnCerrarPanelIT = findViewById<Button>(R.id.btnCerrarPanelIT)
        val tvEstadoKiosco = findViewById<TextView>(R.id.tvEstadoKiosco)
        val tvVersionApp = findViewById<TextView>(R.id.tvVersionApp)

        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val ubicacionActual = prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4")
        etUbicacion?.setText(ubicacionActual)

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

        // Forzar Sincro con Excel real
        btnTestSincro?.setOnClickListener {
            Toast.makeText(this, "🔄 Sincronizando datos y avisos con la nube...", Toast.LENGTH_SHORT).show()
            AppLog.registrar("🔄 Forzada sincronización manual desde Panel IT")

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
                        AppLog.registrar("✨ Sincronización manual completada y guardada en caché")
                        runOnUiThread { Toast.makeText(this@ItActivity, "✨ ¡Sincronizado y avisos actualizados!", Toast.LENGTH_LONG).show() }
                    } catch (e: Exception) {
                        AppLog.registrar("❌ Excepción guardando caché manual: ${e.message}")
                    }
                }
            })
        }

        // Ver Logs en pantalla (Exclusivo desde Panel IT con botones de envío)
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
            .setNegativeButton("📤 Enviar por SMS") { _, _ ->
                enviarLogsPorSms()
            }
            .setNeutralButton("📧 Enviar por Correo") { _, _ ->
                enviarLogsPorCorreo()
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

    private fun enviarLogsPorCorreo() {
        try {
            val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
            val correoIT = prefs.getString("correo_it", "") ?: ""
            if (correoIT.isEmpty()) {
                Toast.makeText(this, "⚠️ No hay correo configurado en la columna 8 del Excel para esta sección", Toast.LENGTH_LONG).show()
                return
            }
            val ubicacion = prefs.getString("ubicacion_dispositivo", "Ubicación Desconocida")
            val logsCompletos = AppLog.obtenerLogs()

            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(correoIT))
                putExtra(Intent.EXTRA_SUBJECT, "🚨 Diagnóstico y Logs Kiosco TGT - [$ubicacion]")
                putExtra(Intent.EXTRA_TEXT, "Adjunto el registro de actividad y errores (Logs) del terminal:\n\n$logsCompletos")
            }
            startActivity(Intent.createChooser(intent, "Enviar logs por correo..."))
            AppLog.registrar("📧 Cliente de correo abierto para enviar diagnóstico a: $correoIT")
        } catch (e: Exception) {
            AppLog.registrar("❌ Error abriendo cliente de correo: ${e.message}")
            Toast.makeText(this, "❌ Error al abrir correo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}