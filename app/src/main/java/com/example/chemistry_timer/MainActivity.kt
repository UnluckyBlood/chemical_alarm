package com.example.chemistry_timer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        requestNotificationPermission()
        setupRecyclerView()
        setupFab()
        observeTimers()
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
            onPlayClick = { timer -> toggleTimer(timer) }, // Теперь это Пауза/Возобновление
            onStopClick = { timer -> stopTimer(timer) },   // Новая кнопка полного сброса
            onDeleteClick = { timer -> confirmDelete(timer) }
        )

        binding.rvTimers.layoutManager = LinearLayoutManager(this)
        binding.rvTimers.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                // Здесь можно добавить логику перемещения элементов, если нужно
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.rvTimers)
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            lifecycleScope.launch {
                val maxNum = db.timerDao().getMaxNumber() ?: 0
                val newTimer = TimerEntity(number = maxNum + 1, totalSeconds = 300)
                val id = db.timerDao().insertTimer(newTimer)
                openDetail(id)
            }
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

    // ИЗМЕНЕННЫЙ МЕТОД: Теперь это Пауза или Возобновление
    private fun toggleTimer(timer: TimerEntity) {
        val intent = Intent(this, TimerService::class.java).apply {
            putExtra(TimerService.EXTRA_TIMER_ID, timer.id)
        }

        if (timer.isRunning) {
            // Если работает -> ставим на ПАУЗУ (время сохраняется в БД)
            intent.action = TimerService.ACTION_PAUSE
        } else {
            // Если на паузе -> ВОЗОБНОВЛЯЕМ
            if (timer.totalSeconds <= 0 && timer.remainingSeconds <= 0) {
                Toast.makeText(this, "Установите время таймера!", Toast.LENGTH_SHORT).show()
                return
            }
            intent.action = TimerService.ACTION_START
        }

        ContextCompat.startForegroundService(this, intent)

        lifecycleScope.launch {
            val t = db.timerDao().getTimerById(timer.id) ?: return@launch
            t.isRunning = !timer.isRunning
            // ВАЖНО: Мы НЕ сбрасываем remainingSeconds в 0 здесь! 
            // Сервис сам продолжит отсчет с того места, где остановился.
            db.timerDao().updateTimer(t)
        }
    }

    // НОВЫЙ МЕТОД: Полный Стоп (сброс времени)
    private fun stopTimer(timer: TimerEntity) {
        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP
            putExtra(TimerService.EXTRA_TIMER_ID, timer.id)
        }
        ContextCompat.startForegroundService(this, intent)

        lifecycleScope.launch {
            val t = db.timerDao().getTimerById(timer.id) ?: return@launch
            t.isRunning = false
            t.remainingSeconds = 0 // Полный сброс времени
            db.timerDao().updateTimer(t)
        }
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
    }
}