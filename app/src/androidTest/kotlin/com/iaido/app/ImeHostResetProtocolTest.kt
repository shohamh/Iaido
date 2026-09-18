package com.iaido.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeHostResetProtocolTest {
    @Test
    fun resetIsAcknowledgedOnlyAfterEmptyTextAndCollapsedSelection() {
        assertFalse(ImeHostResetProtocol.isAcknowledged(4L, 3L, 0, 0..0))
        assertFalse(ImeHostResetProtocol.isAcknowledged(4L, 4L, 1, 0..0))
        assertFalse(ImeHostResetProtocol.isAcknowledged(4L, 4L, 0, 0..1))
        assertTrue(ImeHostResetProtocol.isAcknowledged(4L, 4L, 0, 0..0))
    }
}
