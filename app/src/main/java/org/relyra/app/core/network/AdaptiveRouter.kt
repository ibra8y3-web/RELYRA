package org.relyra.app.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.relyra.app.core.messaging.SendOutcome
import org.relyra.app.core.messaging.Transport

/** True since Phase 5: every payload is end-to-end encrypted before any connector sees it. */
object Capabilities {
    const val PAYLOAD_ENCRYPTION = true
}

interface Connector : Transport {
    override fun isReady(): Boolean
}

/**
 * Nostr connector. Payloads arriving here are already end-to-end encrypted. Each is wrapped in a signed event
 * (kind [RELYRA_KIND], tagged with the recipient's Nostr key) and published to every connected relay.
 */
class NostrConnector(
    private val network: NetworkManager,
    private val nostrKeyOf: suspend (String) -> ByteArray?,
    private val deliverWire: suspend (ByteArray) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Connector {
    override val name = "Nostr"

    private val identitySecret = MutableStateFlow<ByteArray?>(null)
    private val pool = NostrPool(Http.client, scope, ::sinceSeconds, ::onEvent)
    val connections = pool.connected

    init {
        scope.launch {
            combine(network.relays, identitySecret) { relays, secret -> relays to secret }.collect { (relays, secret) ->
                pool.configure(secret, relays.filter { it.enabled }.map { it.url }.toSet())
            }
        }
    }

    fun setIdentity(secret: ByteArray?) { identitySecret.value = secret }

    /** For the background worker: connect, fetch the backlog, process it. */
    suspend fun syncOnce(secret: ByteArray?, timeoutMs: Long) {
        identitySecret.value = secret
        pool.configure(secret, network.relays.value.filter { it.enabled }.map { it.url }.toSet())
        pool.waitForBacklog(timeoutMs)
    }

    private fun sinceSeconds(): Long {
        val last = network.inboxSince
        return if (last == 0L) clock() / 1000 - 3 * 86_400 else maxOf(0, last - 600)
    }

    private suspend fun onEvent(e: NostrEvent) {
        val me = identitySecret.value?.let { Hex.encode(NostrCrypto.publicKeyX(it)) } ?: return
        if (e.kind != RELYRA_KIND || !NostrEvents.verify(e)) return
        if (e.tags.none { it.size >= 2 && it[0] == "p" && it[1] == me }) return
        val wire = try { java.util.Base64.getDecoder().decode(e.content) } catch (ex: IllegalArgumentException) { return }
        deliverWire(wire)
        network.inboxSince = maxOf(network.inboxSince, e.createdAt)
    }

    override fun isReady(): Boolean = Capabilities.PAYLOAD_ENCRYPTION && network.relays.value.any { it.enabled }

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        val secret = identitySecret.value ?: return SendOutcome.Unavailable
        val peerKey = nostrKeyOf(toPeer) ?: return SendOutcome.Rejected("peer has no Nostr key")
        val event = NostrEvents.create(
            secret, RELYRA_KIND, listOf(listOf("p", Hex.encode(peerKey))),
            java.util.Base64.getEncoder().encodeToString(payload), clock() / 1000,
        )
        return if (pool.publish(event)) SendOutcome.Relayed else SendOutcome.Unavailable
    }
}

/** Tries ready connectors in the user's priority order and remembers which path carried the last message. */
class AdaptiveRouter(private val connectors: () -> List<Connector>) : Transport {
    override val name = "adaptive"
    override fun isReady(): Boolean = connectors().any { it.isReady() }
    val lastPath = MutableStateFlow<String?>(null)

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        var sawUnavailable = false
        var rejected: SendOutcome.Rejected? = null
        for (c in connectors().filter { it.isReady() }) {
            when (val r = c.send(toPeer, payload)) {
                SendOutcome.Accepted, SendOutcome.Relayed -> { lastPath.value = c.name; return r }
                SendOutcome.Unavailable -> sawUnavailable = true
                is SendOutcome.Rejected -> rejected = r
            }
        }
        return if (sawUnavailable) SendOutcome.Unavailable else rejected ?: SendOutcome.Unavailable
    }
}
