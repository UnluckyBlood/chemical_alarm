package com.example.chemistry_timer

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.chemistry_timer.data.AppDatabase
import com.example.chemistry_timer.data.TimerEntity
import com.example.chemistry_timer.databinding.ActivityTimerDetailBinding
import com.example.chemistry_timer.util.ChemicalFormulaHelper
import kotlinx.coroutines.launch

class TimerDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimerDetailBinding
    private lateinit var db: AppDatabase
    private var timerId: Long = -1
    private var currentTimer: TimerEntity? = null

    // Переменная для хранения выбранного звука
    private var selectedSoundUri: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimerDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        timerId = intent.getLongExtra("TIMER_ID", -1)

        setupNumberPickers()
        setupFormulaPreview()
        setupToolbar()
        setupSaveButton()
        setupSoundPicker() // <-- Инициализация выбора звука
        loadTimer()
    }

    private fun setupToolbar() {
        binding.toolbarDetail.setNavigationOnClickListener { finish() }
    }

    private fun setupNumberPickers() {
        binding.npHours.apply {
            minValue = 0
            maxValue = 99
            value = 0
            setWrapSelectorWheel(false)
        }
        binding.npMinutes.apply {
            minValue = 0
            maxValue = 59
            value = 5
            setWrapSelectorWheel(true)
        }
        binding.npSeconds.apply {
            minValue = 0
            maxValue = 59
            value = 0
            setWrapSelectorWheel(true)
        }
    }

    private fun setupFormulaPreview() {
        binding.etFormula.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                if (text.isNotEmpty()) {
                    binding.tvFormulaPreview.text = ChemicalFormulaHelper.parseFormula(text)
                } else {
                    binding.tvFormulaPreview.text = ""
                }
            }
        })
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            saveTimer()
        }
    }

    // Метод для выбора звука
    private fun setupSoundPicker() {
        binding.btnSelectSound.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            soundPickerLauncher.launch(intent)
        }
    }

    // Лаунчер для получения результата выбора файла
    private val soundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedSoundUri = uri.toString()
                binding.tvSelectedSound.text = "Звук выбран: ${uri.lastPathSegment ?: "файл"}"
            }
        }
    }

    private fun loadTimer() {
        if (timerId == -1L) return

        lifecycleScope.launch {
            currentTimer = db.timerDao().getTimerById(timerId)
            currentTimer?.let { timer ->
                binding.etNumber.setText(timer.number.toString())
                binding.etName.setText(timer.name)
                binding.etFormula.setText(timer.formula)
                binding.etDescription.setText(timer.description)

                // Запоминаем текущий звук, если он был сохранен ранее
                selectedSoundUri = timer.customSoundUri
                if (selectedSoundUri.isNotEmpty()) {
                    binding.tvSelectedSound.text = "Звук выбран: ${selectedSoundUri.substringAfterLast("/")}"
                }

                val total = timer.totalSeconds
                val h = (total / 3600).toInt()
                val m = ((total % 3600) / 60).toInt()
                val s = (total % 60).toInt()
                binding.npHours.value = h
                binding.npMinutes.value = m
                binding.npSeconds.value = s

                binding.toolbarDetail.title = "Таймер #${timer.number}"
            }
        }
    }

    private fun saveTimer() {
        val number = binding.etNumber.text.toString().toIntOrNull() ?: 1
        val name = binding.etName.text.toString().trim()
        val formula = binding.etFormula.text.toString().trim()
        val description = binding.etDescription.text.toString().trim()
        val hours = binding.npHours.value.toLong()
        val minutes = binding.npMinutes.value.toLong()
        val seconds = binding.npSeconds.value.toLong()
        val totalSeconds = hours * 3600 + minutes * 60 + seconds

        if (totalSeconds <= 0) {
            Toast.makeText(this, "Установите время больше 0!", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val timer = currentTimer?.copy(
                number = number,
                name = name,
                formula = formula,
                description = description,
                totalSeconds = totalSeconds,
                remainingSeconds = totalSeconds,
                customSoundUri = selectedSoundUri // <-- Сохраняем выбранный звук
            ) ?: TimerEntity(
                number = number,
                name = name,
                formula = formula,
                description = description,
                totalSeconds = totalSeconds,
                remainingSeconds = totalSeconds,
                customSoundUri = selectedSoundUri // <-- Сохраняем выбранный звук
            )

            db.timerDao().insertTimer(timer)
            Toast.makeText(this@TimerDetailActivity, "Сохранено!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}