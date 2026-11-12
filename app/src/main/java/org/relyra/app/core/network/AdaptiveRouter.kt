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
    private val deliverWire: suspend (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Connector {
    override val name = "Nostr"

    private val identitySecret = MutableStateFlow<ByteArray?>(null)
    private val pool = NostrPool(Http.client, scope, ::sinceSeconds, ::onEvent)
    val connections = pool.connected

    init {
        scope.launch {
            var previousTorSettings: TorSocksSettings? = null
            combine(network.relays, identitySecret, network.torProxySettings) { relays, secret, tor ->
                Triple(relays, secret, tor)
            }.collect { (relays, secret, torSettings) ->
                TorSocksProxySelector.configure(torSettings)
                val proxyChanged = previousTorSettings != null && previousTorSettings != torSettings
                pool.configure(
                    secret,
                    relays.filter { it.enabled }.map { it.url }.toSet(),
                    forceReconnect = proxyChanged,
                )
                previousTorSettings = torSettings
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
        // A signed event is not accepted until its encrypted RELYRA envelope authenticates.
        if (deliverWire(wire)) {
            // A relay-supplied future timestamp must not move the durable cursor beyond local time.
            network.inboxSince = maxOf(network.inboxSince, minOf(e.createdAt, clock() / 1000))
        }
    }

    override fun isReady(): Boolean = Capabilities.PAYLOAD_ENCRYPTION && identitySecret.value != null && connections.value.values.any { it }

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

enum class RouteAttemptOutcome { ACCEPTED, RELAYED, UNAVAILABLE, REJECTED, ERROR }

data class RouteAttempt(
    val path: String,
    val outcome: RouteAttemptOutcome,
    val elapsedMillis: Long,
    val completedAtMillis: Long,
    val reason: String? = null,
)

/** Tries ready connectors in priority order and retains a bounded, observable route history. */
class AdaptiveRouter(private val connectors: () -> List<Connector>) : Transport {
    override val name = "adaptive"
    override fun isReady(): Boolean = connectors().any { it.isReady() }
    val lastPath = MutableStateFlow<String?>(null)
    private val _routeAttempts = MutableStateFlow<List<RouteAttempt>>(emptyList())
    val routeAttempts: kotlinx.coroutines.flow.StateFlow<List<RouteAttempt>> = _routeAttempts

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        var sawUnavailable = false
        var rejected: SendOutcome.Rejected? = null
        for (connector in connectors().filter { it.isReady() }) {
            val started = System.nanoTime()
            val result = try {
                connector.send(toPeer, payload)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordAttempt(
                    connector.name,
                    RouteAttemptOutcome.ERROR,
                    (System.nanoTime() - started) / 1_000_000,
                    failure.message?.take(120) ?: failure.javaClass.simpleName,
                )
                sawUnavailable = true
                continue
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            when (result) {
                SendOutcome.Accepted -> {
                    recordAttempt(connector.name, RouteAttemptOutcome.ACCEPTED, elapsed, null)
                    lastPath.value = connector.name
                    return result
                }
                SendOutcome.Relayed -> {
                    recordAttempt(connector.name, RouteAttemptOutcome.RELAYED, elapsed, null)
                    lastPath.value = connector.name
                    return result
                }
                SendOutcome.Unavailable -> {
                    recordAttempt(connector.name, RouteAttemptOutcome.UNAVAILABLE, elapsed, null)
                    sawUnavailable = true
                }
                is SendOutcome.Rejected -> {
                    recordAttempt(connector.name, RouteAttemptOutcome.REJECTED, elapsed, result.reason.take(120))
                    rejected = result
                }
            }
        }
        return if (sawUnavailable) SendOutcome.Unavailable else rejected ?: SendOutcome.Unavailable
    }

    private fun recordAttempt(
        path: String,
        outcome: RouteAttemptOutcome,
        elapsedMillis: Long,
        reason: String?,
    ) {
        val attempt = RouteAttempt(path, outcome, elapsedMillis.coerceAtLeast(0), System.currentTimeMillis(), reason)
        _routeAttempts.value = (listOf(attempt) + _routeAttempts.value).take(MAX_ROUTE_HISTORY)
    }

    private companion object {
        const val MAX_ROUTE_HISTORY = 64
    }
}
