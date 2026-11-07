package com.example.rosterrebuild

import com.example.rosterrebuild.data.local.RosterEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Domain model representing a single calendar grid cell.
 */
data class CalendarDay(
    val dayOfMonth: Int,
    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val scheduleEntry: DailyScheduleEntry?
)

/**
 * Result representation for evaluating today's clinical schedule.
 */
sealed class TodayScheduleResult {
    data class Success(
        val roster: RosterEntity,
        val entry: DailyScheduleEntry,
        val date: LocalDate,
        val employeeId: String
    ) : TodayScheduleResult()

    data class NoRoster(
        val date: LocalDate,
        val employeeId: String,
        val reason: String
    ) : TodayScheduleResult()

    data class NoEntryInRoster(
        val roster: RosterEntity,
        val date: LocalDate,
        val employeeId: String,
        val reason: String
    ) : TodayScheduleResult()
}

/**
 * Representation for the next upcoming scheduled clinical duty.
 */
data class UpcomingShiftResult(
    val date: LocalDate,
    val entry: DailyScheduleEntry,
    val roster: RosterEntity
)

/**
 * Monthly calendar calculations, weekday alignment, and grid generation.
 */
object CalendarDomain {

    val WEEKDAY_HEADERS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /**
     * Calculates the exact number of days for any year and month.
     * Correctly handles 28, 29 (leap year), 30, and 31-day months.
     */
    fun getDaysInMonth(year: Int, month: Int): Int {
        require(month in 1..12) { "Month must be between 1 and 12, got $month" }
        return YearMonth.of(year, month).lengthOfMonth()
    }

    /**
     * Calculates the 0-based offset for the first day of the month where
     * 0 = Monday, 1 = Tuesday, ..., 6 = Sunday.
     */
    fun getFirstDayOfWeekOffset(year: Int, month: Int): Int {
        require(month in 1..12) { "Month must be between 1 and 12, got $month" }
        val firstDay = LocalDate.of(year, month, 1)
        // DayOfWeek: MONDAY is 1, SUNDAY is 7
        return firstDay.dayOfWeek.value - 1
    }

    /**
     * Formats the month and year header (e.g. "June 2026").
     */
    fun formatMonthHeader(year: Int, month: Int): String {
        val ym = YearMonth.of(year, month)
        val monthTitle = ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "$monthTitle $year"
    }

    /**
     * Builds the complete list of [CalendarDay] objects for the specified month and schedule.
     *
     * Correctly ensures [CalendarDay.isToday] is only true when viewing the actual current month
     * and year, preventing historical or future dates with the same day number from being marked as today.
     */
    fun buildMonthDays(
        year: Int,
        month: Int,
        schedule: List<DailyScheduleEntry>,
        today: LocalDate = LocalDate.now()
    ): List<CalendarDay> {
        val daysCount = getDaysInMonth(year, month)
        val isViewingCurrentMonth = (year == today.year && month == today.monthValue)
        val targetMonthPrefix = "%04d-%02d".format(year, month)

        val scheduleMap = schedule.filter { entry ->
            entry.date == null || entry.date.startsWith(targetMonthPrefix)
        }.associateBy { it.day }

        return (1..daysCount).map { day ->
            val date = LocalDate.of(year, month, day)
            val isToday = isViewingCurrentMonth && (day == today.dayOfMonth)
            CalendarDay(
                dayOfMonth = day,
                date = date,
                isCurrentMonth = true,
                isToday = isToday,
                scheduleEntry = scheduleMap[day]
            )
        }
    }
}

/**
 * Domain logic for locating applicable rosters and extracting today's clinical schedule.
 */
object TodayScheduleResolver {

    /**
     * Finds the roster applicable to [targetDate] for the specified [employeeId].
     *
     * Selection criteria:
     * 1. Filters strictly by [employeeId] and matching [targetDate.year] and [targetDate.monthValue].
     * 2. Among candidate rosters for that exact month, prioritizes status == "ACTIVE",
     *    then highest version, then latest upload timestamp.
     * 3. Never simply assumes the most recently uploaded roster across other months is applicable.
     */
    fun findApplicableRoster(
        rosters: List<RosterEntity>,
        employeeId: String,
        targetDate: LocalDate
    ): RosterEntity? {
        val candidates = rosters.filter {
            it.employeeId.equals(employeeId.trim(), ignoreCase = true) &&
                    it.year == targetDate.year &&
                    it.month == targetDate.monthValue
        }
        if (candidates.isEmpty()) return null

        return candidates.firstOrNull { it.status == "ACTIVE" }
            ?: candidates.maxWithOrNull(
                compareBy<RosterEntity> { it.version }
                    .thenBy { it.uploadTimestamp }
            )
    }

