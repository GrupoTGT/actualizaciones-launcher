package com.grupotgt.launcherkioscotgt

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ItActivity : AppCompatActivity() {

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

        // Forzar Sincro con Excel
        btnTestSincro?.setOnClickListener {
            Toast.makeText(this, "🔄 Sincronizando datos con la nube...", Toast.LENGTH_SHORT).show()
            AppLog.registrar("🔄 Forzada sincronización manual desde Panel IT")
        }

        // Ver Logs en pantalla
        btnVerLogsIt?.setOnClickListener {
            mostrarDialogoLogsEnPantalla()
        }

        // Forzar OTA
        btnForzarOTA?.setOnClickListener {
            Toast.makeText(this, "🚀 Verificando actualizaciones en GitHub...", Toast.LENGTH_SHORT).show()
            AppLog.registrar("🚀 Comprobación manual de OTA desde Panel IT")
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
            .show()
    }
}