package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.network.DiscoveryGatewayUrl
import org.relyra.app.core.network.PeerPresenceCodec

class DhtDiscoveryTest {
    @Test fun signedPresenceRecordRoundTripsWithTheRelYraIdentity() {
        val identity = IdentityCrypto.generate()
        val publicId = IdentityCrypto.publicId(identity.publicKey)
        val now = 1_800_000_000L
        val json = PeerPresenceCodec.encode(publicId, now) { bytes ->
            IdentityCrypto.sign(identity.seed, bytes)
        }
        assertNotNull(json)

        val presence = PeerPresenceCodec.decode(json!!, publicId, now + 1)
        assertNotNull(presence)
        assertEquals(publicId, presence!!.publicId)
        assertEquals(now, presence.issuedAt)
        assertEquals(now + PeerPresenceCodec.TTL_SECONDS, presence.expiresAt)
        assertEquals(listOf("nostr-signal-v1", "webrtc-data-v1"), presence.capabilities)
    }

    @Test fun presenceRejectsTamperingWrongIdentityAndExpiredLease() {
        val identity = IdentityCrypto.generate()
        val other = IdentityCrypto.generate()
        val publicId = IdentityCrypto.publicId(identity.publicKey)
        val otherId = IdentityCrypto.publicId(other.publicKey)
        val now = 1_800_000_000L
        val json = PeerPresenceCodec.encode(publicId, now) { IdentityCrypto.sign(identity.seed, it) }!!

        assertNull(PeerPresenceCodec.decode(json.replace("webrtc-data-v1", "unknown-v1"), publicId, now))
        assertNull(PeerPresenceCodec.decode(json, otherId, now))
        assertNull(PeerPresenceCodec.decode(json, publicId, now + PeerPresenceCodec.TTL_SECONDS + 1))
        assertNull(PeerPresenceCodec.decode(json + " ", publicId, now))
    }

    @Test fun discoveryGatewaysRequireHttpsAndRejectUrlCredentials() {
        assertEquals(
            "https://discovery.example.org/api",
            DiscoveryGatewayUrl.normalize("https://Discovery.Example.org/api/"),
        )
        assertNull(DiscoveryGatewayUrl.normalize("http://discovery.example.org"))
        assertNull(DiscoveryGatewayUrl.normalize("https://user:password@discovery.example.org"))
        assertNull(DiscoveryGatewayUrl.normalize("https://discovery.example.org/?token=secret"))
        assertNull(DiscoveryGatewayUrl.normalize("https://discovery.example.org:99999"))
        assertNull(DiscoveryGatewayUrl.normalize("not a url"))
    }
}