    /**
     * Retrieves the schedule entry matching [targetDate] from the given [schedule] list.
     *
     * Matches strictly using the full date string "YYYY-MM-DD", falling back to day number
     * only when date is unspecified and roster year/month match.
     */
    fun findScheduleEntry(
        schedule: List<DailyScheduleEntry>,
        roster: RosterEntity,
        targetDate: LocalDate
    ): DailyScheduleEntry? {
        val targetDateStr = "%04d-%02d-%02d".format(
            targetDate.year,
            targetDate.monthValue,
            targetDate.dayOfMonth
        )
        return schedule.firstOrNull { it.date == targetDateStr }
            ?: schedule.firstOrNull {
                it.day == targetDate.dayOfMonth &&
                        (it.date == null || it.date == targetDateStr) &&
                        roster.year == targetDate.year &&
                        roster.month == targetDate.monthValue
            }
    }

    /**
     * Resolves today's schedule for [employeeId] on [targetDate].
     */
    inline fun resolveTodaySchedule(
        rosters: List<RosterEntity>,
        employeeId: String,
        targetDate: LocalDate,
        scheduleProvider: (rosterId: String) -> List<DailyScheduleEntry>
    ): TodayScheduleResult {
        val applicableRoster = findApplicableRoster(rosters, employeeId, targetDate)
        if (applicableRoster == null) {
            val monthName = targetDate.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            return TodayScheduleResult.NoRoster(
                date = targetDate,
                employeeId = employeeId,
                reason = "No saved roster for $monthName ${targetDate.year}."
            )
        }

        val schedule = scheduleProvider(applicableRoster.id)
        val entry = findScheduleEntry(schedule, applicableRoster, targetDate)
        if (entry == null) {
            val monthName = targetDate.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            return TodayScheduleResult.NoEntryInRoster(
                roster = applicableRoster,
                date = targetDate,
                employeeId = employeeId,
                reason = "No schedule entry found for ${targetDate.dayOfMonth} $monthName ${targetDate.year} in roster v${applicableRoster.version}."
            )
        }

        return TodayScheduleResult.Success(
            roster = applicableRoster,
            entry = entry,
            date = targetDate,
            employeeId = employeeId
        )
    }

    /**
     * Resolves the next upcoming scheduled clinical duty after [targetDate].
     */
    inline fun findUpcomingShift(
        rosters: List<RosterEntity>,
        employeeId: String,
        targetDate: LocalDate,
        scheduleProvider: (rosterId: String) -> List<DailyScheduleEntry>
    ): UpcomingShiftResult? {
        val currentRoster = findApplicableRoster(rosters, employeeId, targetDate)
        if (currentRoster != null) {
            val schedule = scheduleProvider(currentRoster.id)
            val upcomingEntry = schedule
                .filter { it.day > targetDate.dayOfMonth && it.currentShift.isWorkShift }
                .minByOrNull { it.day }
            if (upcomingEntry != null) {
                val shiftDate = LocalDate.of(currentRoster.year, currentRoster.month, upcomingEntry.day)
                return UpcomingShiftResult(
                    date = shiftDate,
                    entry = upcomingEntry,
                    roster = currentRoster
                )
            }
        }

        // Check if there is an active roster for next month
        val nextMonthDate = targetDate.plusMonths(1)
        val nextRoster = findApplicableRoster(rosters, employeeId, nextMonthDate)
        if (nextRoster != null) {
            val nextSchedule = scheduleProvider(nextRoster.id)
            val firstEntry = nextSchedule
                .filter { it.currentShift.isWorkShift }
                .minByOrNull { it.day }
            if (firstEntry != null) {
                val shiftDate = LocalDate.of(nextRoster.year, nextRoster.month, firstEntry.day)
                return UpcomingShiftResult(
                    date = shiftDate,
                    entry = firstEntry,
                    roster = nextRoster
                )
            }
        }
        return null
    }
}
