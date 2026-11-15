package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.voice.VoiceRecorder

class VoiceNameTest {
    @Test fun nameRoundTripsDuration() {
        val n = VoiceRecorder.fileName(12_400, now = 1700000000000L)
        assertEquals("voice-12s-1700000000000.m4a", n)
        assertTrue(VoiceRecorder.isVoice(n, "audio/mp4"))
        assertEquals(12, VoiceRecorder.durationSeconds(n))
    }

    @Test fun veryShortRoundsUpToOneSecond() {
        assertEquals(1, VoiceRecorder.durationSeconds(VoiceRecorder.fileName(700)))
    }

    @Test fun otherFilesAreNotVoiceMessages() {
        assertFalse(VoiceRecorder.isVoice("voice-12s-1.m4a", "application/octet-stream"))
        assertFalse(VoiceRecorder.isVoice("song.m4a", "audio/mp4"))
        assertFalse(VoiceRecorder.isVoice("voice-99999s-1.m4a", "audio/mp4"))
        assertNull(VoiceRecorder.durationSeconds("song.m4a"))
    }
}
