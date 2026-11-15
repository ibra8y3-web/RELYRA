package org.relyra.app.core.messaging

import java.util.UUID

/** One person's reaction on one message. An empty [emoji] is a tombstone (removed) that blocks older updates from resurrecting it. */
data class StoredReaction(val messageId: String, val reactorId: String, val emoji: String, val updatedAt: Long)

enum class ControlKind { REACTION, EDIT }

/** A reaction/edit packet that must reach the peer. Kept in the database and re-sent until acknowledged or expired. */
class StoredControl(
    val controlId: String,
    val chatId: String,
    val targetId: String,
    val kind: ControlKind,
    val plaintext: ByteArray,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val done: Boolean,
) {
    fun copy(attempts: Int = this.attempts, nextAttemptAt: Long = this.nextAttemptAt, done: Boolean = this.done) =
        StoredControl(controlId, chatId, targetId, kind, plaintext, createdAt, attempts, nextAttemptAt, done)
}

interface ReactionStore {
    suspend fun current(messageId: String, reactorId: String): StoredReaction?
    /** Stores [r] only if it is newer than what exists for the same (message, reactor). Returns whether it was applied. */
    suspend fun applyIfNewer(r: StoredReaction): Boolean
}

interface ControlStore {
    suspend fun insert(c: StoredControl)
    suspend fun get(id: String): StoredControl?
    suspend fun update(c: StoredControl)
    suspend fun due(now: Long, limit: Int): List<StoredControl>
    /** Marks every unfinished control of [kind] for [targetId] (except [exceptId]) as done: a newer one replaces them. */
    suspend fun supersede(targetId: String, kind: ControlKind, exceptId: String)
    suspend fun purgeDone(olderThan: Long)
}

/** Runs [block] under the delivery engine's state lock so edits never interleave with outbox state writes. */
interface StateLock {
    suspend fun <T> run(block: suspend () -> T): T
}

enum class ExtraResult { OK, NOT_FOUND, NOT_ALLOWED, INVALID, TOO_LATE, UNCHANGED }

/**
 * Reactions and message edits for one-to-one chats.
 *
 * Reliability: each change is stored as a [StoredControl] and re-sent with back-off until the peer acknowledges it.
 * Conflicts: a reaction is last-writer-wins per (message, reactor) by timestamp; an edit carries a revision number
 * and receivers only accept a revision higher than the one they hold, so replays and reordering cannot roll text back.
 * Authorisation: a reaction is accepted only from the peer of that conversation; an edit only from the message's author.
 */
