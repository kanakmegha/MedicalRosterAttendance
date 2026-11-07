package com.example.rosterrebuild.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.room.withTransaction
import com.example.rosterrebuild.*
import com.example.rosterrebuild.data.export.ExcelGenerator
import com.example.rosterrebuild.data.local.*
import com.example.rosterrebuild.data.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class RosterRepository(
    private val database: AppDatabase,
    private val storageManager: StorageManager,
    private var clock: Clock = Clock.systemDefaultZone()
) {
    private val rosterDao = database.rosterDao()
    private val scheduleDao = database.dailyScheduleDao()
    private val attendanceDao = database.attendanceDao()
    private val auditDao = database.auditEventDao()
    private val exportDao = database.exportRecordDao()

    fun setClock(clock: Clock) {
        this.clock = clock
    }

    fun getClock(): Clock = clock

    fun getAllRostersFlow(): Flow<List<RosterEntity>> = rosterDao.getAllRostersFlow()

    suspend fun getAllRosters(): List<RosterEntity> = withContext(Dispatchers.IO) {
        rosterDao.getAllRosters()
    }

    suspend fun getRostersForEmployee(employeeId: String): List<RosterEntity> = withContext(Dispatchers.IO) {
        rosterDao.getRostersForEmployee(employeeId)
    }

    fun getActiveRostersFlow(): Flow<List<RosterEntity>> = rosterDao.getActiveRostersFlow()

    suspend fun getRoster(id: String): RosterEntity? = withContext(Dispatchers.IO) {
        rosterDao.getRosterById(id)
    }

    suspend fun deleteRoster(id: String) = withContext(Dispatchers.IO) {
        val roster = rosterDao.getRosterById(id)
        if (roster != null) {
            val dir = storageManager.getRosterDirectory(roster.employeeId, roster.id)
            dir.deleteRecursively()
            database.withTransaction {
                rosterDao.deleteRoster(id)
                // Recalculate remaining rosters for this employee
                val remaining = rosterDao.getRostersForEmployee(roster.employeeId)
                val statusMap = RosterStatusPolicy.computeStatuses(remaining, YearMonth.now(clock))
                for (r in remaining) {
                    val target = statusMap[r.id]
                    if (target != null && target != r.status) {
                        rosterDao.updateRosterStatus(r.id, target)
                    }
                }
            }
        }
    }

    suspend fun getExpiredRosters(employeeId: String): List<RosterEntity> = withContext(Dispatchers.IO) {
        val now = LocalDate.now(clock)
        rosterDao.getExpiredRosters(employeeId, now.monthValue, now.year)
    }

    /**
     * Deterministically recalculates and synchronizes the statuses (ACTIVE, UPCOMING, ARCHIVED)
     * of all rosters across all employees based on chronological month/year and versioning rules.
     */
    suspend fun recalculateAllRosterStatuses(
        referenceYearMonth: YearMonth = YearMonth.now(clock)
    ): Unit = withContext(Dispatchers.IO) {
        val allRosters = rosterDao.getAllRosters()
        val statusMap = RosterStatusPolicy.computeAllStatuses(allRosters, referenceYearMonth)
        database.withTransaction {
            for (r in allRosters) {
                val target = statusMap[r.id]
                if (target != null && target != r.status) {
                    rosterDao.updateRosterStatus(r.id, target)
                }
            }
        }
    }

    /**
     * Deterministically recalculates and synchronizes the statuses of all rosters for a specific employee.
     */
    suspend fun recalculateRosterStatusesForEmployee(
        employeeId: String,
        referenceYearMonth: YearMonth = YearMonth.now(clock)
    ): Unit = withContext(Dispatchers.IO) {
        val empRosters = rosterDao.getRostersForEmployee(employeeId)
        val statusMap = RosterStatusPolicy.computeStatuses(empRosters, referenceYearMonth)
        database.withTransaction {
            for (r in empRosters) {
                val target = statusMap[r.id]
                if (target != null && target != r.status) {
                    rosterDao.updateRosterStatus(r.id, target)
                }
            }
        }
    }

    /**
     * Saves an extracted roster into the database and generates its local Excel workbook.
     *
     * Ensures strict parent-child insertion order inside an atomic Room database transaction:
     * 1. Generate one unique rosterId.
     * 2. Insert parent RosterEntity FIRST.
     * 3. Confirm that the parent roster exists in the database.
     * 4. Insert DailyScheduleEntity records using exactly the same rosterId.
     * 5. Insert related attendance and audit records after parent records exist.
     * 6. Entire logical save executes in a Room transaction with rollback on failure.
     */
    suspend fun saveExtractedRoster(
        employeeId: String,
        month: Int,
        year: Int,
        rosterResult: RosterResult,
        sourceBitmap: Bitmap? = null,
        existingRosterId: String? = null,
        canonicalImagePath: String? = null,
        confirmedFloor: String? = null,
        floorAuthor: String = "User",
        floorReason: String? = null
    ): RosterEntity = withContext(Dispatchers.IO) {
        // Step 1: Determine version
        val existingPeriodRosters = rosterDao.getRostersForPeriod(employeeId, month, year)
        val version = if (existingPeriodRosters.isEmpty()) 1 else (existingPeriodRosters.maxOf { it.version } + 1)
        val rosterId = existingRosterId ?: UUID.randomUUID().toString()

        android.util.Log.d("ROSTER_DB", "Starting saveExtractedRoster for emp=$employeeId period=$month/$year v=$version rosterId=$rosterId")

        // If older version exists, compare for discrepancies
        val previousRoster = existingPeriodRosters.maxByOrNull { it.version }
        val previousSchedules = previousRoster?.let { scheduleDao.getSchedulesForRoster(it.id).associateBy { s -> s.day } } ?: emptyMap()

        // Step 2: Reuse existing canonical image file if provided, avoiding duplicate re-compression
        var imageFilePath: String? = null
        var imageFile: File? = null
        if (!canonicalImagePath.isNullOrBlank()) {
            val candidate = File(canonicalImagePath)
            if (candidate.exists()) {
                imageFile = candidate
                imageFilePath = candidate.absolutePath
                android.util.Log.d("ROSTER_DB", "Reusing canonical stored roster image at: $imageFilePath")
            }
        }

        // Fallback: only if canonical image was not provided, save from in-memory bitmap
        if (imageFile == null && sourceBitmap != null) {
            try {
                imageFile = storageManager.saveOriginalImage(sourceBitmap, employeeId, rosterId)
                imageFilePath = imageFile.absolutePath
                android.util.Log.d("ROSTER_DB", "Saved fallback roster image to: $imageFilePath")
            } catch (e: Exception) {
                android.util.Log.e("ROSTER_DB", "Failed to save fallback image file", e)
            }
        }

        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
        val ocrMonthlyFloor = rosterResult.diagnostics.extractedMonthlyFloor ?: rosterResult.extractedFloor
        val effectiveMonthlyFloor = confirmedFloor?.trim()?.ifBlank { null } ?: ocrMonthlyFloor
        val isUserFloorCorrection = !confirmedFloor.isNullOrBlank() && confirmedFloor.trim() != (ocrMonthlyFloor ?: "")

        val validSchedule = rosterResult.schedule.filter { it.day in 1..daysInMonth }

        val scheduleEntities = mutableListOf<DailyScheduleEntity>()
        val shiftChangeEntities = mutableListOf<ShiftChangeEventEntity>()
        val floorChangeEntities = mutableListOf<FloorChangeEventEntity>()
        val bulkFloorEvents = mutableListOf<BulkFloorChangeEventEntity>()
        val attendancesToSave = mutableListOf<AttendanceEntity>()
        val domainScheduleEntries = mutableListOf<DailyScheduleEntry>()

        validSchedule.forEach { entry ->
            val scheduleId = UUID.randomUUID().toString()
            val prevSchedule = previousSchedules[entry.day]
            val entryDate = "%04d-%02d-%02d".format(year, month, entry.day)
            val dailyFloor = if (isUserFloorCorrection) effectiveMonthlyFloor else (entry.currentFloor ?: effectiveMonthlyFloor)

            // Check if shift or floor changed from previous version
            val shiftChangedFromPrev = prevSchedule != null && prevSchedule.currentShiftCode != entry.currentShiftCode
            val floorChangedFromPrev = prevSchedule != null && (prevSchedule.currentFloor ?: "") != (dailyFloor ?: "")

            val scheduleEntity = DailyScheduleEntity(
                id = scheduleId,
                rosterId = rosterId,
                day = entry.day,
                date = entryDate,
                extractedShiftCode = entry.extractedShiftCode,
                currentShiftCode = entry.currentShiftCode,
                extractedFloor = entry.extractedFloor ?: ocrMonthlyFloor,
                currentFloor = dailyFloor,
                isProvisional = entry.isProvisional
            )
            scheduleEntities.add(scheduleEntity)

            // Initial extraction shift audit event (references scheduleId)
            val shiftAudit = ShiftChangeEventEntity(
                id = UUID.randomUUID().toString(),
                scheduleId = scheduleId,
                rosterId = rosterId,
                day = entry.day,
                date = entryDate,
                timestamp = System.currentTimeMillis(),
                previousShiftCode = prevSchedule?.currentShiftCode,
                newShiftCode = entry.currentShiftCode,
                source = if (prevSchedule != null) "Roster Version v$version Update" else ShiftChangeSource.INITIAL_EXTRACTION.displayName,
                author = "RosterProcessor",
                reason = if (shiftChangedFromPrev) "Shift revised in v$version (was '${prevSchedule?.currentShiftCode}')" else "Extracted from roster image"
            )
            shiftChangeEntities.add(shiftAudit)

            // Floor audit event if extracted or corrected (references scheduleId)
            if (!dailyFloor.isNullOrBlank()) {
                val previousFloorVal = if (isUserFloorCorrection) {
                    entry.extractedFloor ?: ocrMonthlyFloor
                } else {
                    prevSchedule?.currentFloor
                }
                val floorAudit = FloorChangeEventEntity(
                    id = UUID.randomUUID().toString(),
                    scheduleId = scheduleId,
                    rosterId = rosterId,
                    day = entry.day,
                    date = entryDate,
                    timestamp = System.currentTimeMillis(),
                    previousFloor = previousFloorVal,
                    newFloor = dailyFloor,
                    author = if (isUserFloorCorrection) floorAuthor else "RosterProcessor"
                )
                floorChangeEntities.add(floorAudit)
            }

            // Attendance handling: preserve if already recorded for this date
            val existingAttendance = entryDate.let { attendanceDao.getAttendanceForEmployeeAndDate(employeeId, it) }
            val finalAttendance = if (existingAttendance != null) {
                // Preserve existing recorded attendance
                if ((shiftChangedFromPrev || floorChangedFromPrev) &&
                    (existingAttendance.status == AttendanceStatus.PRESENT.name || existingAttendance.status == AttendanceStatus.ABSENT.name)) {
                    val reason = if (shiftChangedFromPrev) {
                        "Roster v$version changed shift from '${prevSchedule?.currentShiftCode}' to '${entry.currentShiftCode}'"
                    } else {
                        "Roster v$version changed floor from '${prevSchedule?.currentFloor}' to '${dailyFloor}'"
                    }
                    existingAttendance.copy(
                        isFlaggedForReview = true,
                        reviewReason = reason,
                        updatedTimestamp = System.currentTimeMillis()
                    )
                } else {
                    existingAttendance
                }
            } else {
                AttendanceEntity(
                    id = UUID.randomUUID().toString(),
                    employeeId = employeeId,
                    date = entryDate,
                    status = AttendanceStatus.defaultForShift(entry.currentShift).name,
                    notes = null,
                    isFlaggedForReview = false,
                    reviewReason = null
                )
            }
            attendancesToSave.add(finalAttendance)

            // Convert to domain model for Excel generation
            val domainAttendance = AttendanceRecord(
                status = AttendanceStatus.valueOf(finalAttendance.status),
                notes = finalAttendance.notes,
                isFlaggedForReview = finalAttendance.isFlaggedForReview,
                reviewReason = finalAttendance.reviewReason
            )
            val domainEntry = entry.copy(
                date = entryDate,
                currentFloor = dailyFloor,
                extractedFloor = entry.extractedFloor ?: ocrMonthlyFloor,
                attendance = domainAttendance
            )
            domainScheduleEntries.add(domainEntry)
        }

        if (isUserFloorCorrection && effectiveMonthlyFloor != null) {
            bulkFloorEvents.add(
                BulkFloorChangeEventEntity(
                    rosterId = rosterId,
                    month = month,
                    year = year,
                    timestamp = System.currentTimeMillis(),
                    previousFloor = ocrMonthlyFloor,
                    newFloor = effectiveMonthlyFloor,
                    affectedDaysCount = scheduleEntities.size,
                    author = floorAuthor,
                    description = floorReason?.takeIf { it.isNotBlank() }
                        ?: "Initial upload floor confirmed/corrected: '${ocrMonthlyFloor ?: "Unassigned"}' -> '$effectiveMonthlyFloor'"
                )
            )
        }

        // Step 3: Generate genuine .xlsx file in app storage (non-database IO)
        val workbookFile = storageManager.getWorkbookFile(employeeId, rosterId, month, year, version)
        try {
            FileOutputStream(workbookFile).use { out ->
                ExcelGenerator.generateWorkbook(
                    roster = RosterEntity(
                        id = rosterId,
                        employeeId = employeeId,
                        month = month,
                        year = year,
                        version = version,
                        status = "ACTIVE",
                        floorExtracted = ocrMonthlyFloor,
                        currentMonthlyFloor = effectiveMonthlyFloor,
                        daysCount = scheduleEntities.size
                    ),
                    scheduleEntries = domainScheduleEntries,
                    shiftChanges = shiftChangeEntities,
                    floorChanges = floorChangeEntities,
                    attendanceChanges = emptyList(),
                    outputStream = out
                )
            }
            android.util.Log.d("ROSTER_DB", "Generated genuine XLSX workbook at: ${workbookFile.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("ROSTER_DB", "Failed to generate initial Excel workbook", e)
        }

        val provisionalRoster = RosterEntity(
            id = rosterId,
            employeeId = employeeId,
            month = month,
            year = year,
            version = version,
            status = RosterStatusPolicy.STATUS_ARCHIVED,
            uploadTimestamp = System.currentTimeMillis(),
            originalFilePath = imageFilePath,
            workbookFilePath = workbookFile.absolutePath,
            workbookFileUri = null,
            processingStatus = "COMPLETED",
            floorExtracted = ocrMonthlyFloor,
            currentMonthlyFloor = effectiveMonthlyFloor,
            daysCount = scheduleEntities.size
        )

        // Compute deterministic statuses across all employee rosters including the new one
        val allEmpRosters = rosterDao.getRostersForEmployee(employeeId).toMutableList()
        allEmpRosters.removeAll { it.id == rosterId }
        allEmpRosters.add(provisionalRoster)

        val refYm = YearMonth.now(clock)
        val statusMap = RosterStatusPolicy.computeStatuses(allEmpRosters, refYm)
        val calculatedStatus = statusMap[rosterId] ?: RosterStatusPolicy.STATUS_ARCHIVED
        val rosterEntity = provisionalRoster.copy(status = calculatedStatus)

        // Step 5: ATOMIC ROOM DATABASE TRANSACTION WITH STRICT PARENT-CHILD ORDER
        try {
            database.withTransaction {
                android.util.Log.d("ROSTER_DB", "=== BEGIN DATABASE TRANSACTION FOR ROSTER $rosterId ===")

                // 1. Insert Parent RosterEntity FIRST with its calculated status
                android.util.Log.d("ROSTER_DB", "[Op 1] Inserting parent RosterEntity: id=$rosterId, emp=$employeeId, v$version, status=$calculatedStatus")
                rosterDao.insertRoster(rosterEntity)

                // 2. Confirm Parent exists before any child inserts
                val confirmedParent = rosterDao.getRosterById(rosterId)
                checkNotNull(confirmedParent) { "Parent RosterEntity with ID $rosterId was not found after insertion!" }
                android.util.Log.d("ROSTER_DB", "[Op 2] Verified parent RosterEntity confirmed in database: id=${confirmedParent.id}")

                // 3. Update statuses of all other rosters of this employee according to statusMap
                for (r in allEmpRosters) {
                    if (r.id != rosterId) {
                        val target = statusMap[r.id]
                        if (target != null && target != r.status) {
                            rosterDao.updateRosterStatus(r.id, target)
                        }
                    }
                }

                // 4. Insert child DailyScheduleEntity records using the verified rosterId
                android.util.Log.d("ROSTER_DB", "[Op 4] Inserting ${scheduleEntities.size} DailyScheduleEntity records (rosterId=$rosterId)")
                scheduleDao.insertSchedules(scheduleEntities)

                // 5. Insert/update AttendanceEntity records (independent table keyed by employeeId + date)
                android.util.Log.d("ROSTER_DB", "[Op 5] Inserting/updating ${attendancesToSave.size} AttendanceEntity records")
                attendanceDao.insertOrUpdateAttendances(attendancesToSave)

                // 6. Insert ShiftChangeEventEntity records (child of daily_schedules)
                android.util.Log.d("ROSTER_DB", "[Op 6] Inserting ${shiftChangeEntities.size} ShiftChangeEventEntity records")
                auditDao.insertShiftChanges(shiftChangeEntities)

                // 7. Insert FloorChangeEventEntity records (child of daily_schedules)
                if (floorChangeEntities.isNotEmpty()) {
                    android.util.Log.d("ROSTER_DB", "[Op 7] Inserting ${floorChangeEntities.size} FloorChangeEventEntity records")
                    auditDao.insertFloorChanges(floorChangeEntities)
                }

                if (bulkFloorEvents.isNotEmpty()) {
                    bulkFloorEvents.forEach { auditDao.insertBulkFloorChange(it) }
                }

                // 8. Insert ExportRecordEntity (child of rosters)
                android.util.Log.d("ROSTER_DB", "[Op 8] Inserting ExportRecordEntity referencing parent rosterId=$rosterId")
                exportDao.insertExportRecord(
                    ExportRecordEntity(
                        rosterId = rosterId,
                        destinationUri = workbookFile.absolutePath,
                        status = "SUCCESS"
                    )
                )

                android.util.Log.d("ROSTER_DB", "=== DATABASE TRANSACTION COMMITTED SUCCESSFULLY FOR ROSTER $rosterId ===")
            }
        } catch (e: Exception) {
            android.util.Log.e("ROSTER_DB", "FATAL: Database transaction failed during roster save", e)
            throw e
        }

        rosterEntity
    }

    suspend fun getRosterSchedule(rosterId: String): List<DailyScheduleEntry> = withContext(Dispatchers.IO) {
        val roster = rosterDao.getRosterById(rosterId) ?: return@withContext emptyList()
        val schedules = scheduleDao.getSchedulesForRoster(rosterId)
        val dates = schedules.mapNotNull { it.date }
        val attendances = attendanceDao.getAttendancesForDates(roster.employeeId, dates).associateBy { it.date }
        val shiftAudits = auditDao.getShiftChangesForRoster(rosterId).groupBy { it.scheduleId }

        schedules.map { s ->
            val attEntity = s.date?.let { attendances[it] }
            val attRecord = if (attEntity != null) {
                AttendanceRecord(
                    status = AttendanceStatus.valueOf(attEntity.status),
                    notes = attEntity.notes,
                    isFlaggedForReview = attEntity.isFlaggedForReview,
                    reviewReason = attEntity.reviewReason
                )
            } else {
                AttendanceRecord(status = AttendanceStatus.defaultForShift(ShiftType.fromCode(s.currentShiftCode)))
            }

            val audits = (shiftAudits[s.id] ?: emptyList()).map { a ->
                ShiftChangeEvent(
                    id = a.id,
                    timestamp = a.timestamp,
                    previousShiftCode = a.previousShiftCode,
                    newShiftCode = a.newShiftCode,
                    source = ShiftChangeSource.entries.firstOrNull { it.displayName == a.source } ?: ShiftChangeSource.USER_EDIT,
                    author = a.author,
                    reason = a.reason
                )
            }

            DailyScheduleEntry(
                day = s.day,
                date = s.date,
                extractedShift = ShiftType.fromCode(s.extractedShiftCode),
                extractedShiftCode = s.extractedShiftCode,
                currentShift = ShiftType.fromCode(s.currentShiftCode),
                currentShiftCode = s.currentShiftCode,
                isProvisional = s.isProvisional,
                changeHistory = audits,
                extractedFloor = s.extractedFloor,
                currentFloor = s.currentFloor,
                attendance = attRecord
            )
        }
    }

    suspend fun updateShift(
        rosterId: String,
        day: Int,
        newCode: String,
        source: ShiftChangeSource,
        author: String = "User",
        reason: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val roster = rosterDao.getRosterById(rosterId) ?: return@withContext
        val schedules = scheduleDao.getSchedulesForRoster(rosterId)
        val schedule = schedules.firstOrNull { it.day == day } ?: return@withContext

        val oldCode = schedule.currentShiftCode
        val newType = ShiftType.fromCode(newCode)
        if (oldCode == newCode) return@withContext

        val updatedSchedule = schedule.copy(
            currentShiftCode = newType.code,
            isProvisional = (newType == ShiftType.MANAGEMENT_ABSENCE)
        )

        database.withTransaction {
            scheduleDao.updateSchedule(updatedSchedule)

            val shiftAudit = ShiftChangeEventEntity(
                scheduleId = schedule.id,
                rosterId = rosterId,
                day = day,
                date = schedule.date,
                previousShiftCode = oldCode,
                newShiftCode = newType.code,
                source = source.displayName,
                author = author,
                reason = reason
            )
            auditDao.insertShiftChange(shiftAudit)

            // Update attendance if needed
            schedule.date?.let { date ->
                val attendance = attendanceDao.getAttendanceForEmployeeAndDate(roster.employeeId, date)
                if (attendance != null) {
                    val hasRecordedAttendance = attendance.status == AttendanceStatus.PRESENT.name || attendance.status == AttendanceStatus.ABSENT.name
                    if (hasRecordedAttendance) {
                        attendanceDao.insertOrUpdateAttendance(
                            attendance.copy(
                                isFlaggedForReview = true,
                                reviewReason = "Shift changed from '$oldCode' to '${newType.code}' after attendance was recorded",
                                updatedTimestamp = System.currentTimeMillis()
                            )
                        )
                    } else if (!newType.isWorkShift && attendance.status == AttendanceStatus.NOT_MARKED.name) {
                        attendanceDao.insertOrUpdateAttendance(
                            attendance.copy(status = AttendanceStatus.NOT_APPLICABLE.name, updatedTimestamp = System.currentTimeMillis())
                        )
                    } else if (newType.isWorkShift && attendance.status == AttendanceStatus.NOT_APPLICABLE.name) {
                        attendanceDao.insertOrUpdateAttendance(
                            attendance.copy(status = AttendanceStatus.NOT_MARKED.name, updatedTimestamp = System.currentTimeMillis())
                        )
                    }
                }
            }
        }

        regenerateWorkbook(rosterId)
    }

    suspend fun updateFloor(
        rosterId: String,
        day: Int,
        newFloor: String?,
        author: String = "User"
    ): Unit = withContext(Dispatchers.IO) {
        val roster = rosterDao.getRosterById(rosterId) ?: return@withContext
        val schedules = scheduleDao.getSchedulesForRoster(rosterId)
        val schedule = schedules.firstOrNull { it.day == day } ?: return@withContext

        val oldFloor = schedule.currentFloor
        val normalized = newFloor?.trim()?.ifBlank { null }
        if (oldFloor == normalized) return@withContext

        val updatedSchedule = schedule.copy(currentFloor = normalized)

        database.withTransaction {
            scheduleDao.updateSchedule(updatedSchedule)

            val floorAudit = FloorChangeEventEntity(
                scheduleId = schedule.id,
                rosterId = rosterId,
                day = day,
                date = schedule.date,
                previousFloor = oldFloor,
                newFloor = normalized,
                author = author
            )
            auditDao.insertFloorChange(floorAudit)

            // Check attendance review flag
            schedule.date?.let { date ->
                val attendance = attendanceDao.getAttendanceForEmployeeAndDate(roster.employeeId, date)
                if (attendance != null && (attendance.status == AttendanceStatus.PRESENT.name || attendance.status == AttendanceStatus.ABSENT.name)) {
                    attendanceDao.insertOrUpdateAttendance(
                        attendance.copy(
                            isFlaggedForReview = true,
                            reviewReason = "Floor reassigned to '${normalized ?: "Unassigned"}' after attendance was recorded",
                            updatedTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        regenerateWorkbook(rosterId)
    }

    /**
     * Atomically saves planned shift and floor edits for a daily schedule entry.
     * Records shift and floor change audit events in the same Room transaction without duplication.
     * Preserves attendance status and original extracted values.
     */
    suspend fun saveDailyScheduleEdit(
        rosterId: String,
        day: Int,
        newShiftCode: String,
        newFloor: String?,
        author: String = "Clinical Supervisor",
        reason: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val roster = rosterDao.getRosterById(rosterId) ?: return@withContext
        val schedules = scheduleDao.getSchedulesForRoster(rosterId)
        val schedule = schedules.firstOrNull { it.day == day } ?: return@withContext

        val oldShiftCode = schedule.currentShiftCode
        val oldFloor = schedule.currentFloor
        val normalizedFloor = newFloor?.trim()?.ifBlank { null }

        val shiftChanged = oldShiftCode != newShiftCode && ShiftType.isValidShiftCode(newShiftCode)
        val floorChanged = (oldFloor ?: "") != (normalizedFloor ?: "")

        if (!shiftChanged && !floorChanged) {
            return@withContext
        }

        val newType = if (shiftChanged) ShiftType.fromCode(newShiftCode) else ShiftType.fromCode(oldShiftCode)
        val updatedSchedule = schedule.copy(
            currentShiftCode = if (shiftChanged) newType.code else oldShiftCode,
            isProvisional = if (shiftChanged) (newType == ShiftType.MANAGEMENT_ABSENCE) else schedule.isProvisional,
            currentFloor = if (floorChanged) normalizedFloor else oldFloor
        )

        database.withTransaction {
            scheduleDao.updateSchedule(updatedSchedule)

            if (shiftChanged) {
                val shiftAudit = ShiftChangeEventEntity(
                    scheduleId = schedule.id,
                    rosterId = rosterId,
                    day = day,
                    date = schedule.date,
                    previousShiftCode = oldShiftCode,
                    newShiftCode = newType.code,
                    source = ShiftChangeSource.USER_EDIT.displayName,
                    author = author,
                    reason = reason
                )
                auditDao.insertShiftChange(shiftAudit)
            }

            if (floorChanged) {
                val floorAudit = FloorChangeEventEntity(
                    scheduleId = schedule.id,
                    rosterId = rosterId,
                    day = day,
                    date = schedule.date,
                    previousFloor = oldFloor,
                    newFloor = normalizedFloor,
                    author = author
                )
                auditDao.insertFloorChange(floorAudit)
            }

            // Flag attendance for review if attendance was previously marked
            schedule.date?.let { date ->
                val attendance = attendanceDao.getAttendanceForEmployeeAndDate(roster.employeeId, date)
                if (attendance != null) {
                    val hasRecordedAttendance = attendance.status == AttendanceStatus.PRESENT.name || attendance.status == AttendanceStatus.ABSENT.name
                    if (hasRecordedAttendance) {
                        val reviewReason = if (shiftChanged && floorChanged) {
                            "Shift changed to '${newType.code}' and floor changed to '${normalizedFloor ?: "Unassigned"}' after attendance was recorded"
                        } else if (shiftChanged) {
                            "Shift changed from '$oldShiftCode' to '${newType.code}' after attendance was recorded"
                        } else {
                            "Floor reassigned to '${normalizedFloor ?: "Unassigned"}' after attendance was recorded"
                        }
                        attendanceDao.insertOrUpdateAttendance(
                            attendance.copy(
                                isFlaggedForReview = true,
                                reviewReason = reviewReason,
                                updatedTimestamp = System.currentTimeMillis()
                            )
                        )
                    } else if (shiftChanged) {
                        if (!newType.isWorkShift && attendance.status == AttendanceStatus.NOT_MARKED.name) {
                            attendanceDao.insertOrUpdateAttendance(
                                attendance.copy(status = AttendanceStatus.NOT_APPLICABLE.name, updatedTimestamp = System.currentTimeMillis())
                            )
                        } else if (newType.isWorkShift && attendance.status == AttendanceStatus.NOT_APPLICABLE.name) {
                            attendanceDao.insertOrUpdateAttendance(
                                attendance.copy(status = AttendanceStatus.NOT_MARKED.name, updatedTimestamp = System.currentTimeMillis())
                            )
                        }
                    }
                }
            }
        }

        regenerateWorkbook(rosterId)
    }

    /**
     * Atomically updates the monthly default floor and all daily schedules for [rosterId].
     * Reconciles all date-specific overrides so every day receives [newFloor].
     * Preserves original OCR-extracted floor in [RosterEntity.floorExtracted] and [DailyScheduleEntity.extractedFloor].
     * Records a [BulkFloorChangeEventEntity] and individual [FloorChangeEventEntity]s inside the same transaction.
     * Leaves planned shifts, attendance, and notes unaltered.
     */
    suspend fun bulkUpdateFloor(
        rosterId: String,
        newFloor: String,
        author: String = "User",
        reason: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedFloor = newFloor.trim()
        if (normalizedFloor.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Floor cannot be blank"))
        }

        val roster = rosterDao.getRosterById(rosterId)
            ?: return@withContext Result.failure(IllegalArgumentException("Roster with ID '$rosterId' not found"))

        val schedules = scheduleDao.getSchedulesForRoster(rosterId)
        if (schedules.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No schedule days found for roster '$rosterId'"))
        }

        val previousMonthlyFloor = roster.getEffectiveMonthlyFloor()
        val updatedRoster = roster.copy(currentMonthlyFloor = normalizedFloor)

        val updatedSchedules = mutableListOf<DailyScheduleEntity>()
        val floorAudits = mutableListOf<FloorChangeEventEntity>()

        for (schedule in schedules) {
            val oldFloor = schedule.currentFloor
            updatedSchedules.add(schedule.copy(currentFloor = normalizedFloor))
            if ((oldFloor ?: "") != normalizedFloor) {
                floorAudits.add(
                    FloorChangeEventEntity(
                        scheduleId = schedule.id,
                        rosterId = rosterId,
                        day = schedule.day,
                        date = schedule.date,
                        previousFloor = oldFloor,
                        newFloor = normalizedFloor,
                        author = "$author (Bulk Update)"
                    )
                )
            }
        }

        val bulkAudit = BulkFloorChangeEventEntity(
            rosterId = rosterId,
            month = roster.month,
            year = roster.year,
            timestamp = System.currentTimeMillis(),
            previousFloor = previousMonthlyFloor,
            newFloor = normalizedFloor,
            affectedDaysCount = schedules.size,
            author = author,
            description = reason?.takeIf { it.isNotBlank() }
                ?: "Bulk floor update for all ${schedules.size} days in ${roster.year}-${roster.month.toString().padStart(2, '0')}: '${previousMonthlyFloor ?: "Unassigned"}' -> '$normalizedFloor'"
        )

        try {
            database.withTransaction {
                rosterDao.updateRoster(updatedRoster)
                if (updatedSchedules.isNotEmpty()) {
                    scheduleDao.insertSchedules(updatedSchedules)
                }
                auditDao.insertBulkFloorChange(bulkAudit)
                if (floorAudits.isNotEmpty()) {
                    auditDao.insertFloorChanges(floorAudits)
                }
            }

            regenerateWorkbook(rosterId)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("ROSTER_DB", "Failed to perform bulk floor update", e)
            Result.failure(e)
        }
    }

    suspend fun updateAttendance(
        employeeId: String,
        date: String,
        rosterId: String,
        newStatus: AttendanceStatus,
        notes: String? = null,
        author: String = "User"
    ): Unit = withContext(Dispatchers.IO) {
        val current = attendanceDao.getAttendanceForEmployeeAndDate(employeeId, date)
        val oldStatus = current?.status
        val isFlagged = current?.isFlaggedForReview == true
        if (oldStatus == newStatus.name && current?.notes == notes && !isFlagged) return@withContext

        val attId = current?.id ?: UUID.randomUUID().toString()
        val updated = AttendanceEntity(
            id = attId,
            employeeId = employeeId,
            date = date,
            status = newStatus.name,
            notes = notes,
            isFlaggedForReview = false,
            reviewReason = null,
            updatedTimestamp = System.currentTimeMillis()
        )

        database.withTransaction {
            attendanceDao.insertOrUpdateAttendance(updated)

            val changeAudit = AttendanceChangeEventEntity(
                attendanceId = attId,
                employeeId = employeeId,
                date = date,
                previousStatus = oldStatus,
                newStatus = newStatus.name,
                author = author,
                notes = notes
            )
            auditDao.insertAttendanceChange(changeAudit)
        }

        regenerateWorkbook(rosterId)
    }

    suspend fun regenerateWorkbook(rosterId: String): File? = withContext(Dispatchers.IO) {
        val roster = rosterDao.getRosterById(rosterId) ?: return@withContext null
        val scheduleEntries = getRosterSchedule(rosterId)
        val shiftChanges = auditDao.getShiftChangesForRoster(rosterId)
        val floorChanges = auditDao.getFloorChangesForRoster(rosterId)
        val attendanceChanges = auditDao.getAttendanceChangesForEmployee(roster.employeeId)

        val file = storageManager.getWorkbookFile(roster.employeeId, roster.id, roster.month, roster.year, roster.version)
        try {
            FileOutputStream(file).use { out ->
                ExcelGenerator.generateWorkbook(
                    roster = roster,
                    scheduleEntries = scheduleEntries,
                    shiftChanges = shiftChanges,
                    floorChanges = floorChanges,
                    attendanceChanges = attendanceChanges,
                    outputStream = out
                )
            }
            file
        } catch (e: Exception) {
            android.util.Log.e("RosterRepository", "Failed to regenerate workbook", e)
            null
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: RosterRepository? = null

        fun getInstance(context: Context): RosterRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getInstance(context)
                val storage = StorageManager(context)
                val instance = RosterRepository(db, storage)
                INSTANCE = instance
                instance
            }
        }
    }
}
