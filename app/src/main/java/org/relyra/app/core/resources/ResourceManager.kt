package org.relyra.app.core.resources

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.StatFs
import android.os.SystemClock

/**
 * A point-in-time view of resources that RELYRA can observe for its own process.
 * CPU percentage is normalized against aggregate logical CPU capacity.
 * Network rates are per-UID counters, not whole-device network totals.
 */
data class ResourceSnapshot(
    val sampledAtElapsedMs: Long,
    val appCpuPercent: Double?,
    val rxBytesPerSecond: Long?,
    val txBytesPerSecond: Long?,
    val storageAvailableBytes: Long?,
    val storageTotalBytes: Long?,
)

/** Pure calculations kept separate from Android APIs so boundary cases are JVM-testable. */
object ResourceMath {
    fun cpuPercent(cpuDeltaMs: Long, wallDeltaMs: Long, logicalCores: Int): Double? {
        if (cpuDeltaMs < 0 || wallDeltaMs <= 0 || logicalCores <= 0) return null
        val capacity = wallDeltaMs.toDouble() * logicalCores.toDouble()
        if (capacity <= 0.0) return null
        return (cpuDeltaMs.toDouble() * 100.0 / capacity).coerceIn(0.0, 100.0)
    }

    fun bytesPerSecond(deltaBytes: Long, intervalMs: Long): Long? {
        if (deltaBytes < 0 || intervalMs <= 0) return null
        val rate = deltaBytes.toDouble() * 1_000.0 / intervalMs.toDouble()
        return rate.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }

    fun nonNegativeDelta(current: Long, previous: Long?): Long? {
        if (current < 0 || previous == null || previous < 0 || current < previous) return null
        return current - previous
    }
}

/**
 * Samples real Android process/device counters. Call from a background dispatcher.
 * The first sample establishes a baseline; rates and CPU usage become available on the next sample.
 * Unsupported per-UID traffic counters are represented as null instead of fabricated zeros.
 */
class ResourceManager(
    context: Context,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val processCpuTime: () -> Long = { Process.getElapsedCpuTime() },
    private val rxCounter: () -> Long = { TrafficStats.getUidRxBytes(Process.myUid()) },
    private val txCounter: () -> Long = { TrafficStats.getUidTxBytes(Process.myUid()) },
    private val logicalCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    private val storageReader: (String) -> Pair<Long, Long> = { path ->
        val fs = StatFs(path)
        fs.availableBytes to fs.totalBytes
    },
) {
    private val appContext = context.applicationContext
    private val storagePath = appContext.filesDir.absolutePath

    private var previousElapsedMs: Long? = null
    private var previousCpuMs: Long? = null
    private var previousRxBytes: Long? = null
    private var previousTxBytes: Long? = null

    @Synchronized
    fun sample(): ResourceSnapshot {
        val now = elapsedRealtime()
        val cpuNow = processCpuTime()
        val rxNow = rxCounter()
        val txNow = txCounter()

        val elapsedDelta = previousElapsedMs?.let { now - it }
        val cpuDelta = ResourceMath.nonNegativeDelta(cpuNow, previousCpuMs)
        val rxDelta = ResourceMath.nonNegativeDelta(rxNow, previousRxBytes)
        val txDelta = ResourceMath.nonNegativeDelta(txNow, previousTxBytes)

        val cpuPercent = if (elapsedDelta != null && cpuDelta != null) {
            ResourceMath.cpuPercent(cpuDelta, elapsedDelta, logicalCores)
        } else null
        val rxRate = if (elapsedDelta != null && rxDelta != null) {
            ResourceMath.bytesPerSecond(rxDelta, elapsedDelta)
        } else null
        val txRate = if (elapsedDelta != null && txDelta != null) {
            ResourceMath.bytesPerSecond(txDelta, elapsedDelta)
        } else null

        previousElapsedMs = now
        previousCpuMs = cpuNow.takeIf { it >= 0 }
        previousRxBytes = rxNow.takeIf { it >= 0 }
        previousTxBytes = txNow.takeIf { it >= 0 }

        val storage = runCatching { storageReader(storagePath) }.getOrNull()
        return ResourceSnapshot(
            sampledAtElapsedMs = now,
            appCpuPercent = cpuPercent,
            rxBytesPerSecond = rxRate,
            txBytesPerSecond = txRate,
            storageAvailableBytes = storage?.first?.takeIf { it >= 0 },
            storageTotalBytes = storage?.second?.takeIf { it >= 0 },
        )
    }
}
