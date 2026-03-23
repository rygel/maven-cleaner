package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FormatUtilsTest {

    @Test
    fun testZeroBytes() {
        assertEquals("0 B", formatSize(0))
    }

    @Test
    fun testNegativeSize() {
        assertEquals("0 B", formatSize(-1))
        assertEquals("0 B", formatSize(-999))
    }

    @Test
    fun testOneByte() {
        val result = formatSize(1)
        assertTrue(result.endsWith("B"))
        assertTrue(result.contains("1"))
    }

    @Test
    fun testJustBelowKilobyte() {
        val result = formatSize(1023)
        assertTrue(result.endsWith("B"), "1023 bytes should be in B, got: $result")
    }

    @Test
    fun testOneKilobyte() {
        val result = formatSize(1024)
        assertTrue(result.endsWith("kB"))
    }

    @Test
    fun testOneMegabyte() {
        val result = formatSize(1_048_576)
        assertTrue(result.endsWith("MB"))
    }

    @Test
    fun testOneGigabyte() {
        val result = formatSize(1_073_741_824)
        assertTrue(result.endsWith("GB"))
    }

    @Test
    fun testOneTerabyte() {
        val result = formatSize(1_099_511_627_776)
        assertTrue(result.endsWith("TB"))
    }

    @Test
    fun testMaxLongDoesNotCrash() {
        val result = formatSize(Long.MAX_VALUE)
        assertNotNull(result)
        assertTrue(result.endsWith("EB"), "Long.MAX_VALUE (~8 EB) should use EB unit, got: $result")
    }

    @ParameterizedTest
    @CsvSource(
        "512, B",
        "2048, kB",
        "5242880, MB",
        "2147483648, GB"
    )
    fun testCorrectUnitSelected(size: Long, expectedUnit: String) {
        val result = formatSize(size)
        assertTrue(result.endsWith(expectedUnit), "Expected $expectedUnit for $size, got: $result")
    }
}