class MessageExtras(
    private val messages: MessageStore,
    private val reactions: ReactionStore,
    private val controls: ControlStore,
    private val ownId: suspend () -> String?,
    private val transmit: suspend (peer: String, plaintext: ByteArray) -> SendOutcome,
    private val stateLock: StateLock,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        const val EDIT_WINDOW_MS = 48L * 3600 * 1000
        const val CONTROL_TTL_MS = 7L * 24 * 3600 * 1000
        const val MAX_ATTEMPTS = 12
        const val MAX_REV = 1000
        const val CLOCK_SKEW_MS = 10L * 60 * 1000

        /** Empty (= remove) or 1..8 symbols that include at least one non-ASCII symbol; no control or blank characters. */
        fun validEmoji(s: String): Boolean {
            if (s.isEmpty()) return true
            if (s.length > EnvelopeCodec.MAX_EMOJI_CHARS || s.codePointCount(0, s.length) > 8) return false
            var symbol = false
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                if (Character.isISOControl(cp) || Character.isWhitespace(cp)) return false
                if (cp >= 0x203C) symbol = true
                i += Character.charCount(cp)
            }
            return symbol
        }
    }

    suspend fun react(chatId: String, targetId: String, emoji: String): ExtraResult {
        if (!validEmoji(emoji)) return ExtraResult.INVALID
        val me = ownId() ?: return ExtraResult.NOT_ALLOWED
        val target = messages.get(targetId) ?: return ExtraResult.NOT_FOUND
        if (target.chatId != chatId) return ExtraResult.NOT_FOUND
        if (target.outgoing && target.state !in setOf(MessageState.SENT, MessageState.RELAYED, MessageState.DELIVERED, MessageState.READ)) {
            return ExtraResult.NOT_ALLOWED
        }
        if (target.state == MessageState.CANCELLED) return ExtraResult.NOT_ALLOWED
        val existing = reactions.current(targetId, me)
        if (existing != null && existing.emoji == emoji) return ExtraResult.UNCHANGED
        val ts = maxOf(clock(), (existing?.updatedAt ?: 0L) + 1)
        reactions.applyIfNewer(StoredReaction(targetId, me, emoji, ts))
        val id = newId()
        val plain = EnvelopeCodec.encode(Envelope.Reaction(id, me, chatId, targetId, emoji, ts))
        controls.supersede(targetId, ControlKind.REACTION, id)
        controls.insert(StoredControl(id, chatId, targetId, ControlKind.REACTION, plain, clock(), 0, clock(), false))
        processControls()
        return ExtraResult.OK
    }

    suspend fun edit(messageId: String, newBody: String): ExtraResult {
        val body = newBody
        if (body.isBlank() || body.length > EnvelopeCodec.MAX_BODY) return ExtraResult.INVALID
        val me = ownId() ?: return ExtraResult.NOT_ALLOWED
        var control: StoredControl? = null
        val result = stateLock.run {
            val m = messages.get(messageId) ?: return@run ExtraResult.NOT_FOUND
            if (!m.outgoing) return@run ExtraResult.NOT_ALLOWED
            if (messages.attachment(messageId) != null) return@run ExtraResult.NOT_ALLOWED
            if (m.body == body) return@run ExtraResult.UNCHANGED
            val now = clock()
            when (m.state) {
                MessageState.QUEUED -> {
                    if (m.wirePayload == null) {
                        // Nothing has left the device yet: the message will simply be sent with the new text.
                        messages.update(m.copy(body = body))
                        return@run ExtraResult.OK
                    }
                }
                MessageState.SENT, MessageState.RELAYED, MessageState.DELIVERED, MessageState.READ -> Unit
                else -> return@run ExtraResult.NOT_ALLOWED
            }
            if (now - m.createdAt > EDIT_WINDOW_MS) return@run ExtraResult.TOO_LATE
            val rev = m.editRev + 1
            if (rev > MAX_REV) return@run ExtraResult.NOT_ALLOWED
            messages.update(m.copy(body = body, editedAt = now, editRev = rev))
            val id = newId()
            val plain = EnvelopeCodec.encode(Envelope.Edit(id, me, m.chatId, messageId, body, now, rev))
            control = StoredControl(id, m.chatId, messageId, ControlKind.EDIT, plain, now, 0, now, false)
            ExtraResult.OK
        }
        control?.let {
            controls.supersede(it.targetId, ControlKind.EDIT, it.controlId)
            controls.insert(it)
            processControls()
        }
        return result
    }

    /** Applies a packet from a peer (already authenticated and known by the delivery engine). */
    suspend fun onEnvelope(env: Envelope) {
        val me = ownId() ?: return
        when (env) {
            is Envelope.Reaction -> {
                val target = messages.get(env.targetId) ?: return   // not here yet: no ack, the sender retries
                if (target.chatId != env.from) return                // not a message of this conversation
                if (!validEmoji(env.emoji) || env.rev > clock() + CLOCK_SKEW_MS) { ack(me, env); return }
                reactions.applyIfNewer(StoredReaction(env.targetId, env.from, env.emoji, env.rev))
                ack(me, env)
            }
            is Envelope.Edit -> {
                val outcome = stateLock.run {
                    val target = messages.get(env.targetId) ?: return@run null
                    // Only the original author may edit: it must be a message we RECEIVED from this very peer.
                    if (target.outgoing || target.chatId != env.from) return@run false
                    if (messages.attachment(env.targetId) != null) return@run false
                    if (env.rev > MAX_REV || env.editedAt > clock() + CLOCK_SKEW_MS) return@run false
                    if (env.rev > target.editRev) {
                        messages.update(target.copy(body = env.body, editedAt = env.editedAt, editRev = env.rev))
                    }
                    true // newer applied, or an old/duplicate revision safely ignored
                }
                if (outcome == null) return           // original not here yet: no ack, the sender retries
                if (outcome) ack(me, env)
            }
            else -> Unit
        }
    }

    private suspend fun ack(me: String, env: Envelope) {
        transmit(env.from, EnvelopeCodec.encode(Envelope.Ack(env.messageId, me, env.from, AckKind.DELIVERED)))
    }

    /** Returns true if [ack] confirmed one of our reaction/edit packets. */
    suspend fun onAck(ack: Envelope.Ack): Boolean {
        val c = controls.get(ack.messageId) ?: return false
        if (c.chatId != ack.from) return false
        if (!c.done) controls.update(c.copy(done = true, nextAttemptAt = Long.MAX_VALUE))
        return true
    }

    /** Sends every due reaction/edit packet. Safe to call repeatedly. */
    suspend fun processControls(): Int {
        val now = clock()
        var sent = 0
        for (c in controls.due(now, 50)) {
            if (now - c.createdAt > CONTROL_TTL_MS || c.attempts >= MAX_ATTEMPTS) {
                controls.update(c.copy(done = true, nextAttemptAt = Long.MAX_VALUE))
                continue
            }
            when (transmit(c.chatId, c.plaintext)) {
                SendOutcome.Accepted, SendOutcome.Relayed -> {
                    val attempts = c.attempts + 1
                    controls.update(c.copy(attempts = attempts, nextAttemptAt = clock() + DeliveryEngine.ackWait(attempts)))
                    sent++
                }
                SendOutcome.Unavailable -> {
                    val attempts = c.attempts + 1
                    controls.update(c.copy(attempts = attempts, nextAttemptAt = clock() + DeliveryEngine.backoff(attempts)))
                }
                is SendOutcome.Rejected -> controls.update(c.copy(done = true, nextAttemptAt = Long.MAX_VALUE))
            }
        }
        controls.purgeDone(now - CONTROL_TTL_MS)
        return sent
    }
}
