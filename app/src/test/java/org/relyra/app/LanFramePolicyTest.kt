package org.relyra.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.LanFramePolicy

class LanFramePolicyTest {
    @Test
    fun acceptsOnlyPositiveFramesWithinConfiguredLimit() {
        assertTrue(LanFramePolicy.isValidLength(1))
        assertTrue(LanFramePolicy.isValidLength(LanFramePolicy.MAX_FRAME_BYTES))
        assertFalse(LanFramePolicy.isValidLength(0))
        assertFalse(LanFramePolicy.isValidLength(-1))
        assertFalse(LanFramePolicy.isValidLength(LanFramePolicy.MAX_FRAME_BYTES + 1))
    }
}
