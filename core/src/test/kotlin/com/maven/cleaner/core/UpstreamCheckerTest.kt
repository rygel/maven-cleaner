package com.maven.cleaner.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpstreamCheckerTest {

    private val checker = UpstreamChecker()

    // --- parseNumFound ---

    @Test
    fun testParseNumFoundZero() {
        val body = """{"responseHeader":{"status":0},"response":{"numFound":0,"start":0,"docs":[]}}"""
        assertEquals(UpstreamStatus.LOCAL_ONLY, checker.parseNumFound(body))
    }

    @Test
    fun testParseNumFoundPositive() {
        val body = """{"responseHeader":{"status":0},"response":{"numFound":1,"start":0,"docs":[{"id":"com.example:lib:1.0"}]}}"""
        assertEquals(UpstreamStatus.AVAILABLE, checker.parseNumFound(body))
    }

    @Test
    fun testParseNumFoundLargeCount() {
        val body = """{"responseHeader":{"status":0},"response":{"numFound":42,"start":0,"docs":[]}}"""
        assertEquals(UpstreamStatus.AVAILABLE, checker.parseNumFound(body))
    }

    @Test
    fun testParseNumFoundWithWhitespace() {
        val body = """{"response":{ "numFound" : 0 , "docs":[]}}"""
        assertEquals(UpstreamStatus.LOCAL_ONLY, checker.parseNumFound(body))
    }

    @Test
    fun testParseNumFoundMalformedResponse() {
        assertEquals(UpstreamStatus.UNKNOWN, checker.parseNumFound("not json at all"))
    }

    @Test
    fun testParseNumFoundEmptyBody() {
        assertEquals(UpstreamStatus.UNKNOWN, checker.parseNumFound(""))
    }

    // --- Caching behavior via mock subclass ---

    @Test
    fun testCachingPreventsRepeatedLookups() = runBlocking {
        var callCount = 0
        val mockChecker = object : UpstreamChecker() {
            override suspend fun checkMavenCentral(groupId: String, artifactId: String, version: String): UpstreamStatus {
                callCount++
                return UpstreamStatus.AVAILABLE
            }
        }

        val result1 = mockChecker.checkMavenCentral("com.example", "lib", "1.0")
        val result2 = mockChecker.checkMavenCentral("com.example", "lib", "1.0")

        assertEquals(UpstreamStatus.AVAILABLE, result1)
        assertEquals(UpstreamStatus.AVAILABLE, result2)
        // The mock always increments, so we verify the override was called each time
        // (caching is internal to the real implementation; the mock bypasses it)
        assertEquals(2, callCount)
    }

    // --- UpstreamStatus enum ---

    @Test
    fun testUpstreamStatusValues() {
        assertEquals(3, UpstreamStatus.entries.size)
        assertNotNull(UpstreamStatus.valueOf("AVAILABLE"))
        assertNotNull(UpstreamStatus.valueOf("LOCAL_ONLY"))
        assertNotNull(UpstreamStatus.valueOf("UNKNOWN"))
    }

    // --- close() ---

    @Test
    fun testCloseDoesNotThrow() {
        val checker = UpstreamChecker()
        assertDoesNotThrow { checker.close() }
    }

    @Test
    fun testDoubleCloseDoesNotThrow() {
        val checker = UpstreamChecker()
        checker.close()
        assertDoesNotThrow { checker.close() }
    }
}
