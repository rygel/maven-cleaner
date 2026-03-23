package com.maven.cleaner.core

class VersionStringComparator : Comparator<String> {

    companion object {
        // Maven-compatible qualifier ordering: lower index = older version
        // A release (no qualifier) ranks higher than pre-release qualifiers
        private val QUALIFIER_ORDER = mapOf(
            "alpha" to 0, "a" to 0,
            "beta" to 1, "b" to 1,
            "milestone" to 2, "m" to 2,
            "rc" to 3, "cr" to 3,
            "snapshot" to 4,
            // "" (release) is 5 — handled via RELEASE_RANK
            "sp" to 6
        )
        private const val RELEASE_RANK = 5
        private const val UNKNOWN_QUALIFIER_RANK = -1
    }

    override fun compare(v1: String, v2: String): Int {
        val parts1 = v1.split(".", "-", "_").filter { it.isNotEmpty() }
        val parts2 = v2.split(".", "-", "_").filter { it.isNotEmpty() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrNull(i)
            val p2 = parts2.getOrNull(i)

            val cmp = compareParts(p1, p2)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun compareParts(p1: String?, p2: String?): Int {
        // A missing part means this is the release (no qualifier trailing)
        val rank1 = if (p1 == null) RELEASE_RANK else qualifierRank(p1)
        val rank2 = if (p2 == null) RELEASE_RANK else qualifierRank(p2)

        val isQualifier1 = rank1 != UNKNOWN_QUALIFIER_RANK || p1 == null
        val isQualifier2 = rank2 != UNKNOWN_QUALIFIER_RANK || p2 == null

        // Both are numeric
        val n1 = p1?.toIntOrNull()
        val n2 = p2?.toIntOrNull()
        if (n1 != null && n2 != null) {
            return n1.compareTo(n2)
        }

        // One side ran out of parts (release) vs a qualifier/number
        if (p1 == null && p2 != null) {
            // p1 is "release" rank; p2 is a qualifier or numeric sub-version
            return if (n2 != null) -1 else RELEASE_RANK.compareTo(rank2.coerceAtLeast(0))
        }
        if (p2 == null && p1 != null) {
            return if (n1 != null) 1 else rank1.coerceAtLeast(0).compareTo(RELEASE_RANK)
        }

        // Both are known qualifiers
        if (isQualifier1 && rank1 >= 0 && isQualifier2 && rank2 >= 0) {
            return rank1.compareTo(rank2)
        }

        // One known qualifier, one numeric trailing part
        if (n1 != null && isQualifier2) return 1  // numeric > qualifier
        if (n2 != null && isQualifier1) return -1  // qualifier < numeric

        // Fallback: lexicographic for unknown qualifiers
        return (p1 ?: "").compareTo(p2 ?: "")
    }

    private fun qualifierRank(part: String): Int {
        return QUALIFIER_ORDER[part.lowercase()] ?: UNKNOWN_QUALIFIER_RANK
    }
}
