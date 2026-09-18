package com.iaido.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImeEditorSnapshotTest {
    @Test
    fun parsesLengthSelectionAndHostGenerationFromOneStatusValue() {
        val snapshot = ImeEditorSnapshot.fromStatus("length=4 selection=4:4 generation=9 reset=4")

        assertNotNull(snapshot)
        assertEquals(4, snapshot?.reportedLength)
        assertEquals(4..4, snapshot?.selection)
        assertEquals(9L, snapshot?.hostGeneration)
        assertEquals(4L, snapshot?.resetRequestId)
    }
}
