package com.maven.cleaner.core

class VersionStringComparator : Comparator<String> {
    override fun compare(v1: String, v2: String): Int {
        val parts1 = v1.split(".", "-", "_").filter { it.isNotEmpty() }
        val parts2 = v2.split(".", "-", "_").filter { it.isNotEmpty() }

        val minLen = minOf(parts1.size, parts2.size)
        for (i in 0 until minLen) {
            val p1 = parts1[i]
            val p2 = parts2[i]

            val n1 = p1.toIntOrNull()
            val n2 = p2.toIntOrNull()

            if (n1 != null && n2 != null) {
                if (n1 != n2) return n1.compareTo(n2)
            } else {
                if (p1 != p2) return p1.compareTo(p2)
            }
        }
        return parts1.size.compareTo(parts2.size)
    }
}
