package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class VersionStringComparatorTest {

    private val comparator = VersionStringComparator()

    @ParameterizedTest
    @CsvSource(
        "1.0, 1.1",
        "1.0, 2.0",
        "1.0.0, 1.0.1",
        "1.0.0, 1.1.0",
        "1.9, 1.10",
        "1.0.0, 2.0.0",
        "0.1, 0.2"
    )
    fun testNumericOrdering(lower: String, higher: String) {
        assertTrue(comparator.compare(lower, higher) < 0, "$lower should be < $higher")
        assertTrue(comparator.compare(higher, lower) > 0, "$higher should be > $lower")
    }

    @ParameterizedTest
    @CsvSource(
        "1.0, 1.0",
        "2.3.4, 2.3.4",
        "1.0-SNAPSHOT, 1.0-SNAPSHOT"
    )
    fun testEqualVersions(v1: String, v2: String) {
        assertEquals(0, comparator.compare(v1, v2))
    }

    @Test
    fun testSnapshotSortsBeforeRelease() {
        // In Maven ordering, SNAPSHOT is a pre-release qualifier and sorts before the release
        assertTrue(comparator.compare("1.0-SNAPSHOT", "1.0") < 0)
    }

    @ParameterizedTest
    @CsvSource(
        "1.0-SNAPSHOT, 2.0-SNAPSHOT",
        "1.0-SNAPSHOT, 1.1-SNAPSHOT"
    )
    fun testSnapshotOrderingAmongSnapshots(lower: String, higher: String) {
        assertTrue(comparator.compare(lower, higher) < 0)
    }

    @Test
    fun testMixedDelimitersAreEqual() {
        assertEquals(0, comparator.compare("1.0.0", "1-0-0"))
        assertEquals(0, comparator.compare("1.0.0", "1_0_0"))
        assertEquals(0, comparator.compare("1-0-0", "1_0_0"))
    }

    @Test
    fun testLeadingZeros() {
        assertEquals(0, comparator.compare("1.01", "1.1"))
        assertEquals(0, comparator.compare("01.01.01", "1.1.1"))
    }

    @Test
    fun testVeryLongVersionString() {
        assertTrue(comparator.compare("1.2.3.4.5.6.7.8", "1.2.3.4.5.6.7.9") < 0)
        assertEquals(0, comparator.compare("1.2.3.4.5.6.7.8", "1.2.3.4.5.6.7.8"))
    }

    @Test
    fun testEmptySegmentsFiltered() {
        // "1..0" splits to ["1","0"] after filtering empty strings
        assertEquals(0, comparator.compare("1..0", "1.0"))
    }

    @Test
    fun testTransitivity() {
        val versions = listOf("1.0", "1.1", "1.2", "2.0", "2.1", "3.0")
        for (i in versions.indices) {
            for (j in i + 1 until versions.size) {
                assertTrue(comparator.compare(versions[i], versions[j]) < 0,
                    "${versions[i]} should be < ${versions[j]}")
            }
        }
    }

    @Test
    fun testQualifierOrdering() {
        // Maven qualifier order: alpha < beta < milestone < rc < snapshot < "" (release) < sp
        assertTrue(comparator.compare("1.0-alpha", "1.0-beta") < 0)
        assertTrue(comparator.compare("1.0-beta", "1.0-milestone") < 0)
        assertTrue(comparator.compare("1.0-milestone", "1.0-rc") < 0)
        assertTrue(comparator.compare("1.0-rc", "1.0-SNAPSHOT") < 0)
        assertTrue(comparator.compare("1.0-SNAPSHOT", "1.0") < 0)
        assertTrue(comparator.compare("1.0", "1.0-sp") < 0)
    }

    @Test
    fun testQualifierShortForms() {
        assertEquals(0, comparator.compare("1.0-alpha", "1.0-a"))
        assertEquals(0, comparator.compare("1.0-beta", "1.0-b"))
        assertEquals(0, comparator.compare("1.0-milestone", "1.0-m"))
        assertEquals(0, comparator.compare("1.0-rc", "1.0-cr"))
    }

    @Test
    fun testSymmetry() {
        val pairs = listOf("1.0" to "2.0", "1.0" to "1.0-SNAPSHOT", "3.1" to "3.2")
        for ((a, b) in pairs) {
            val ab = comparator.compare(a, b)
            val ba = comparator.compare(b, a)
            assertEquals(-Integer.signum(ab), Integer.signum(ba),
                "compare($a,$b)=$ab but compare($b,$a)=$ba — symmetry violated")
        }
    }

    @Test
    fun testSortingAListOfVersions() {
        val versions = listOf("3.0", "1.0", "2.1", "1.1", "2.0", "1.0-SNAPSHOT")
        val sorted = versions.sortedWith(comparator)
        assertEquals(listOf("1.0-SNAPSHOT", "1.0", "1.1", "2.0", "2.1", "3.0"), sorted)
    }
}
