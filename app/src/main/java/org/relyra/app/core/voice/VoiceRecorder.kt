package org.relyra.app.core.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/**
 * Records a voice message to an AAC/M4A file in the app's private cache. Supports pause/resume and cancel; the file is
 * handed to the normal attachment pipeline, which encrypts it before it is stored or uploaded, and is deleted afterwards.
 */
class VoiceRecorder(context: Context) {
    class Recording(val file: File, val durationMs: Long)

    private val dir = File(context.applicationContext.cacheDir, "voice").apply { mkdirs() }
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var segmentStart = 0L
    private var accumulated = 0L
    var paused = false
        private set
    val isRecording: Boolean get() = recorder != null

    init {
        // Leftovers from a crash or an interrupted send.
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
    }

    fun elapsedMs(): Long = accumulated + if (recorder != null && !paused) SystemClock.elapsedRealtime() - segmentStart else 0L

    /** Returns false if recording could not start (e.g. the microphone is busy). */
    fun start(): Boolean {
        if (recorder != null) return false
        val out = File(dir, "rec-${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(48_000)
            r.setAudioSamplingRate(24_000)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_MS.toInt())
            r.setOutputFile(out.path)
            r.prepare()
            r.start()
            recorder = r; file = out; accumulated = 0; paused = false
            segmentStart = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    fun pause() {
        val r = recorder ?: return
        if (paused) return
        runCatching { r.pause() }.onSuccess {
            accumulated += SystemClock.elapsedRealtime() - segmentStart
            paused = true
        }
    }

    fun resume() {
        val r = recorder ?: return
        if (!paused) return
        runCatching { r.resume() }.onSuccess {
            segmentStart = SystemClock.elapsedRealtime()
            paused = false
        }
    }

    /** Stops and returns the finished recording, or null if it was too short or failed. */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val f = file
        val duration = elapsedMs()
        recorder = null; file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        paused = false
        if (!ok || f == null || !f.isFile || f.length() == 0L || duration < MIN_MS) { f?.delete(); return null }
        return Recording(f, duration)
    }

    fun cancel() {
        val r = recorder
        val f = file
        recorder = null; file = null; paused = false
        if (r != null) { runCatching { r.stop() }; runCatching { r.release() } }
        f?.delete()
    }

    companion object {
        const val MAX_MS = 5L * 60 * 1000
        const val MIN_MS = 600L

        /** "voice-<seconds>s-<timestamp>.m4a": the duration travels in the (sanitised) file name. */
        fun fileName(durationMs: Long, now: Long = System.currentTimeMillis()) = "voice-${(durationMs / 1000).coerceAtLeast(1)}s-$now.m4a"
        private val NAME = Regex("^voice-(\\d{1,4})s-\\d+\\.m4a$")
        fun isVoice(name: String, mime: String) = mime.startsWith("audio/") && NAME.matches(name)
        fun durationSeconds(name: String): Int? = NAME.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }
}
