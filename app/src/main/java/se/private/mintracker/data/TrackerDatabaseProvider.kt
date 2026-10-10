package se.oxelv.mintracker.data

import android.content.Context
import androidx.room.Room

object TrackerDatabaseProvider {
    @Volatile private var instance: TrackerDatabase? = null

    fun get(context: Context): TrackerDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, "tracker.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .build()
            .also { instance = it }
    }
}
