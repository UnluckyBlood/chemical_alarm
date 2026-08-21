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
import android.widget.Toast
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
        // Два канала: один со звуком (завершение), другой без звука (ход таймера)
        const val CHANNEL_ID = "chem_timer_channel"            // для завершения (звук)
        const val FOREGROUND_CHANNEL_ID = "chem_timer_fg"      // для прогресса и фона (без звука)

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
        const val ACTION_STOP_ALARM = "ACTION_STOP_ALARM"
        const val ACTION_RESET_TIMER = "ACTION_RESET_TIMER"
        const val ACTION_RESET_TO_DEFAULT = "ACTION_RESET_TO_DEFAULT"
        const val EXTRA_TIMER_ID = "EXTRA_TIMER_ID"

        private const val NOTIFICATION_ID_BASE = 1000
        private const val ALARM_NOTIFICATION_ID_BASE = 2000
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeTimers = mutableMapOf<Long, Job>()
    private val timerRemaining = mutableMapOf<Long, Long>()
    private val timerGenerations = mutableMapOf<Long, Int>()
    private lateinit var db: AppDatabase
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        // Создаём оба канала
        createNotificationChannel(alarmUri, CHANNEL_ID, withSound = true)
        createNotificationChannel(null, FOREGROUND_CHANNEL_ID, withSound = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val timerId = intent?.getLongExtra(EXTRA_TIMER_ID, -1) ?: -1L

        if (action != ACTION_STOP_ALL && !isForeground) {
            showTemporaryForegroundNotification(timerId)
        }

        when (action) {
            ACTION_START -> if (timerId != -1L) {
                cleanupTimerState(timerId)
                startTimer(timerId)
            }
            ACTION_PAUSE -> if (timerId != -1L) {
                cleanupTimerState(timerId)
                pauseTimer(timerId)
            }
            ACTION_STOP -> if (timerId != -1L) {
                cleanupTimerState(timerId)
                stopTimer(timerId)
            }
            ACTION_STOP_ALL -> stopAllTimers()
            ACTION_STOP_ALARM -> if (timerId != -1L) stopAlarmOnly(timerId)
            ACTION_RESET_TIMER -> if (timerId != -1L) {
                cleanupTimerState(timerId)
                resetTimer(timerId)
            }
            ACTION_RESET_TO_DEFAULT -> if (timerId != -1L) {
                cleanupTimerState(timerId)
                resetToDefault(timerId)
            }
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun showTemporaryForegroundNotification(timerId: Long) {
        val notificationId = getNotificationId(timerId)
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Таймер")
            .setContentText("Управление таймером #$timerId")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(notificationId, notification)
        isForeground = true
    }

    private fun getNotificationId(timerId: Long) = NOTIFICATION_ID_BASE + timerId.toInt()
    private fun getAlarmNotificationId(timerId: Long) = ALARM_NOTIFICATION_ID_BASE + timerId.toInt()

    private fun cleanupTimerState(timerId: Long) {
        val generation = (timerGenerations[timerId] ?: 0) + 1
        timerGenerations[timerId] = generation

        activeTimers[timerId]?.cancel()
        activeTimers.remove(timerId)
        timerRemaining.remove(timerId)

        // Удаляем все уведомления, чтобы звук точно остановился
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(getNotificationId(timerId))
        nm.cancel(getAlarmNotificationId(timerId))
    }

    // ===================== ОСНОВНАЯ ЛОГИКА =====================

    private fun startTimer(timerId: Long) {
        if (activeTimers.containsKey(timerId)) return

        val generation = (timerGenerations[timerId] ?: 0) + 1
        timerGenerations[timerId] = generation

        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            val remaining = if (timer.remainingSeconds > 0) timer.remainingSeconds else timer.totalSeconds
            if (remaining <= 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TimerService, "Установите время!", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            timerRemaining[timerId] = remaining
            timer.isRunning = true
            timer.remainingSeconds = remaining
            db.timerDao().updateTimer(timer)
            sendBroadcast(Intent("TIMER_UPDATED"))

            updateNotification(timerId, timer, remaining)

            val job = launch(Dispatchers.Default) {
                var sec = remaining
                while (sec > 0) {
                    if (timerGenerations[timerId] != generation) return@launch
                    delay(1000)
                    sec--
                    timerRemaining[timerId] = sec
                    db.timerDao().updateRemainingSeconds(timerId, sec)
                    updateNotification(timerId, timer, sec)
                }
                if (timerGenerations[timerId] == generation && activeTimers[timerId] == coroutineContext[Job]) {
                    onTimerFinished(timerId, timer, generation)
                }
            }
            activeTimers[timerId] = job
        }
    }

    private fun pauseTimer(timerId: Long) {
        val newGen = (timerGenerations[timerId] ?: 0) + 1
        timerGenerations[timerId] = newGen

        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            timer.isRunning = false
            db.timerDao().updateTimer(timer)
            sendBroadcast(Intent("TIMER_UPDATED"))

            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(getNotificationId(timerId))
            nm.cancel(getAlarmNotificationId(timerId))

            if (activeTimers.isEmpty()) {
                stopForeground(true)
                isForeground = false
                stopSelf()
            } else {
                refreshForeground()
            }
        }
    }

    private fun stopTimer(timerId: Long) {
        val newGen = (timerGenerations[timerId] ?: 0) + 1
        timerGenerations[timerId] = newGen

        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            timer.isRunning = false
            timer.remainingSeconds = 0
            db.timerDao().updateTimer(timer)
            sendBroadcast(Intent("TIMER_UPDATED"))

            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(getNotificationId(timerId))
            nm.cancel(getAlarmNotificationId(timerId))

            if (activeTimers.isEmpty()) {
                stopForeground(true)
                isForeground = false
                stopSelf()
            } else {
                refreshForeground()
            }
        }
    }

    private fun stopAllTimers() {
        activeTimers.keys.toList().forEach { stopTimer(it) }
    }

    private suspend fun onTimerFinished(timerId: Long, timer: TimerEntity, generation: Int) {
        if (timerGenerations[timerId] != generation) return
        if (!activeTimers.containsKey(timerId)) return

        activeTimers.remove(timerId)
        timerRemaining.remove(timerId)

        timer.isRunning = false
        timer.remainingSeconds = 0
        db.timerDao().updateTimer(timer)
        sendBroadcast(Intent("TIMER_UPDATED"))

        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(getNotificationId(timerId))

        triggerAlarm(timerId, timer)

        if (activeTimers.isEmpty()) {
            stopForeground(true)
            isForeground = false
            withContext(Dispatchers.Main) { stopSelf() }
        } else {
            refreshForeground()
        }
    }

    // ===================== УВЕДОМЛЕНИЕ О ЗАВЕРШЕНИИ (со звуком) =====================

    private fun triggerAlarm(timerId: Long, timer: TimerEntity) {
        val nm = getSystemService(NotificationManager::class.java)

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_STOP_ALARM
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val contentPI = PendingIntent.getActivity(
            this, timerId.toInt() + 4000, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val fullScreenPI = PendingIntent.getActivity(
            this, timerId.toInt() + 1000, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_RESET_TIMER
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val restartPI = PendingIntent.getService(
            this, timerId.toInt() + 2000, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val stopPI = PendingIntent.getService(
            this, timerId.toInt() + 3000, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = if (timer.customSoundUri.isNotEmpty()) {
            try {
                val uri = timer.customSoundUri.toUri()
                contentResolver.openInputStream(uri)?.close()
                uri
            } catch (e: Exception) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }

        // Убедимся, что канал со звуком настроен с нужным URI
        createNotificationChannel(soundUri, CHANNEL_ID, withSound = true)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("⏰ Таймер #${timer.number} завершён!")
            .setContentText(timer.name.ifEmpty { "Хим. вещество #${timer.number}" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPI)
            .setFullScreenIntent(fullScreenPI, true)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_refresh, "Перезапуск", restartPI)
            .addAction(R.drawable.ic_stop_red, "Стоп", stopPI)
            .build()

        nm.notify(getAlarmNotificationId(timerId), notification)

        // Вибрация (оставляем)
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
    }

    // ===================== ОБРАБОТЧИКИ КНОПОК =====================

    private fun stopAlarmOnly(timerId: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(getAlarmNotificationId(timerId))

        if (activeTimers.isEmpty()) {
            stopForeground(true)
            isForeground = false
            stopSelf()
        }
    }

    private fun resetTimer(timerId: Long) {
        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            timer.isRunning = true
            timer.remainingSeconds = timer.totalSeconds
            db.timerDao().updateTimer(timer)
            sendBroadcast(Intent("TIMER_UPDATED"))
            startTimer(timerId)
        }
    }

    private fun resetToDefault(timerId: Long) {
        scope.launch {
            val timer = db.timerDao().getTimerById(timerId) ?: return@launch
            timer.isRunning = false
            timer.remainingSeconds = timer.totalSeconds
            db.timerDao().updateTimer(timer)
            sendBroadcast(Intent("TIMER_UPDATED"))

            if (activeTimers.isEmpty()) {
                stopForeground(true)
                isForeground = false
                stopSelf()
            } else {
                refreshForeground()
            }
        }
    }

    // ===================== FOREGROUND УВЕДОМЛЕНИЯ (без звука) =====================

    private fun refreshForeground() {
        if (activeTimers.isEmpty()) {
            stopForeground(true)
            isForeground = false
            return
        }

        val primaryId = activeTimers.keys.min()
        scope.launch {
            val timer = db.timerDao().getTimerById(primaryId) ?: return@launch
            val remaining = timerRemaining[primaryId] ?: timer.remainingSeconds
            updateNotification(primaryId, timer, remaining)
        }
    }

    private fun updateNotification(timerId: Long, timer: TimerEntity, remaining: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        val timeStr = formatTime(remaining)
        val notificationId = getNotificationId(timerId)

        val pauseIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_PAUSE
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val pausePI = PendingIntent.getService(
            this, timerId.toInt() + 5000, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val stopPI = PendingIntent.getService(
            this, timerId.toInt() + 6000, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_RESET_TIMER
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val restartPI = PendingIntent.getService(
            this, timerId.toInt() + 7000, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Используем БЕСШУМНЫЙ канал
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Таймер #${timer.number}: $timeStr")
            .setContentText(timer.name.ifEmpty { "Хим. вещество" })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)   // дополнительная гарантия
            .addAction(R.drawable.ic_pause, "Пауза", pausePI)
            .addAction(R.drawable.ic_stop_red, "Стоп", stopPI)
            .addAction(R.drawable.ic_refresh, "Перезапуск", restartPI)
            .build()

        val isPrimary = activeTimers.keys.minOrNull() == timerId

        if (isPrimary) {
            if (!isForeground) {
                startForeground(notificationId, notification)
                isForeground = true
            } else {
                nm.notify(notificationId, notification)
                startForeground(notificationId, notification)
            }
        } else {
            nm.notify(notificationId, notification)
        }
    }

    // ===================== КАНАЛЫ УВЕДОМЛЕНИЙ =====================

    private fun createNotificationChannel(soundUri: Uri?, channelId: String, withSound: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val importance = if (withSound) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(
            channelId,
            if (withSound) "Таймеры химических веществ" else "Сервис ХимТаймер",
            importance
        ).apply {
            description = if (withSound) "Уведомления о завершении таймеров" else "Информирование о работе сервиса"
            enableVibration(withSound)
            if (withSound) {
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(soundUri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            } else {
                setSound(null, null)
            }
        }
        nm.createNotificationChannel(channel)
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}