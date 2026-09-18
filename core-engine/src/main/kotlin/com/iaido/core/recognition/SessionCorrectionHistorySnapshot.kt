package com.iaido.core.recognition

data class SessionCorrectionHistorySnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val nextId: Int,
    val entries: List<SessionWord>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported correction-history snapshot version: $schemaVersion" }
        require(nextId >= 0) { "Next correction-history ID must not be negative" }
        require(entries.map { it.id }.distinct().size == entries.size) {
            "Correction history contains duplicate IDs"
        }
        require(entries.all { it.start >= 0 && it.end >= it.start }) {
            "Correction-history ranges must be ordered and non-negative"
        }
        require(entries.all { it.candidates.isNotEmpty() }) {
            "Correction-history entries must retain at least one candidate"
        }
        val maximumId = entries.maxOfOrNull { it.id } ?: -1
        require(nextId > maximumId) { "Next correction-history ID must follow all entry IDs" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
