package org.relyra.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.community.CommunityClient
import org.relyra.app.core.community.CommunityEvent
import org.relyra.app.core.community.CommunityManifest
import org.relyra.app.core.crypto.IdentityCrypto
import java.util.Base64

class CommunityClientTest {
    @Test fun verifiesOwnerSignedManifestAndRejectsTampering() {
        val owner = IdentityCrypto.generate()
        val ownerId = IdentityCrypto.publicId(owner.publicKey)
        val now = 1_800_000_000_000L
        val unsigned = CommunityManifest(
            communityId = "a1b2c3d4-e5f6-4789-8abc-0123456789ab",
            ownerPublicId = ownerId,
            name = "Relyra Community",
            description = "A signed public space",
            visibility = "public",
            moderatorPublicIds = emptyList(),
            policyVersion = 1,
            createdAt = now,
            updatedAt = now,
            signature = "",
        )
        val payload = listOf(
            "RELYRA-COMMUNITY-MANIFEST-V1",
            unsigned.communityId.lowercase(),
            unsigned.ownerPublicId.lowercase(),
            unsigned.name,
            unsigned.description,
            unsigned.visibility,
            "",
            unsigned.policyVersion.toString(),
            unsigned.createdAt.toString(),
            unsigned.updatedAt.toString(),
        ).joinToString("\n").toByteArray()
        val signed = unsigned.copy(
            signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(IdentityCrypto.sign(owner.seed, payload))
        )
        val client = CommunityClient(
            baseUrl = { null },
            ownPublicId = { ownerId },
            sign = { bytes -> IdentityCrypto.sign(owner.seed, bytes) },
        )

        assertTrue(client.verifyManifest(signed))
        assertFalse(client.verifyManifest(signed.copy(name = "Tampered name")))
        assertFalse(client.verifyManifest(signed.copy(ownerPublicId = IdentityCrypto.publicId(IdentityCrypto.generate().publicKey))))
    }

    @Test fun verifiesModeratorEventOnlyForCurrentSignedPolicy() {
        val owner = IdentityCrypto.generate()
        val moderator = IdentityCrypto.generate()
        val stranger = IdentityCrypto.generate()
        val subject = IdentityCrypto.generate()
        val ownerId = IdentityCrypto.publicId(owner.publicKey)
        val moderatorId = IdentityCrypto.publicId(moderator.publicKey)
        val now = 1_800_000_000_000L
        val manifestUnsigned = CommunityManifest(
            "a1b2c3d4-e5f6-4789-8abc-0123456789ab", ownerId, "Community", "Policy",
            "public", listOf(moderatorId), 2, now, now, "",
        )
        val manifestPayload = listOf(
            "RELYRA-COMMUNITY-MANIFEST-V1",
            manifestUnsigned.communityId.lowercase(), ownerId.lowercase(),
            manifestUnsigned.name, manifestUnsigned.description, manifestUnsigned.visibility,
            listOf(moderatorId).sorted().joinToString(","),
            "2", now.toString(), now.toString(),
        ).joinToString("\n").toByteArray()
        val manifest = manifestUnsigned.copy(
            signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(IdentityCrypto.sign(owner.seed, manifestPayload))
        )
        val eventUnsigned = CommunityEvent(
            "b1b2c3d4-e5f6-4789-8abc-0123456789ab",
            manifest.communityId, moderatorId, 1, 2, "mute",
            IdentityCrypto.publicId(subject.publicKey), "spam", now, now + 60_000, "",
        )
        val eventPayload = listOf(
            "RELYRA-COMMUNITY-MODERATION-V1",
            eventUnsigned.eventId.lowercase(), eventUnsigned.communityId.lowercase(),
            moderatorId.lowercase(), "1", "2", "mute",
            eventUnsigned.subjectPublicId.lowercase(), "spam",
            now.toString(), (now + 60_000).toString(),
        ).joinToString("\n").toByteArray()
        val signedEvent = eventUnsigned.copy(
            signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(IdentityCrypto.sign(moderator.seed, eventPayload))
        )
        val client = CommunityClient({ null }, { ownerId }, { IdentityCrypto.sign(owner.seed, it) })
        assertTrue(client.verifyManifest(manifest))
        assertTrue(client.verifyEvent(signedEvent, manifest))
        assertFalse(client.verifyEvent(signedEvent.copy(action = "ban"), manifest))
        val stalePolicy = manifest.copy(policyVersion = 3)
        assertFalse(client.verifyEvent(signedEvent, stalePolicy))
        val strangerEvent = signedEvent.copy(actorPublicId = IdentityCrypto.publicId(stranger.publicKey))
        assertFalse(client.verifyEvent(strangerEvent, manifest))
    }
}
