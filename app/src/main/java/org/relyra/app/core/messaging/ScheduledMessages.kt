package org.relyra.app.core.messaging

import java.time.ZoneId
import java.util.UUID

enum class ScheduledState { PENDING, SENT, CANCELLED, FAILED, MISSED }

/**
 * A message the user wants sent later. [sendAt] is an absolute instant (epoch millis), so changing the phone's time
 * zone does not move it; [zoneId] only records the zone the user picked the time in, for display.
 * [messageId] is fixed when the item is created, so a crash between "queued" and "marked sent" can never produce a
 * second message: the delivery engine treats an existing ID as already queued.
 */
data class StoredScheduled(
    val id: String,
    val chatId: String,
    val body: String,
    val sendAt: Long,
    val zoneId: String,
    val state: ScheduledState,
    val createdAt: Long,
    val messageId: String,
    val failReason: String? = null,
)

interface ScheduledStore {
    suspend fun insert(s: StoredScheduled)
    suspend fun get(id: String): StoredScheduled?
    suspend fun update(s: StoredScheduled)
    suspend fun due(now: Long, limit: Int): List<StoredScheduled>
    suspend fun pending(): List<StoredScheduled>
}

enum class ScheduleResult { OK, EMPTY, TOO_LONG, IN_THE_PAST, TOO_FAR, NOT_FOUND, NOT_PENDING, BLOCKED, UNKNOWN_PEER, NO_KEY, NO_IDENTITY }

class ScheduledMessageService(
    private val store: ScheduledStore,
    private val delivery: DeliveryEngine,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Asks the OS to wake the app at [sendAt] (WorkManager). Best effort; the periodic worker and app start are the safety net. */
    private val wake: (id: String, sendAt: Long) -> Unit = { _, _ -> },
    private val unwake: (id: String) -> Unit = {},
) {
    companion object {
        const val MIN_LEAD_MS = 5_000L
        const val MAX_LEAD_MS = 366L * 24 * 3600 * 1000
        /** A message that is more than this late (phone was off) is not sent silently; the user decides. */
        const val MISSED_AFTER_MS = 24L * 3600 * 1000
    }

    suspend fun schedule(chatId: String, body: String, sendAt: Long): ScheduleResult {
        validate(body, sendAt)?.let { return it }
        delivery.precheck(chatId)?.let { return map(it) }
        val now = clock()
        val s = StoredScheduled(newId(), chatId, body, sendAt, zone().id, ScheduledState.PENDING, now, newId())
        store.insert(s)
        wake(s.id, sendAt)
        return ScheduleResult.OK
    }

    suspend fun reschedule(id: String, body: String, sendAt: Long): ScheduleResult {
        validate(body, sendAt)?.let { return it }
        val s = store.get(id) ?: return ScheduleResult.NOT_FOUND
        if (s.state != ScheduledState.PENDING) return ScheduleResult.NOT_PENDING
        store.update(s.copy(body = body, sendAt = sendAt, zoneId = zone().id))
        wake(id, sendAt)
        return ScheduleResult.OK
    }

    suspend fun cancel(id: String): ScheduleResult {
        val s = store.get(id) ?: return ScheduleResult.NOT_FOUND
        if (s.state != ScheduledState.PENDING) return ScheduleResult.NOT_PENDING
        store.update(s.copy(state = ScheduledState.CANCELLED))
        unwake(id)
        return ScheduleResult.OK
    }

    /** Queues everything that is due. Idempotent: calling it twice, or after a crash, never duplicates a message. */
    suspend fun dispatchDue(): Int {
        val now = clock()
        var queued = 0
        for (s in store.due(now, 50)) {
            if (now - s.sendAt > MISSED_AFTER_MS) {
                store.update(s.copy(state = ScheduledState.MISSED))
                continue
            }
            when (val r = delivery.enqueueWithId(s.chatId, s.body, s.messageId)) {
                is EnqueueResult.Queued -> { store.update(s.copy(state = ScheduledState.SENT)); queued++ }
                EnqueueResult.Empty, EnqueueResult.TooLong, EnqueueResult.Blocked, EnqueueResult.UnknownPeer,
                EnqueueResult.NoEncryptionKey, EnqueueResult.NoIdentity ->
                    store.update(s.copy(state = ScheduledState.FAILED, failReason = r.javaClass.simpleName))
            }
        }
        if (queued > 0) delivery.processOutbox()
        return queued
    }

    /** Re-arms the OS wake-ups after a restart. */
    suspend fun rearmAll() {
        for (s in store.pending()) wake(s.id, s.sendAt)
    }

    private fun validate(body: String, sendAt: Long): ScheduleResult? {
        if (body.isBlank()) return ScheduleResult.EMPTY
        if (body.length > EnvelopeCodec.MAX_BODY) return ScheduleResult.TOO_LONG
        val now = clock()
        if (sendAt < now + MIN_LEAD_MS) return ScheduleResult.IN_THE_PAST
        if (sendAt > now + MAX_LEAD_MS) return ScheduleResult.TOO_FAR
        return null
    }

    private fun map(r: EnqueueResult) = when (r) {
        EnqueueResult.Blocked -> ScheduleResult.BLOCKED
        EnqueueResult.UnknownPeer -> ScheduleResult.UNKNOWN_PEER
        EnqueueResult.NoEncryptionKey -> ScheduleResult.NO_KEY
        EnqueueResult.NoIdentity -> ScheduleResult.NO_IDENTITY
        EnqueueResult.Empty -> ScheduleResult.EMPTY
        EnqueueResult.TooLong -> ScheduleResult.TOO_LONG
        is EnqueueResult.Queued -> ScheduleResult.OK
    }
}
