package com.example.chemistry_timer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.chemistry_timer.service.TimerService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtras(intent)
        }
        context.startForegroundService(serviceIntent)
    }
}