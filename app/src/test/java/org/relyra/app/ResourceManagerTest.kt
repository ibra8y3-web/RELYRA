package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.relyra.app.core.resources.ResourceMath

class ResourceManagerTest {
    @Test
    fun cpuIsNormalizedToAllLogicalCores() {
        assertEquals(12.5, ResourceMath.cpuPercent(cpuDeltaMs = 500, wallDeltaMs = 1_000, logicalCores = 4)!!, 0.001)
    }

    @Test
    fun cpuUsageIsBoundedToOneHundredPercentOfDeviceCapacity() {
        assertEquals(100.0, ResourceMath.cpuPercent(cpuDeltaMs = 5_000, wallDeltaMs = 1_000, logicalCores = 2)!!, 0.001)
    }

    @Test
    fun invalidCpuIntervalsAreUnavailable() {
        assertNull(ResourceMath.cpuPercent(cpuDeltaMs = -1, wallDeltaMs = 1_000, logicalCores = 4))
        assertNull(ResourceMath.cpuPercent(cpuDeltaMs = 100, wallDeltaMs = 0, logicalCores = 4))
        assertNull(ResourceMath.cpuPercent(cpuDeltaMs = 100, wallDeltaMs = 1_000, logicalCores = 0))
    }

    @Test
    fun networkRateUsesElapsedTime() {
        assertEquals(2_048L, ResourceMath.bytesPerSecond(deltaBytes = 4_096, intervalMs = 2_000))
        assertEquals(0L, ResourceMath.bytesPerSecond(deltaBytes = 0, intervalMs = 500))
    }

    @Test
    fun invalidNetworkCountersAreUnavailable() {
        assertNull(ResourceMath.bytesPerSecond(deltaBytes = -1, intervalMs = 1_000))
        assertNull(ResourceMath.bytesPerSecond(deltaBytes = 100, intervalMs = 0))
    }

    @Test
    fun counterResetOrUnsupportedCounterDoesNotProduceNegativeTraffic() {
        assertNull(ResourceMath.nonNegativeDelta(current = -1, previous = 100))
        assertNull(ResourceMath.nonNegativeDelta(current = 50, previous = 100))
        assertNull(ResourceMath.nonNegativeDelta(current = 100, previous = null))
        assertEquals(25L, ResourceMath.nonNegativeDelta(current = 125, previous = 100))
    }
}
