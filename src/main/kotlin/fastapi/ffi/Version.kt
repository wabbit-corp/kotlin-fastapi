package fastapi.ffi

// Semantic versioning
data class Version(val parts: List<Int>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in parts.indices) {
            if (i >= other.parts.size) {
                // This version has more parts, thus it's greater
                return 1
            }

            if (parts[i] != other.parts[i]) {
                // The first part that differs determines the order
                return parts[i] - other.parts[i]
            }
        }

        if (parts.size < other.parts.size) {
            // This version has less parts, thus it's smaller
            return -1
        }

        // The versions are equal
        return 0
    }
}
