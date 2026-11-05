package com.example.rosterrebuild.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "rosters",
    indices = [
        Index("employeeId"),
        Index(value = ["employeeId", "month", "year", "version"], unique = true)
    ]
)
data class RosterEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val month: Int,
    val year: Int,
    val version: Int = 1,
    val status: String = "ACTIVE", // ACTIVE, ARCHIVED
    val uploadTimestamp: Long = System.currentTimeMillis(),
    val originalFileUri: String? = null,
    val originalFilePath: String? = null,
    val workbookFileUri: String? = null,
    val workbookFilePath: String? = null,
    val processingStatus: String = "COMPLETED",
    val floorExtracted: String? = null,
    val currentMonthlyFloor: String? = null,
    val daysCount: Int = 0
) {
    fun getEffectiveMonthlyFloor(): String? = currentMonthlyFloor ?: floorExtracted
}

@Entity(
    tableName = "daily_schedules",
    foreignKeys = [
        ForeignKey(
            entity = RosterEntity::class,
            parentColumns = ["id"],
            childColumns = ["rosterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("rosterId"),
        Index(value = ["rosterId", "day"], unique = true)
    ]
)
data class DailyScheduleEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val rosterId: String,
    val day: Int,
    val date: String?,
    val extractedShiftCode: String,
    val currentShiftCode: String,
    val extractedFloor: String?,
    val currentFloor: String?,
    val isProvisional: Boolean = false
)

@Entity(
    tableName = "attendances",
    indices = [
        Index(value = ["employeeId", "date"], unique = true),
        Index("employeeId")
    ]
)
data class AttendanceEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val date: String, // YYYY-MM-DD
    val status: String, // PRESENT, ABSENT, NOT_MARKED, NOT_APPLICABLE
    val notes: String? = null,
    val isFlaggedForReview: Boolean = false,
    val reviewReason: String? = null,
    val updatedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "shift_change_events",
    foreignKeys = [
        ForeignKey(
            entity = DailyScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("scheduleId"),
        Index("rosterId")
    ]
)
data class ShiftChangeEventEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val scheduleId: String,
    val rosterId: String,
    val day: Int,
    val date: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val previousShiftCode: String?,
    val newShiftCode: String,
    val source: String,
    val author: String = "System",
    val reason: String? = null
)

@Entity(
    tableName = "floor_change_events",
    foreignKeys = [
        ForeignKey(
            entity = DailyScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("scheduleId"),
        Index("rosterId")
    ]
)
data class FloorChangeEventEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val scheduleId: String,
    val rosterId: String,
    val day: Int,
    val date: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val previousFloor: String?,
    val newFloor: String?,
    val author: String = "User"
)

@Entity(
    tableName = "attendance_change_events",
    foreignKeys = [
        ForeignKey(
            entity = AttendanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["attendanceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("attendanceId"),
        Index("employeeId"),
        Index("date")
    ]
)
data class AttendanceChangeEventEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val attendanceId: String,
    val employeeId: String,
    val date: String,
    val timestamp: Long = System.currentTimeMillis(),
    val previousStatus: String?,
    val newStatus: String,
    val author: String = "User",
    val notes: String? = null
)

@Entity(
    tableName = "export_records",
    foreignKeys = [
        ForeignKey(
            entity = RosterEntity::class,
            parentColumns = ["id"],
            childColumns = ["rosterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rosterId")]
)
data class ExportRecordEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val rosterId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val destinationUri: String,
    val status: String = "SUCCESS"
)

@Entity(
    tableName = "bulk_floor_change_events",
    foreignKeys = [
        ForeignKey(
            entity = RosterEntity::class,
            parentColumns = ["id"],
            childColumns = ["rosterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("rosterId")
    ]
)
data class BulkFloorChangeEventEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val rosterId: String,
    val month: Int,
    val year: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val previousFloor: String?,
    val newFloor: String,
    val affectedDaysCount: Int,
    val author: String = "User",
    val description: String = ""
)


