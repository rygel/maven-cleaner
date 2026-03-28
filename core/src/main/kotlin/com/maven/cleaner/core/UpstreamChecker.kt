package com.maven.cleaner.core

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

enum class UpstreamStatus {
    AVAILABLE,
    LOCAL_ONLY,
    UNKNOWN
}

open class UpstreamChecker : AutoCloseable {
    private val logger = LoggerFactory.getLogger(UpstreamChecker::class.java)

    companion object {
        // Matches "numFound" : <digits> in Solr JSON, extracting the count
        private val NUM_FOUND_PATTERN = Regex(""""numFound"\s*:\s*(\d+)""")
    }

    private val executor = Executors.newCachedThreadPool()

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .executor(executor)
        .build()

    private val cache = ConcurrentHashMap<String, UpstreamStatus>()

    /**
     * Checks if the given artifact exists in Maven Central.
     * Returns AVAILABLE if found, LOCAL_ONLY if confirmed absent, UNKNOWN on error.
     */
    open suspend fun checkMavenCentral(groupId: String, artifactId: String, version: String): UpstreamStatus = withContext(Dispatchers.IO) {
        val cacheKey = "$groupId:$artifactId:$version"
        cache[cacheKey]?.let { return@withContext it }

        val query = "g:$groupId AND a:$artifactId AND v:$version"
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val uri = URI.create("https://search.maven.org/solrsearch/select?q=$encodedQuery&rows=1&wt=json")

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .GET()
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> {
                    val body = response.body()
                    val status = parseNumFound(body)
                    cache[cacheKey] = status
                    return@withContext status
                }
                429 -> {
                    logger.warn("Rate limited by Maven Central, treating as unknown")
                    return@withContext UpstreamStatus.UNKNOWN
                }
                else -> {
                    logger.warn("Unexpected status {} from Maven Central for {}", response.statusCode(), cacheKey)
                    return@withContext UpstreamStatus.UNKNOWN
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to check upstream for {}: {}", cacheKey, e.message)
            return@withContext UpstreamStatus.UNKNOWN
        }
    }

    /**
     * Extracts the numFound value from Solr JSON response.
     * Handles the response format: {"response":{"numFound":N,...}}
     */
    internal fun parseNumFound(body: String): UpstreamStatus {
        val match = NUM_FOUND_PATTERN.find(body) ?: return UpstreamStatus.UNKNOWN
        val count = match.groupValues[1].toLongOrNull() ?: return UpstreamStatus.UNKNOWN
        return if (count > 0) UpstreamStatus.AVAILABLE else UpstreamStatus.LOCAL_ONLY
    }

    override fun close() {
        executor.shutdown()
    }
}
