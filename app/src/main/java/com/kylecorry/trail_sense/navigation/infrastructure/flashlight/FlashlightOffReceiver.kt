package com.kylecorry.trail_sense.navigation.infrastructure.flashlight

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FlashlightOffReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        context.stopService(FlashlightService.intent(context))
        context.stopService(SosService.intent(context))
    }


    companion object {

        val PI_ID = 38095822

        fun intent(context: Context): Intent {
            return Intent(context, FlashlightOffReceiver::class.java)
        }

        fun pendingIntent(context: Context): PendingIntent {
            return PendingIntent.getBroadcast(context, PI_ID, intent(context), PendingIntent.FLAG_CANCEL_CURRENT)
        }
    }
}