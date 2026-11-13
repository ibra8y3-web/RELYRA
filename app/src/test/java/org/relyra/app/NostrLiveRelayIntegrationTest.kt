package org.relyra.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.relyra.app.core.network.Hex
import org.relyra.app.core.network.NostrCrypto
import org.relyra.app.core.network.NostrEvents
import org.relyra.app.core.network.NostrPool
import org.relyra.app.core.network.RELYRA_KIND
import java.util.concurrent.TimeUnit

/**
 * Optional real-relay integration test. Set RELYRA_NOSTR_RELAY to a trusted wss:// relay
 * when deliberately running this test; ordinary CI skips it and never contacts public relays.
 */
class NostrLiveRelayIntegrationTest {
    @Test(timeout = 90_000)
    fun twoIndependentIdentitiesExchangeASignedRelyraEventThroughARealRelay() = runBlocking {
        val relay = System.getenv("RELYRA_NOSTR_RELAY")?.trim().orEmpty()
        assumeTrue("Set RELYRA_NOSTR_RELAY to run the live relay integration test", relay.startsWith("wss://"))

        val aliceSecret = NostrCrypto.randomSecret()
        val bobSecret = NostrCrypto.randomSecret()
        val alicePub = Hex.encode(NostrCrypto.publicKeyX(aliceSecret))
        val bobPub = Hex.encode(NostrCrypto.publicKeyX(bobSecret))
        val received = CompletableDeferred<org.relyra.app.core.network.NostrEvent>()
        val aliceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val alice = NostrPool(http, aliceScope, { System.currentTimeMillis() / 1000 - 30 }, onEvent = { })
        val bob = NostrPool(http, bobScope, { System.currentTimeMillis() / 1000 - 30 }, onEvent = {
            if (it.pubkey == alicePub && it.tags.any { tag -> tag.size >= 2 && tag[0] == "p" && tag[1] == bobPub }) {
                received.complete(it)
            }
        }
        try {
            alice.configure(aliceSecret, setOf(relay))
            bob.configure(bobSecret, setOf(relay))
            withTimeout(25_000) {
                while (alice.connected.value[relay] != true || bob.connected.value[relay] != true) delay(100)
            }
            alice.waitForBacklog(10_000)
            bob.waitForBacklog(10_000)

            val event = NostrEvents.create(
                aliceSecret, RELYRA_KIND, listOf(listOf("p", bobPub)),
                "RELYRA live relay integration ${System.currentTimeMillis()}",
                System.currentTimeMillis() / 1000,
            )
            assertTrue("relay did not acknowledge publish", withTimeout(15_000) { alice.publish(event) })
            val delivered = withTimeout(20_000) { received.await() }
            assertEquals(event.id, delivered.id)
            assertTrue(NostrEvents.verify(delivered))
        } finally {
            alice.configure(null, emptySet())
            bob.configure(null, emptySet())
            aliceScope.cancel()
            bobScope.cancel()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
