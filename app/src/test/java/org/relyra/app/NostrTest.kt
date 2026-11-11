package org.relyra.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.network.Hex
import org.relyra.app.core.network.NostrCrypto
import org.relyra.app.core.network.NostrEvents
import org.relyra.app.core.network.RelayMessage
import org.relyra.app.core.network.RelayProtocol
import org.relyra.app.core.network.RelayUrl

class NostrTest {
    private val secret = ByteArray(32).also { it[31] = 3 }

    @Test fun bip340PublicKeyVector() {
        assertEquals(
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            Hex.encode(NostrCrypto.publicKeyX(secret)),
        )
    }

    @Test fun derivedSecretIsDeterministicAndValid() {
        val seed = ByteArray(32) { it.toByte() }
        val a = NostrCrypto.deriveSecret(seed)
        assertEquals(Hex.encode(a), Hex.encode(NostrCrypto.deriveSecret(seed)))
        assertEquals(32, NostrCrypto.publicKeyX(a).size)
    }

    @Test fun createdEventVerifiesAndTamperingFails() {
        val e = NostrEvents.create(secret, 1, listOf(listOf("p", "abc")), "hello / \"world\"\nline2 مرحبا", 1_700_000_000)
        assertTrue(NostrEvents.verify(e))
        val tampered = org.relyra.app.core.network.NostrEvent(e.id, e.pubkey, e.createdAt, e.kind, e.tags, e.content + "x", e.sig)
        assertFalse(NostrEvents.verify(tampered))
    }

    @Test fun serializationFollowsNip01Escaping() {
        val s = NostrEvents.serializeForId("pk", 5, 1, emptyList(), "a\"b\\c\n/\u0001")
        assertEquals("[0,\"pk\",5,1,[],\"a\\\"b\\\\c\\n/\\u0001\"]", s)
    }

    @Test fun eventSurvivesJsonRoundTripThroughRelayMessage() {
        val e = NostrEvents.create(secret, 1, listOf(listOf("t", "x/y")), "مرحبا 👋 /path", 1_700_000_000)
        val wire = "[\"EVENT\",\"sub1\",${NostrEvents.toJson(e)}]"
        val m = RelayProtocol.parse(wire) as RelayMessage.Event
        assertEquals("sub1", m.subId)
        assertTrue(NostrEvents.verify(m.event))
        assertEquals(e.content, m.event.content)
    }

    @Test fun relayMessagesParse() {
        assertTrue(RelayProtocol.parse("[\"EOSE\",\"s\"]") is RelayMessage.Eose)
        val ok = RelayProtocol.parse("[\"OK\",\"id\",false,\"blocked: spam\"]") as RelayMessage.Ok
        assertFalse(ok.accepted)
        assertTrue(RelayProtocol.parse("[\"NOTICE\",\"hi\"]") is RelayMessage.Notice)
        assertTrue(RelayProtocol.parse("[\"CLOSED\",\"s\",\"auth-required\"]") is RelayMessage.Closed)
        assertNull(RelayProtocol.parse("not json"))
        assertNull(RelayProtocol.parse("[\"WHAT\"]"))
    }

    @Test fun clientMessagesAreValidJson() {
        val req = JSONArray(RelayProtocol.req("s1", "{\"kinds\":[1],\"limit\":1}"))
        assertEquals("REQ", req.getString(0))
        assertEquals(1, req.getJSONObject(2).getInt("limit"))
        val e = NostrEvents.create(secret, 1, emptyList(), "x", 1)
        assertEquals("EVENT", JSONArray(RelayProtocol.event(e)).getString(0))
    }

    @Test fun relayUrlNormalisation() {
        assertEquals("wss://relay.example.com", RelayUrl.normalize("relay.example.com"))
        assertEquals("wss://relay.example.com", RelayUrl.normalize("  WSS://Relay.Example.com/  "))
        assertEquals("wss://relay.example.com:8443/nostr", RelayUrl.normalize("wss://relay.example.com:8443/nostr/"))
        assertNull(RelayUrl.normalize("ws://relay.example.com"))
        assertNull(RelayUrl.normalize("https://relay.example.com"))
        assertNull(RelayUrl.normalize("wss://user@relay.example.com"))
        assertNull(RelayUrl.normalize("wss://localhost"))
        assertNull(RelayUrl.normalize("not a url"))
        assertNull(RelayUrl.normalize(""))
    }
    @Test fun rejectsMalformedEventFieldsBeforeVerification() {
        assertNull(RelayProtocol.parse("""["EVENT","s",{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pubkey":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","created_at":-1,"kind":1,"tags":[],"content":"x","sig":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]"""))
        assertNull(RelayProtocol.parse("""["EVENT","s",{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pubkey":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","created_at":1,"kind":-1,"tags":[],"content":"x","sig":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]"""))
        assertNull(RelayProtocol.parse("""["EVENT","s",{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pubkey":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","created_at":1,"kind":1,"tags":[[1]],"content":"x","sig":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]"""))
        assertNull(RelayProtocol.parse("""["EVENT","s",{"id":"bad","pubkey":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","created_at":1,"kind":1,"tags":[],"content":"x","sig":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]"""))
    }

}
