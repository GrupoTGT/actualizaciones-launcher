package com.grupotgt.launcherkioscotgt

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class MyAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "🔒 Modo Administrador TGT Activado", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "🔓 Modo Administrador TGT Desactivado", Toast.LENGTH_SHORT).show()
    }
}