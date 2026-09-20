package com.iaido.app

object ImeScenarioData {
    val englishSmoke = listOf("there", "is", "a", "ninja")

    enum class AutoSpaceFixture(val preferenceValue: String) {
        SEPARATE("separate"),
        JOIN_REEL("join_reel"),
        SPLIT_REEL("split_reel"),
        CONTEXT_REVISION("context_revision"),
        TWO_FINGER_MERGE("two_finger_merge"),
        TWO_FINGER_BOUNDARY("two_finger_boundary"),
        SIX_UNIT("six_unit"),
        LOW_CONFIDENCE("low_confidence"),
        TYPED_REEL("typed_reel"),
    }
}
