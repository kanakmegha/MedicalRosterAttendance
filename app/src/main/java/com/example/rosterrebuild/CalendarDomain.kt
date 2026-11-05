package com.example.rosterrebuild

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
     */
    fun buildMonthDays(
        year: Int,
        month: Int,
        schedule: List<DailyScheduleEntry>,
        today: LocalDate = LocalDate.now()
    ): List<CalendarDay> {
        val daysCount = getDaysInMonth(year, month)
        val targetMonthPrefix = "%04d-%02d".format(year, month)
        val scheduleMap = schedule.filter { entry ->
            entry.date == null || entry.date.startsWith(targetMonthPrefix)
        }.associateBy { it.day }

        return (1..daysCount).map { day ->
            val date = LocalDate.of(year, month, day)
            val isToday = (date == today)
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

