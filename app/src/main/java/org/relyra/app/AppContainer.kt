package org.relyra.app

import android.content.Context
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.RoomGroupStore
import org.relyra.app.core.database.RoomMessageStore
import org.relyra.app.core.identity.ContactRepository
import org.relyra.app.core.identity.IdentityRepository
import org.relyra.app.core.identity.SecurityLog
import org.relyra.app.core.messaging.DeliveryEngine
import org.relyra.app.core.messaging.GroupEngine
import org.relyra.app.core.messaging.Transport
import org.relyra.app.core.network.AdaptiveRouter
import org.relyra.app.core.network.NetworkManager
import org.relyra.app.core.network.NostrConnector
import org.relyra.app.core.storage.SecureStore
import org.relyra.app.core.transfer.BlossomServers
import org.relyra.app.core.transfer.TransferEngine

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val secure = SecureStore(appContext)
    val database: AppDatabase by lazy { AppDatabase.build(appContext, secure.databaseKey()) }
    val events by lazy { SecurityLog(database) }
    val identities: IdentityRepository by lazy { IdentityRepository(database, secure, events) { transfers.wipeAll() } }
    val contacts: ContactRepository by lazy { ContactRepository(database, events) { ids, paths -> transfers.clean(ids, paths) } }
    val messages by lazy { RoomMessageStore(database) }
    val groupStore by lazy { RoomGroupStore(database) }

    val network by lazy { NetworkManager(appContext) }
    private val netScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val protector by lazy { org.relyra.app.core.identity.E2eeProtector(database, secure) }
    val nostr by lazy {
        NostrConnector(network, { contacts.nostrKeyOf(it) }, { delivery.onWire(it) }, netScope)
    }
    val blossom by lazy { BlossomServers(appContext) }
    val transfers: TransferEngine by lazy { TransferEngine(appContext, blossom, messages, { identities.nostrSecret() }) }
    val router by lazy { AdaptiveRouter { listOf(nostr) } }
    val transports: List<Transport> by lazy { listOf(router) }

    val delivery: DeliveryEngine by lazy {
        DeliveryEngine(
            store = messages,
            peers = contacts.peers,
            protector = protector,
            transports = { transports },
            ownId = { database.identityDao().get()?.publicId },
            uploader = transfers,
            groups = groups,
        )
    }

    /** Groups and channels: every packet goes out through [delivery] (one-to-one end-to-end encryption). */
    val groups: GroupEngine by lazy {
        GroupEngine(
            store = groupStore,
            peers = contacts.peers,
            ownId = { database.identityDao().get()?.publicId },
            transmit = { peer, plaintext -> delivery.transmit(peer, plaintext) },
        )
    }

    /** Forces the encrypted database to open so failures surface at startup, not mid-use. */
    fun open() {
        database.openHelper.writableDatabase
    }
}
