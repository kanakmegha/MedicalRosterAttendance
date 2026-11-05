package com.example.rosterrebuild.data.repository

import com.example.rosterrebuild.data.local.RosterEntity
import java.time.YearMonth

/**
 * Business rules and policy for deterministic Roster Status calculation.
 *
 * Rules:
 * 1. Scope: For each employee ID independently.
 * 2. Versioning: If multiple versions exist for the same month and year, only the highest
 *    version is valid; all previous/superseded versions of that month remain permanently ARCHIVED.
 * 3. Chronology: The active roster must be determined by the roster's actual month and year,
 *    comparing year first then month, NOT by upload time, creation time, or insertion order.
 * 4. Future Month Handling: A future month's roster must not automatically become active before
 *    its effective period. It is classified as UPCOMING.
 * 5. Effective Month Selection:
 *    - Uses the current month in the application's configured local timezone.
 *    - If a roster exists for the current month, that highest version is ACTIVE.
 *    - If there is no roster for the current month, the most recent available past month is ACTIVE.
 *    - If only future rosters exist, there is NO active roster; future rosters are UPCOMING.
 *    - All older past months are ARCHIVED.
 * 6. Invariant: At most one roster per employee may be ACTIVE at any time.
 */
object RosterStatusPolicy {
    const val STATUS_ACTIVE = "ACTIVE"
    const val STATUS_UPCOMING = "UPCOMING"
    const val STATUS_ARCHIVED = "ARCHIVED"

    /**
     * Deterministically calculates the status for all rosters belonging to a single employee.
     */
    fun computeStatuses(
        employeeRosters: List<RosterEntity>,
        referenceYearMonth: YearMonth
    ): Map<String, String> {
        if (employeeRosters.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, String>()
        val currentPeriodVal = referenceYearMonth.year * 12 + referenceYearMonth.monthValue

        // Group by (year, month) to identify candidate versions vs superseded versions
        val byPeriod = employeeRosters.groupBy { it.year to it.month }
        val candidateRosters = mutableListOf<RosterEntity>()

        for ((_, periodRosters) in byPeriod) {
            // Find highest version for this period
            val highestVersionRoster = periodRosters.maxByOrNull { it.version }
            for (r in periodRosters) {
                if (r.id == highestVersionRoster?.id) {
                    candidateRosters.add(r)
                } else {
                    // Superseded versions must be ARCHIVED
                    result[r.id] = STATUS_ARCHIVED
                }
            }
        }

        // Split candidate versions into future (UPCOMING) vs applicable (current or past)
        val futureCandidates = candidateRosters.filter { (it.year * 12 + it.month) > currentPeriodVal }
        val applicableCandidates = candidateRosters.filter { (it.year * 12 + it.month) <= currentPeriodVal }

        // Future candidates are UPCOMING
        for (f in futureCandidates) {
            result[f.id] = STATUS_UPCOMING
        }

        // Among applicable candidates, find the latest month/year
        val activeCandidate = applicableCandidates.maxWithOrNull(
            compareBy<RosterEntity> { it.year }
                .thenBy { it.month }
                .thenBy { it.version }
        )

        for (a in applicableCandidates) {
            if (a.id == activeCandidate?.id) {
                result[a.id] = STATUS_ACTIVE
            } else {
                result[a.id] = STATUS_ARCHIVED
            }
        }

        return result
    }

    /**
     * Calculates statuses for rosters across all employees independently.
     */
    fun computeAllStatuses(
        allRosters: List<RosterEntity>,
        referenceYearMonth: YearMonth
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val byEmployee = allRosters.groupBy { it.employeeId }
        for ((_, empRosters) in byEmployee) {
            result.putAll(computeStatuses(empRosters, referenceYearMonth))
        }
        return result
    }
}

