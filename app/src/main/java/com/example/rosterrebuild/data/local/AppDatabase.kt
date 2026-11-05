package com.example.rosterrebuild.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RosterEntity::class,
        DailyScheduleEntity::class,
        AttendanceEntity::class,
        ShiftChangeEventEntity::class,
        FloorChangeEventEntity::class,
        AttendanceChangeEventEntity::class,
        ExportRecordEntity::class,
        BulkFloorChangeEventEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rosterDao(): RosterDao
    abstract fun dailyScheduleDao(): DailyScheduleDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun exportRecordDao(): ExportRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rosters ADD COLUMN currentMonthlyFloor TEXT DEFAULT NULL")
                db.execSQL("UPDATE rosters SET currentMonthlyFloor = floorExtracted WHERE currentMonthlyFloor IS NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bulk_floor_change_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        rosterId TEXT NOT NULL,
                        month INTEGER NOT NULL,
                        year INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        previousFloor TEXT,
                        newFloor TEXT NOT NULL,
                        affectedDaysCount INTEGER NOT NULL,
                        author TEXT NOT NULL,
                        description TEXT NOT NULL,
                        FOREIGN KEY (rosterId) REFERENCES rosters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bulk_floor_change_events_rosterId ON bulk_floor_change_events (rosterId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medical_roster_database.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

