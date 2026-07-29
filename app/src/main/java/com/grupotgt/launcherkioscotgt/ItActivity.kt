package com.grupotgt.launcherkioscotgt

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
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
    private val URL_OTA_JSON = "https://grupotgt.github.io/actualizaciones-launcher/version.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_it)

        val spinnerUbicaciones = findViewById<Spinner>(R.id.spinnerUbicaciones)
        val btnGuardarUbicacion = findViewById<Button>(R.id.btnGuardarUbicacion)
        val etTelefonoIT = findViewById<EditText>(R.id.etTelefonoIT)
        val etNuevoPin = findViewById<EditText>(R.id.etNuevoPin)
        val btnGuardarCredenciales = findViewById<Button>(R.id.btnGuardarCredenciales)
        val btnTestSincro = findViewById<Button>(R.id.btnTestSincro)
        val btnForzarOTA = findViewById<Button>(R.id.btnForzarOTA)
        val btnAbrirAjustesAndroid = findViewById<Button>(R.id.btnAbrirAjustesAndroid)
        val btnCerrarPanelIT = findViewById<Button>(R.id.btnCerrarPanelIT)
        val tvEstadoKiosco = findViewById<TextView>(R.id.tvEstadoKiosco)

        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)

        // 1. Rellenar campos de SMS y PIN con lo que ya estuviera guardado
        etTelefonoIT.setText(prefs.getString("telefono_it", ""))
        etNuevoPin.setText(prefs.getString("pin_it", "1234"))

        // 2. Extraer dinámicamente los grupos (primera columna) del CSV en caché
        val listaGrupos = mutableSetOf<String>()
        try {
            val csvCache = prefs.getString("csv_cache_data", "") ?: ""
            if (csvCache.isNotEmpty()) {
                val lineas = csvCache.split("\n")
                for (linea in lineas) {
                    val partes = linea.split(",")
                    if (partes.isNotEmpty()) {
                        val grupoExcel = partes[0].trim()
                        if (grupoExcel.isNotEmpty()) {
                            listaGrupos.add(grupoExcel)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (listaGrupos.isEmpty()) {
            listaGrupos.add("Seccion Finales linea 4")
        }

        val arrayGrupos = listaGrupos.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayGrupos)
        spinnerUbicaciones.adapter = adapter

        val ubicacionActual = prefs.getString("ubicacion_dispositivo", arrayGrupos[0])
        val indexActual = arrayGrupos.indexOf(ubicacionActual)
        if (indexActual >= 0) spinnerUbicaciones.setSelection(indexActual)

        // 3. Comprobar estado de Device Owner y obtener versión de la app
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
            val esDeviceOwner = dpm.isDeviceOwnerApp(packageName)
            val esAdmin = dpm.isAdminActive(adminComponent)

            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "1.0"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }

            tvEstadoKiosco.text = "• Versión instalada: v$versionName (Code: $versionCode)\n" +
                    "• Modo Kiosco (Device Owner): ${if (esDeviceOwner) "🟢 ACTIVO" else "🔴 INACTIVO"}\n" +
                    "• Administrador Activo: ${if (esAdmin) "🟢 SÍ" else "🔴 NO"}"
        } catch (e: Exception) {
            tvEstadoKiosco.text = "• Estado de seguridad y versión no disponible"
        }

        // 4. Botón Guardar Ubicación
        btnGuardarUbicacion.setOnClickListener {
            val seleccionada = spinnerUbicaciones.selectedItem.toString()
            prefs.edit().putString("ubicacion_dispositivo", seleccionada).apply()
            Toast.makeText(this, "✅ Ubicación cambiada a: $seleccionada", Toast.LENGTH_SHORT).show()
        }

        // 5. Botón Guardar Teléfono IT y PIN
        btnGuardarCredenciales.setOnClickListener {
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                currentFocus?.let { view ->
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            } catch (e: Exception) { e.printStackTrace() }

            val nuevoTel = etTelefonoIT.text.toString().trim()
            val nuevoPin = etNuevoPin.text.toString().trim()

            prefs.edit()
                .putString("telefono_it", nuevoTel)
                .putString("pin_it", if (nuevoPin.isNotEmpty()) nuevoPin else "1234")
                .apply()

            Toast.makeText(this, "✅ Configuración de SMS y PIN guardada correctamente", Toast.LENGTH_SHORT).show()
        }

        // 6. Botón Forzar Sincronización Cloud
        btnTestSincro.setOnClickListener {
            Toast.makeText(this, "🔄 Sincronizando con Google Sheets...", Toast.LENGTH_SHORT).show()
            val client = OkHttpClient()
            val request = Request.Builder().url(URL_GOOGLE_SHEETS_CSV).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { Toast.makeText(this@ItActivity, "❌ Error de red al sincronizar", Toast.LENGTH_LONG).show() }
                }

                override fun onResponse(call: Call, response: Response) {
                    val csvData = response.body?.string()
                    if (!csvData.isNullOrEmpty()) {
                        val fechaActual = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
                        prefs.edit()
                            .putString("csv_cache_data", csvData)
                            .putString("ultima_sincro", fechaActual)
                            .apply()
                        runOnUiThread { Toast.makeText(this@ItActivity, "✅ ¡Sincronización completada! Cierra y abre el panel para refrescar los grupos.", Toast.LENGTH_LONG).show() }
                    } else {
                        runOnUiThread { Toast.makeText(this@ItActivity, "⚠️ Respuesta vacía desde el servidor", Toast.LENGTH_LONG).show() }
                    }
                }
            })
        }

        // 7. Botón Forzar OTA manual
        btnForzarOTA.setOnClickListener {
            Toast.makeText(this, "🔍 Buscando actualizaciones en GitHub...", Toast.LENGTH_SHORT).show()
            val client = OkHttpClient()
            val request = Request.Builder().url(URL_OTA_JSON).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { Toast.makeText(this@ItActivity, "❌ Sin conexión para buscar actualizaciones", Toast.LENGTH_LONG).show() }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val jsonStr = response.body?.string() ?: return
                        val jsonObject = JSONObject(jsonStr)
                        val versionNube = jsonObject.getInt("versionCode")
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        val versionActual = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }

                        runOnUiThread {
                            if (versionNube > versionActual) {
                                Toast.makeText(this@ItActivity, "🚀 ¡Nueva versión disponible (v$versionNube)! Descargando...", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@ItActivity, "✨ El terminal ya está actualizado a la última versión (v$versionActual)", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (ex: Exception) {
                        runOnUiThread { Toast.makeText(this@ItActivity, "⚠️ Error procesando el archivo JSON de versión", Toast.LENGTH_SHORT).show() }
                    }
                }
            })
        }

        // 8. Abrir ajustes generales
        btnAbrirAjustesAndroid.setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        // 9. Cerrar panel IT
        btnCerrarPanelIT.setOnClickListener {
            finish()
        }
    }
}