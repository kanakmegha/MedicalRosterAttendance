package com.example.rosterrebuild.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RosterDao {
    @Query("SELECT * FROM rosters ORDER BY year DESC, month DESC, version DESC")
    fun getAllRostersFlow(): Flow<List<RosterEntity>>

    @Query("SELECT * FROM rosters ORDER BY year DESC, month DESC, version DESC")
    suspend fun getAllRosters(): List<RosterEntity>

    @Query("SELECT * FROM rosters WHERE status = 'ACTIVE' ORDER BY year DESC, month DESC, version DESC")
    fun getActiveRostersFlow(): Flow<List<RosterEntity>>

    @Query("SELECT * FROM rosters WHERE id = :id LIMIT 1")
    suspend fun getRosterById(id: String): RosterEntity?

    @Query("SELECT * FROM rosters WHERE employeeId = :employeeId ORDER BY year DESC, month DESC, version DESC")
    suspend fun getRostersForEmployee(employeeId: String): List<RosterEntity>

    @Query("SELECT * FROM rosters WHERE employeeId = :employeeId AND month = :month AND year = :year ORDER BY version DESC")
    suspend fun getRostersForPeriod(employeeId: String, month: Int, year: Int): List<RosterEntity>

    @Query("SELECT * FROM rosters WHERE employeeId = :employeeId AND status = 'ACTIVE' AND (year < :currentYear OR (year = :currentYear AND month < :currentMonth))")
    suspend fun getExpiredRosters(employeeId: String, currentMonth: Int, currentYear: Int): List<RosterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoster(roster: RosterEntity)

    @Update
    suspend fun updateRoster(roster: RosterEntity)

    @Query("UPDATE rosters SET status = :status WHERE id = :id")
    suspend fun updateRosterStatus(id: String, status: String)

    @Query("UPDATE rosters SET status = :status WHERE employeeId = :employeeId AND (month != :excludeMonth OR year != :excludeYear)")
    suspend fun updateStatusForOtherMonths(employeeId: String, excludeMonth: Int, excludeYear: Int, status: String)

    @Query("UPDATE rosters SET status = 'ARCHIVED' WHERE employeeId = :employeeId AND month = :month AND year = :year AND id != :excludeRosterId")
    suspend fun archiveOlderVersions(employeeId: String, month: Int, year: Int, excludeRosterId: String)

    @Query("UPDATE rosters SET currentMonthlyFloor = :floor WHERE id = :id")
    suspend fun updateCurrentMonthlyFloor(id: String, floor: String)

    @Query("DELETE FROM rosters WHERE id = :id")
    suspend fun deleteRoster(id: String)
}

@Dao
interface DailyScheduleDao {
    @Query("SELECT * FROM daily_schedules WHERE rosterId = :rosterId ORDER BY day ASC")
    suspend fun getSchedulesForRoster(rosterId: String): List<DailyScheduleEntity>

    @Query("SELECT * FROM daily_schedules WHERE rosterId = :rosterId ORDER BY day ASC")
    fun getSchedulesForRosterFlow(rosterId: String): Flow<List<DailyScheduleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedules(schedules: List<DailyScheduleEntity>)

    @Update
    suspend fun updateSchedule(schedule: DailyScheduleEntity)

    @Query("UPDATE daily_schedules SET currentFloor = :floor WHERE rosterId = :rosterId")
    suspend fun updateAllFloorsForRoster(rosterId: String, floor: String)
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendances WHERE employeeId = :employeeId AND date = :date LIMIT 1")
    suspend fun getAttendanceForEmployeeAndDate(employeeId: String, date: String): AttendanceEntity?

    @Query("SELECT * FROM attendances WHERE employeeId = :employeeId")
    suspend fun getAttendanceForEmployee(employeeId: String): List<AttendanceEntity>

    @Query("SELECT * FROM attendances WHERE employeeId = :employeeId")
    fun getAttendanceForEmployeeFlow(employeeId: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendances WHERE employeeId = :employeeId AND date IN (:dates)")
    suspend fun getAttendancesForDates(employeeId: String, dates: List<String>): List<AttendanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAttendance(attendance: AttendanceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAttendances(attendances: List<AttendanceEntity>)
}

@Dao
interface AuditEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShiftChange(event: ShiftChangeEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShiftChanges(events: List<ShiftChangeEventEntity>)

    @Query("SELECT * FROM shift_change_events WHERE rosterId = :rosterId ORDER BY timestamp ASC")
    suspend fun getShiftChangesForRoster(rosterId: String): List<ShiftChangeEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFloorChange(event: FloorChangeEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFloorChanges(events: List<FloorChangeEventEntity>)

    @Query("SELECT * FROM floor_change_events WHERE rosterId = :rosterId ORDER BY timestamp ASC")
    suspend fun getFloorChangesForRoster(rosterId: String): List<FloorChangeEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBulkFloorChange(event: BulkFloorChangeEventEntity)

    @Query("SELECT * FROM bulk_floor_change_events WHERE rosterId = :rosterId ORDER BY timestamp ASC")
    suspend fun getBulkFloorChangesForRoster(rosterId: String): List<BulkFloorChangeEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendanceChange(event: AttendanceChangeEventEntity)

    @Query("SELECT * FROM attendance_change_events WHERE employeeId = :employeeId ORDER BY timestamp ASC")
    suspend fun getAttendanceChangesForEmployee(employeeId: String): List<AttendanceChangeEventEntity>
}

@Dao
interface ExportRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportRecord(record: ExportRecordEntity)

    @Query("SELECT * FROM export_records WHERE rosterId = :rosterId ORDER BY timestamp DESC")
    suspend fun getExportsForRoster(rosterId: String): List<ExportRecordEntity>
}

