package com.grupotgt.launcherkioscotgt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.grupotgt.launcherkioscotgt.mdm.MdmHeartbeatScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MdmHeartbeatScheduler.schedule(context)
            val launchIntent = Intent(context, MainActivity::class.java)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }
}
