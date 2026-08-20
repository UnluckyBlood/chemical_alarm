package com.example.chemistry_timer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TimerEntity::class],
    version = 2, // <-- БЫЛО 1, СТАЛО 2
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timerDao(): TimerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chem_timer_db"
                )
                    .fallbackToDestructiveMigration() // <-- ДОБАВЛЕНО (позволяет обновить БД без краша)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}