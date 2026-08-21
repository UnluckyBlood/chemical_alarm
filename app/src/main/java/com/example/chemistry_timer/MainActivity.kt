package com.example.chemistry_timer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chemistry_timer.adapter.TimerAdapter
import com.example.chemistry_timer.data.AppDatabase
import com.example.chemistry_timer.data.TimerEntity
import com.example.chemistry_timer.databinding.ActivityMainBinding
import com.example.chemistry_timer.service.TimerService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: TimerAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Без разрешения уведомления не будут показаны", Toast.LENGTH_LONG).show()
        }
    }

    private val timerUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            observeTimers()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        requestNotificationPermission()
        setupRecyclerView()
        observeTimers()

        handleAlarmStopIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleAlarmStopIntent(intent)
    }

    private fun handleAlarmStopIntent(intent: Intent?) {
        if (intent?.action == TimerService.ACTION_STOP_ALARM) {
            val timerId = intent.getLongExtra(TimerService.EXTRA_TIMER_ID, -1)
            if (timerId != -1L) {
                val stopIntent = Intent(this, TimerService::class.java).apply {
                    action = TimerService.ACTION_STOP_ALARM
                    putExtra(TimerService.EXTRA_TIMER_ID, timerId)
                }
                ContextCompat.startForegroundService(this, stopIntent)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = TimerAdapter(
            onItemClick = { timer -> openDetail(timer.id) },
            onPlayClick = { timer -> toggleTimer(timer) },
            onStopClick = { timer -> stopTimer(timer) },
            onResetClick = { timer -> resetTimerToDefault(timer) },
            onDeleteClick = { timer -> confirmDelete(timer) },
            onNewTimerClick = { createNewTimer() }
        )

        binding.rvTimers.layoutManager = LinearLayoutManager(this)
        binding.rvTimers.adapter = adapter
    }

    private fun createNewTimer() {
        lifecycleScope.launch {
            val maxNum = db.timerDao().getMaxNumber() ?: 0
            val newTimer = TimerEntity(number = maxNum + 1, totalSeconds = 300)
            val id = db.timerDao().insertTimer(newTimer)
            openDetail(id)
        }
    }

    private fun observeTimers() {
        lifecycleScope.launch {
            db.timerDao().getAllTimers().collectLatest { list ->
                adapter.submitList(list)
            }
        }
    }

    private fun openDetail(timerId: Long) {
        val intent = Intent(this, TimerDetailActivity::class.java).apply {
            putExtra("TIMER_ID", timerId)
        }
        startActivity(intent)
    }

    private fun toggleTimer(timer: TimerEntity) {
        // Только отправляем команду, БД обновит сервис
        val intent = Intent(this, TimerService::class.java).apply {
            putExtra(TimerService.EXTRA_TIMER_ID, timer.id)
            action = if (timer.isRunning) TimerService.ACTION_PAUSE else TimerService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTimer(timer: TimerEntity) {
        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP
            putExtra(TimerService.EXTRA_TIMER_ID, timer.id)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun resetTimerToDefault(timer: TimerEntity) {
        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_RESET_TO_DEFAULT
            putExtra(TimerService.EXTRA_TIMER_ID, timer.id)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun confirmDelete(timer: TimerEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить таймер #${timer.number}?")
            .setMessage(timer.name.ifEmpty { "Это действие необратимо." })
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    if (timer.isRunning) {
                        val stopIntent = Intent(this@MainActivity, TimerService::class.java).apply {
                            action = TimerService.ACTION_STOP
                            putExtra(TimerService.EXTRA_TIMER_ID, timer.id)
                        }
                        startService(stopIntent)
                    }
                    db.timerDao().deleteTimer(timer)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        observeTimers()
        ContextCompat.registerReceiver(
            this,
            timerUpdateReceiver,
            IntentFilter("TIMER_UPDATED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(timerUpdateReceiver)
        } catch (_: Exception) { }
    }
}