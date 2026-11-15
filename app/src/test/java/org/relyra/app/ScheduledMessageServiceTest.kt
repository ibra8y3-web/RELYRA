package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.messaging.*

private class MemScheduled : ScheduledStore {
    val rows = LinkedHashMap<String, StoredScheduled>()
    override suspend fun insert(s: StoredScheduled) { rows[s.id] = s }
    override suspend fun get(id: String) = rows[id]
    override suspend fun update(s: StoredScheduled) { rows[s.id] = s }
    override suspend fun due(now: Long, limit: Int) = rows.values.filter { it.state == ScheduledState.PENDING && it.sendAt <= now }.take(limit)
    override suspend fun pending() = rows.values.filter { it.state == ScheduledState.PENDING }
}

class ScheduledMessageServiceTest {
    private val peer = "peer-id"

    private class Rig {
        var now = 1_000_000_000L
        val store = FakeStore()
        val sched = MemScheduled()
        val woken = mutableListOf<Pair<String, Long>>()
        val engine = DeliveryEngine(
            store = store, peers = Peers(mapOf("peer-id" to PeerStatus.Known)), protector = PlainProtector,
            transports = { emptyList() }, ownId = { "me-id" }, clock = { now },
        )
        val service = ScheduledMessageService(sched, engine, clock = { now }, wake = { id, at -> woken += id to at })
    }

    @Test fun scheduleValidatesTimeAndBody() = runBlocking {
        val r = Rig()
        assertEquals(ScheduleResult.EMPTY, r.service.schedule(peer, " ", r.now + 60_000))
        assertEquals(ScheduleResult.IN_THE_PAST, r.service.schedule(peer, "hi", r.now))
        assertEquals(ScheduleResult.TOO_FAR, r.service.schedule(peer, "hi", r.now + ScheduledMessageService.MAX_LEAD_MS + 1))
        assertEquals(ScheduleResult.UNKNOWN_PEER, r.service.schedule("stranger", "hi", r.now + 60_000))
        assertEquals(ScheduleResult.OK, r.service.schedule(peer, "hi", r.now + 60_000))
        assertEquals(1, r.woken.size)
    }

    @Test fun nothingIsSentBeforeItIsDueAndThenExactlyOnce() = runBlocking {
        val r = Rig()
        r.service.schedule(peer, "later", r.now + 60_000)
        assertEquals(0, r.service.dispatchDue())
        assertTrue(r.store.rows.isEmpty())
        r.now += 61_000
        assertEquals(1, r.service.dispatchDue())
        assertEquals(0, r.service.dispatchDue())
        val msg = r.store.rows.values.single()
        assertEquals("later", msg.body); assertEquals(peer, msg.chatId)
        assertEquals(ScheduledState.SENT, r.sched.rows.values.single().state)
    }

    @Test fun crashAfterQueueingBeforeMarkingSentNeverDuplicates() = runBlocking {
        val r = Rig()
        r.service.schedule(peer, "once", r.now + 60_000)
        val item = r.sched.rows.values.single()
        // simulate: the message was queued, but the process died before the scheduled row was updated
        r.engine.enqueueWithId(item.chatId, item.body, item.messageId)
        r.now += 61_000
        r.service.dispatchDue()
        assertEquals(1, r.store.rows.size)
        assertEquals(ScheduledState.SENT, r.sched.rows.values.single().state)
    }

    @Test fun cancelAndRescheduleOnlyWhilePending() = runBlocking {
        val r = Rig()
        r.service.schedule(peer, "a", r.now + 60_000)
        val id = r.sched.rows.keys.single()
        assertEquals(ScheduleResult.OK, r.service.reschedule(id, "b", r.now + 120_000))
        assertEquals("b", r.sched.rows[id]!!.body)
        assertEquals(ScheduleResult.OK, r.service.cancel(id))
        assertEquals(ScheduleResult.NOT_PENDING, r.service.cancel(id))
        assertEquals(ScheduleResult.NOT_PENDING, r.service.reschedule(id, "c", r.now + 120_000))
        r.now += 500_000
        assertEquals(0, r.service.dispatchDue())
        assertTrue(r.store.rows.isEmpty())
    }

    @Test fun veryLateMessageIsMarkedMissedNotSentSilently() = runBlocking {
        val r = Rig()
        r.service.schedule(peer, "old", r.now + 60_000)
        r.now += 60_000 + ScheduledMessageService.MISSED_AFTER_MS + 1
        assertEquals(0, r.service.dispatchDue())
        assertEquals(ScheduledState.MISSED, r.sched.rows.values.single().state)
        assertTrue(r.store.rows.isEmpty())
    }

    @Test fun failedPeerStateMarksFailedWithReason() = runBlocking {
        val r = Rig()
        r.service.schedule(peer, "x", r.now + 60_000)
        // peer disappears before dispatch: rebuild the engine with an unknown directory
        val engine2 = DeliveryEngine(store = r.store, peers = Peers(emptyMap()), protector = PlainProtector,
            transports = { emptyList() }, ownId = { "me-id" }, clock = { r.now })
        val service2 = ScheduledMessageService(r.sched, engine2, clock = { r.now })
        r.now += 61_000
        service2.dispatchDue()
        val s = r.sched.rows.values.single()
        assertEquals(ScheduledState.FAILED, s.state); assertNotNull(s.failReason)
    }

    @Test fun rearmAfterRestartWakesEveryPendingItem() = runBlocking {
        val r = Rig()
        r.service.schedule(peer, "a", r.now + 60_000)
        r.service.schedule(peer, "b", r.now + 90_000)
        r.woken.clear()
        r.service.rearmAll()
        assertEquals(2, r.woken.size)
    }
}
