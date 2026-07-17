package com.grupotgt.launcherkioscotgt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ItActivity : AppCompatActivity() {

    private var uriLogoSeleccionado: String = ""
    private val PICK_IMAGE_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_it)

        generarCamposLineasDinamicas()
        cargarDatosGuardados()

        findViewById<Button>(R.id.btnCambiarLogo).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, PICK_IMAGE_REQUEST)
        }

        findViewById<Button>(R.id.btnGuardar).setOnClickListener {
            guardarConfiguracion()
            finish()
        }

        findViewById<Button>(R.id.btnVolver).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnSalirQuiosco).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudieron abrir los ajustes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generarCamposLineasDinamicas() {
        val contenedor = findViewById<LinearLayout>(R.id.contenedorLineasDinamicas)

        for (i in 1..50) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val tvInfo = TextView(this).apply {
                text = "L$i: "
                setTypeface(null, android.graphics.Typeface.BOLD) // ¡CORREGIDO!
                layoutParams = LinearLayout.LayoutParams(60, -2)
            }

            val etNombre = EditText(this).apply {
                id = i * 100 + 1
                hint = "Nombre"
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }

            val etNum = EditText(this).apply {
                id = i * 100 + 2
                hint = "Teléfono"
                inputType = InputType.TYPE_CLASS_PHONE
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }

            val swActivo = Switch(this).apply {
                id = i * 100 + 3
                isChecked = i <= 6
            }

            row.addView(tvInfo)
            row.addView(etNombre)
            row.addView(etNum)
            row.addView(swActivo)
            contenedor.addView(row)
        }
    }

    private fun cargarDatosGuardados() {
        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)

        findViewById<EditText>(R.id.etUbicacionDisp)?.setText(prefs.getString("ubicacion_dispositivo", "Seccion Finales linea 4"))
        findViewById<EditText>(R.id.etTelefonoIT)?.setText(prefs.getString("telefono_it", ""))
        uriLogoSeleccionado = prefs.getString("logo_uri_custom", "") ?: ""

        for (i in 1..50) {
            findViewById<EditText>(i * 100 + 1)?.setText(prefs.getString("nombre$i", ""))
            findViewById<EditText>(i * 100 + 2)?.setText(prefs.getString("num$i", ""))
            findViewById<Switch>(i * 100 + 3)?.isChecked = prefs.getBoolean("activa$i", i <= 6)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data) // ¡CORREGIDO!
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            data.data?.let { uri ->
                uriLogoSeleccionado = uri.toString()
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Toast.makeText(this, "Logo seleccionado correctamente", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun guardarConfiguracion() {
        val prefs = getSharedPreferences("ConfigKiosco", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putString("ubicacion_dispositivo", findViewById<EditText>(R.id.etUbicacionDisp)?.text.toString())
        editor.putString("logo_uri_custom", uriLogoSeleccionado)
        editor.putString("telefono_it", findViewById<EditText>(R.id.etTelefonoIT)?.text.toString())

        val editNuevoPin = findViewById<EditText>(R.id.etNuevoPin)
        if (editNuevoPin != null && editNuevoPin.text.toString().isNotEmpty()) {
            editor.putString("pin_it", editNuevoPin.text.toString())
        }

        for (i in 1..50) {
            val nombre = findViewById<EditText>(i * 100 + 1)?.text.toString()
            val numero = findViewById<EditText>(i * 100 + 2)?.text.toString()
            val activa = findViewById<Switch>(i * 100 + 3)?.isChecked ?: (i <= 6)

            editor.putString("nombre$i", nombre)
            editor.putString("num$i", numero)
            editor.putBoolean("activa$i", activa)
        }

        editor.apply()
        Toast.makeText(this, "✅ Configuración industrial actualizada", Toast.LENGTH_SHORT).show()
    }
}