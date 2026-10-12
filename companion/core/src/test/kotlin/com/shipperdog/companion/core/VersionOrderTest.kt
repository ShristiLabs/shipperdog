package com.shipperdog.companion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The version-name ordering's suite (issue #73, S15-02): the update
 * badge's whole decision procedure — numeric segments, pre-release
 * suffixes, unequal lengths, and the never-claim-update-on-equal rule.
 */
class VersionOrderTest {

    @Test
    fun numericSegmentsCompareNumerically() {
        assertTrue(VersionOrder.isNewer("1.10.0", "1.9.0"))
        assertFalse(VersionOrder.isNewer("1.9.0", "1.10.0"))
        assertTrue(VersionOrder.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun equalNamesAreNotUpdates() {
        assertFalse(VersionOrder.isNewer("1.2.3", "1.2.3"))
        assertEquals(0, VersionOrder.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun longerSegmentListsWinOnEqualPrefix() {
        assertTrue(VersionOrder.isNewer("1.2.3", "1.2"))
        assertFalse(VersionOrder.isNewer("1.2", "1.2.3"))
    }

    @Test
    fun preReleaseSuffixesPrecedeTheirReleasePoint() {
        assertTrue(VersionOrder.isNewer("1.2.3", "1.2.3-rc1"))
        assertFalse(VersionOrder.isNewer("1.2.3-rc1", "1.2.3"))
        assertTrue(VersionOrder.isNewer("1.2.3-rc2", "1.2.3-rc1"))
    }

    @Test
    fun nonNumericSegmentsDegradeToZero() {
        assertTrue(VersionOrder.isNewer("1.2.1", "1.2.x"))
        assertFalse(VersionOrder.isNewer("1.2.x", "1.2.1"))
    }

    @Test
    fun suffixComparisonIgnoresCase() {
        assertEquals(0, VersionOrder.compare("1.2.3-RC1", "1.2.3-rc1"))
        assertTrue(VersionOrder.isNewer("1.2.3-rc1", "1.2.3-BETA"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(0, VersionOrder.compare(" 1.2.3 ", "1.2.3"))
        assertFalse(VersionOrder.isNewer(" 1.2.3 ", "1.2.3"))
    }
}
