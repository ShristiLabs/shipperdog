package com.shipperdog.companion.core

/**
 * The version-name ordering the update badge needs (issue #73,
 * S15-02): the platform's version names are dot-separated numeric
 * segments (`1.2.3`), occasionally with a pre-release suffix
 * (`1.2.3-rc1`). Numeric segments compare numerically (1.10 > 1.9),
 * a version WITH a suffix precedes its own release point
 * (1.2.3-rc1 < 1.2.3, the semver convention), and a longer segment
 * list with equal prefix wins (1.2.3 > 1.2) — the conservative
 * ordering that never claims "update available" on an equal name.
 */
object VersionOrder {

    /** True when [candidate] is strictly newer than [installed]. */
    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    /** Compares two version names; negative = left older, positive = left newer. */
    fun compare(left: String, right: String): Int {
        val leftParts = parse(left)
        val rightParts = parse(right)
        val segments = maxOf(leftParts.first.size, rightParts.first.size)
        for (index in 0 until segments) {
            val leftSegment = leftParts.first.getOrElse(index) { 0 }
            val rightSegment = rightParts.first.getOrElse(index) { 0 }
            if (leftSegment != rightSegment) {
                return leftSegment.compareTo(rightSegment)
            }
        }
        val leftSuffix = leftParts.second
        val rightSuffix = rightParts.second
        return when {
            leftSuffix == null && rightSuffix == null -> 0
            leftSuffix == null -> 1 // the release point follows any of its own pre-releases
            rightSuffix == null -> -1
            else -> leftSuffix.compareTo(rightSuffix, ignoreCase = true)
        }
    }

    private fun parse(version: String): Pair<List<Int>, String?> {
        val trimmed = version.trim()
        val dash = trimmed.indexOf('-')
        val numeric = if (dash >= 0) trimmed.substring(0, dash) else trimmed
        val suffix = if (dash >= 0) trimmed.substring(dash + 1) else null
        val segments = numeric.split('.').map { segment ->
            segment.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
        return segments to suffix
    }
}
