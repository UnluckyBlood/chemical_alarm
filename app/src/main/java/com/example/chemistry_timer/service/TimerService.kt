package com.example.chemistry_timer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.example.chemistry_timer.MainActivity
import com.example.chemistry_timer.R
import com.example.chemistry_timer.data.AppDatabase
import com.example.chemistry_timer.data.TimerEntity
import kotlinx.coroutines.*
import java.util.Locale

class TimerService : Service() {

    companion object {
        const val CHANNEL_ID = "chem_timer_channel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE" // <-- НОВОЕ ДЕЙСТВИЕ
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
        const val EXTRA_TIMER_ID = "EXTRA_TIMER_ID"
        const val NOTIFICATION_ID_BASE = 5000
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeTimers = mutableMapOf<Long, Job>()
    private val timerRemaining = mutableMapOf<Long, Long>()
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        createNotificationChannel(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1)
                if (timerId != -1L) startTimer(timerId)
            }
            ACTION_PAUSE -> {
                val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1)
                if (timerId != -1L) pauseTimer(timerId)
            }
            ACTION_STOP -> {
                val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1)
                if (timerId != -1L) stopTimer(timerId)
            }
            ACTION_STOP_ALL -> stopAllTimers()
        }

        val notification = buildForegroundNotification()
        startForeground(1, notification)
        return START_STICKY
    }

    private fun startTimer(timerId: Long) {
        if (activeTimers.containsKey(timerId)) return

        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            // Если время было на паузе, продолжаем с него. Иначе берем полное время.
            val remaining = if (timer.remainingSeconds > 0) timer.remainingSeconds else timer.totalSeconds
            timerRemaining[timerId] = remaining

            timer.isRunning = true
            timer.remainingSeconds = remaining
            db.timerDao().updateTimer(timer)

            val job = launch(Dispatchers.Default) {
                var sec = remaining
                while (sec > 0) {
                    delay(1000)
                    sec--
                    timerRemaining[timerId] = sec
                    db.timerDao().updateRemainingSeconds(timerId, sec)
                    updateNotification(timerId, timer, sec)
                }
                onTimerFinished(timerId, timer)
            }
            activeTimers[timerId] = job
        }
    }

    // <-- НОВЫЙ МЕТОД ДЛЯ ПАУЗЫ
    private fun pauseTimer(timerId: Long) {
        activeTimers[timerId]?.cancel() // Останавливаем отсчет
        activeTimers.remove(timerId)
        // НЕ сбрасываем timerRemaining и НЕ сбрасываем remainingSeconds в 0!

        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            timer.isRunning = false
            // Оставляем timer.remainingSeconds как есть
            db.timerDao().updateTimer(timer)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID_BASE + timerId.toInt())

        if (activeTimers.isEmpty()) stopSelf()
    }

    private fun stopTimer(timerId: Long) { // Полный сброс
        activeTimers[timerId]?.cancel()
        activeTimers.remove(timerId)
        timerRemaining.remove(timerId)

        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            timer.isRunning = false
            timer.remainingSeconds = 0 // <-- СБРОС ВРЕМЕНИ
            db.timerDao().updateTimer(timer)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID_BASE + timerId.toInt())

        if (activeTimers.isEmpty()) stopSelf()
    }

    private fun stopAllTimers() {
        activeTimers.keys.toList().forEach { stopTimer(it) }
        stopSelf()
    }

    private suspend fun onTimerFinished(timerId: Long, timer: TimerEntity) {
        activeTimers.remove(timerId)
        timerRemaining.remove(timerId)

        timer.isRunning = false
        timer.remainingSeconds = 0
        db.timerDao().updateTimer(timer)

        triggerAlarm(timerId, timer)

        if (activeTimers.isEmpty()) {
            withContext(Dispatchers.Main) { stopSelf() }
        }
    }

    private fun triggerAlarm(timerId: Long, timer: TimerEntity) {
        val nm = getSystemService(NotificationManager::class.java)

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val fullScreenPI = PendingIntent.getActivity(
            this, timerId.toInt() + 1000, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = if (timer.customSoundUri.isNotEmpty()) {
            timer.customSoundUri.toUri()
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }

        createNotificationChannel(soundUri)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("⏰ Таймер #${timer.number} завершён!")
            .setContentText(timer.name.ifEmpty { "Хим. вещество #${timer.number}" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPI, true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500, 1000))
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID_BASE + timerId.toInt(), notification)

        val vibrator = getSystemService(Vibrator::class.java)
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
    }

    private fun updateNotification(timerId: Long, timer: TimerEntity, remaining: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        val timeStr = formatTime(remaining)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Таймер #${timer.number}: $timeStr")
            .setContentText(timer.name.ifEmpty { "Хим. вещество" })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

        nm.notify(NOTIFICATION_ID_BASE + timerId.toInt(), notification)
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("ХимТаймер работает")
            .setContentText("Таймеры активны")
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel(soundUri: Uri) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CHANNEL_ID)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Таймеры химических веществ",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о завершении таймеров"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            setSound(soundUri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        nm.createNotificationChannel(channel)
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}