package com.example.chemistry_timer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers ORDER BY number ASC")
    fun getAllTimers(): Flow<List<TimerEntity>>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun getTimerById(id: Long): TimerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimer(timer: TimerEntity): Long

    @Update
    suspend fun updateTimer(timer: TimerEntity)

    @Delete
    suspend fun deleteTimer(timer: TimerEntity)

    @Query("DELETE FROM timers WHERE id = :id")
    suspend fun deleteTimerById(id: Long)

    @Query("SELECT MAX(number) FROM timers")
    suspend fun getMaxNumber(): Int?

    // <-- ДОБАВЛЕНО (без этого сервис падал при обновлении времени)
    @Query("UPDATE timers SET remainingSeconds = :remaining WHERE id = :timerId")
    suspend fun updateRemainingSeconds(timerId: Long, remaining: Long)
}