package de.baschek.gymapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * DATA SAFETY: never add fallbackToDestructiveMigration(). Every schema change
 * bumps [version], commits the exported schema in app/schemas and ships a
 * Migration with a migration test.
 */
@Database(
    entities = [DocumentEntity::class, OutboxEntity::class, SettingEntity::class, TelemetryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GymDatabase : RoomDatabase() {
    abstract fun dao(): GymDao

    companion object {
        const val NAME = "gymapp.db"

        fun build(context: Context): GymDatabase =
            Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        /** Add new migrations here, in order. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}
