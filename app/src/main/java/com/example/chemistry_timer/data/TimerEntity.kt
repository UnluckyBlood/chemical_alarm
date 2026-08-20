package com.example.chemistry_timer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timers")
data class TimerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val number: Int,
    var name: String = "",
    var formula: String = "",
    var description: String = "",
    var totalSeconds: Long = 0,
    var remainingSeconds: Long = 0,
    var isRunning: Boolean = false,
    var customSoundUri: String = "", // <-- ДОБАВЛЕНО
    var createdAt: Long = System.currentTimeMillis()
)