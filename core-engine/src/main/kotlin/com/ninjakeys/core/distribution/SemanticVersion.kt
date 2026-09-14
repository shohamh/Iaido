package com.ninjakeys.core.distribution

data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    fun isNewerThan(other: SemanticVersion): Boolean = this > other

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("(\\d+)\\.(\\d+)\\.(\\d+)")

        fun parse(value: String): SemanticVersion {
            val match = PATTERN.matchEntire(value.trim())
                ?: throw IllegalArgumentException("Invalid semantic version: $value")
            return SemanticVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
            )
        }
    }
}
