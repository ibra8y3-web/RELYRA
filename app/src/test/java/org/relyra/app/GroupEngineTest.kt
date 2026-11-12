        )
        val admin = GroupEngine(
            store = store,
            peers = Peers(mapOf("alice" to PeerStatus.Known, "bob" to PeerStatus.Known,
                "carol" to PeerStatus.Known, "dave" to PeerStatus.Known)),
            ownId = { "bob" },
            transmit = { _, _ -> SendOutcome.Unavailable },
        )

        assertEquals(GroupResult.PROPOSAL_QUEUED, admin.addMembers(gid, listOf("dave")))
        assertEquals(GroupResult.MLS_UNAVAILABLE, admin.removeMember(gid, "carol"))
        assertEquals(GroupResult.NOT_ALLOWED, admin.setAdmin(gid, "carol", true))
        assertEquals(setOf("alice", "bob", "carol"), store.members(gid).map { it.id }.toSet())
        assertEquals(7L, store.group(gid)!!.epoch)
        assertEquals(4L, store.group(gid)!!.mlsEpoch)

        // A metadata-only change is safe for an admin; it advances only the snapshot epoch,
        // never the cryptographic MLS epoch.
        assertEquals(GroupResult.OK, admin.rename(gid, "MLS team renamed"))
        assertEquals(8L, store.group(gid)!!.epoch)
        assertEquals(4L, store.group(gid)!!.mlsEpoch)
    }

    @Test fun concurrentMlsAdminMembershipProposalsAreSerializedByOwner() = runBlocking {
        val gid = UUID.randomUUID().toString()
        val members = listOf(