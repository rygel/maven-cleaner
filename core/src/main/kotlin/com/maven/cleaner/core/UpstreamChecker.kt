package com.maven.cleaner.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpstreamChecker {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
        
    private val cache = ConcurrentHashMap<String, Boolean>()

    /**
     * Checks if the given artifact exists in Maven Central.
     * Returns true if it exists, false if it's local only.
     */
    suspend fun checkMavenCentral(groupId: String, artifactId: String, version: String): Boolean = withContext(Dispatchers.IO) {
        val cacheKey = "$groupId:$artifactId:$version"
        cache[cacheKey]?.let { return@withContext it }

        val query = "g:$groupId AND a:$artifactId AND v:$version"
        val encodedQuery = query.replace(" ", "%20")
        val uri = URI.create("https://search.maven.org/solrsearch/select?q=$encodedQuery&rows=1&wt=json")

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .GET()
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val body = response.body()
                // Simple check for "numFound":0 in the JSON response
                val exists = !body.contains("\"numFound\":0")
                cache[cacheKey] = exists
                return@withContext exists
            }
        } catch (e: Exception) {
            // If check fails, assume it's unknown/local to be safe, but don't cache
            return@withContext false
        }
        return@withContext false
    }
}
