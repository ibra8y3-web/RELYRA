package org.relyra.app

import android.content.Context
import kotlinx.coroutines.launch
import org.relyra.app.core.backup.BackupManager
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.RoomGroupStore
import org.relyra.app.core.database.RoomMessageStore
import org.relyra.app.core.identity.ContactRepository
import org.relyra.app.core.identity.IdentityRepository
import org.relyra.app.core.identity.SecurityLog
import org.relyra.app.core.messaging.DeliveryEngine
import org.relyra.app.core.messaging.Envelope
import org.relyra.app.core.messaging.EnvelopeCodec
import org.relyra.app.core.messaging.P2pSignalKind
import org.relyra.app.core.messaging.SendOutcome
import org.relyra.app.core.messaging.GroupEngine
import org.relyra.app.core.messaging.IncomingListener
import org.relyra.app.core.notify.Notifier
import org.relyra.app.core.storage.AppSettings
import org.relyra.app.core.storage.DataManager
import org.relyra.app.core.messaging.Transport
import org.relyra.app.core.network.AdaptiveRouter
import org.relyra.app.core.network.DhtDiscoveryManager
import org.relyra.app.core.network.NetworkManager
import org.relyra.app.core.network.NostrConnector
import org.relyra.app.core.network.NearbyLanConnector
import org.relyra.app.core.network.BluetoothConnector
import org.relyra.app.core.network.WifiDirectConnector
import org.relyra.app.core.network.WebRtcP2pConnector
import org.relyra.app.core.network.CallEngine
import org.relyra.app.core.messaging.PeerStatus
import org.relyra.app.core.storage.SecureStore
import org.relyra.app.core.transfer.BlossomServers
import org.relyra.app.core.transfer.TransferEngine
import org.relyra.app.core.vault.VaultEngine
import org.relyra.app.core.resources.ResourceManager
import java.util.UUID

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val secure = SecureStore(appContext)
    val settings = AppSettings(appContext)
    val resourceManager by lazy { ResourceManager(appContext) }
    val notifier by lazy { Notifier(appContext, settings) }
    val database: AppDatabase by lazy { AppDatabase.build(appContext, secure.databaseKey()) }
    val events by lazy { SecurityLog(database) }
    val identities: IdentityRepository by lazy { IdentityRepository(database, secure, events) { transfers.wipeAll(); vault.wipeFiles() } }
    val contacts: ContactRepository by lazy { ContactRepository(database, events) { ids, paths -> transfers.clean(ids, paths) } }
    val messages by lazy { RoomMessageStore(database) }
    val groupStore by lazy { RoomGroupStore(database) }
    val mls by lazy {
        org.relyra.app.core.messaging.MlsEngine(appContext, secure) { bytes -> identities.sign(bytes) }
    }
    val communityClient by lazy {
        org.relyra.app.core.community.CommunityClient(
            baseUrl = { network.discoveryGateways.value.firstOrNull { it.enabled }?.url },
            ownPublicId = { database.identityDao().get()?.publicId },
            sign = { bytes -> identities.sign(bytes) ?: throw IllegalStateException("RELYRA signing key is unavailable") },
        )
    }
    val data: DataManager by lazy {
        DataManager(
            db = database,
            cleanFiles = { ids, paths -> transfers.clean(ids, paths) },
            filesDir = appContext.filesDir,
            cacheDir = appContext.cacheDir,
            databaseFile = appContext.getDatabasePath("relyra.db"),
        )
    }

    val vault: VaultEngine by lazy {
        VaultEngine(database.vaultDao(), java.io.File(appContext.filesDir, "vault"), appContext.cacheDir)
    }

    val backup: BackupManager by lazy {
        BackupManager(database, secure, appContext.getDatabasePath("relyra.db"), java.io.File(appContext.filesDir, "restore"), appContext.filesDir, mapOf(
                "relyra_settings" to appContext.getSharedPreferences("relyra_settings", Context.MODE_PRIVATE),
                "relyra_blossom" to appContext.getSharedPreferences("relyra_blossom", Context.MODE_PRIVATE),
                "relyra_network" to appContext.getSharedPreferences("relyra_network", Context.MODE_PRIVATE),
            ))
    }

    /** Turns a NEW incoming message into a notification (never for a muted conversation, never with text unless allowed). */
    private val incomingListener = object : IncomingListener {
        override fun onMessage(conversation: String, group: Boolean, sender: String, body: String?) {
            netScope.launch {
                if (data.isMuted(conversation)) return@launch
                val senderName = database.contactDao().get(sender)?.displayName.orEmpty()
                val title = if (group) database.groupDao().get(conversation)?.name.orEmpty() else senderName
                val text = body?.let { if (group && senderName.isNotBlank()) "$senderName: $it" else it }
                notifier.postMessage(conversation, group, title.ifBlank { appContext.getString(R.string.app_name) }, text)
            }
        }

        override fun onInvited(groupId: String, name: String, invitedBy: String) {
            netScope.launch {
                if (data.isMuted(groupId)) return@launch
                val who = database.contactDao().get(invitedBy)?.displayName.orEmpty()
                notifier.postMessage(groupId, true, name, appContext.getString(R.string.notif_group_invite, who.ifBlank { "?" }))
            }
        }
    }

    val network by lazy { NetworkManager(appContext) }
    val discovery by lazy {
        DhtDiscoveryManager(
            network = network,
            ownId = { database.identityDao().get()?.publicId },
            sign = { bytes -> identities.sign(bytes) },
            scope = netScope,
        )
    }
    private val netScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val protector by lazy { org.relyra.app.core.identity.E2eeProtector(database, secure) }
    val nostr by lazy {
        NostrConnector(network, { contacts.nostrKeyOf(it) }, { delivery.acceptWire(it) }, netScope)
    }
    val nearby by lazy {
        NearbyLanConnector(
            context = appContext,
            knownPeer = { id -> contacts.peers.status(id) == PeerStatus.Known },
            sign = { bytes -> identities.sign(bytes) },
            onWire = { delivery.acceptWire(it) },
            scope = netScope,
        )
    }
    val bluetooth by lazy {
        BluetoothConnector(
            context = appContext,
            knownPeer = { id -> contacts.peers.status(id) == PeerStatus.Known },
            onWire = { delivery.acceptWire(it) },
            scope = netScope,
        )
    }
    val calls by lazy {
        CallEngine(
            context = appContext,
            ownId = { database.identityDao().get()?.publicId },
            knownPeer = { id -> contacts.peers.status(id) == PeerStatus.Known },
            sign = { bytes -> identities.sign(bytes) },
            scope = netScope,
            iceServersProvider = { discovery.fetchTurnCredentials()?.iceServers.orEmpty() },
            sendSignal = { peerId, kind, signedSignal ->
                val own = database.identityDao().get()?.publicId
                if (own == null) SendOutcome.Rejected("local RELYRA identity is unavailable")
                else delivery.transmit(peerId, EnvelopeCodec.encode(
                    Envelope.P2pSignal(UUID.randomUUID().toString(), own, peerId, kind, signedSignal)
                ))
            },
        )
    }

    val internetP2p by lazy {
        WebRtcP2pConnector(
            context = appContext,
            ownId = { database.identityDao().get()?.publicId },
            sign = { bytes -> identities.sign(bytes) },
            knownPeer = { id -> contacts.peers.status(id) == PeerStatus.Known },
            onWire = { delivery.acceptWire(it) },
            scope = netScope,
            refreshIceServers = { discovery.fetchTurnCredentials()?.iceServers },
            sendSignal = { peerId, kind, signedSignal ->
                val own = database.identityDao().get()?.publicId
                if (own == null) {
                    SendOutcome.Rejected("local RELYRA identity is unavailable")
                } else {
                    val envelope = Envelope.P2pSignal(
                        UUID.randomUUID().toString(), own, peerId, kind, signedSignal,
                    )
                    delivery.transmit(peerId, EnvelopeCodec.encode(envelope))
                }
            },
        )
    }
    val wifiDirect by lazy {
        WifiDirectConnector(
            context = appContext,
            knownPeer = { id -> contacts.peers.status(id) == PeerStatus.Known },
            sign = { bytes -> identities.sign(bytes) },
            onWire = { delivery.acceptWire(it) },
            scope = netScope,
        )
    }
    val blossom by lazy { BlossomServers(appContext) }
    val transfers: TransferEngine by lazy { TransferEngine(appContext, blossom, messages, { identities.nostrSecret() }) }
    val router by lazy { AdaptiveRouter { listOf(internetP2p, wifiDirect, bluetooth, nearby, nostr) } }
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
            incoming = incomingListener,
            onP2pSignal = { peerId, signal ->
                when (signal.kind) {
                    P2pSignalKind.OFFER -> internetP2p.requestIncomingOffer(peerId, signal.signedSignal)
                    P2pSignalKind.ANSWER -> internetP2p.acceptAnswer(peerId, signal.signedSignal)
                    P2pSignalKind.CALL_OFFER -> {
                        calls.receiveOffer(peerId, signal.signedSignal)
                        if (calls.state.value.peerId == peerId && calls.state.value.phase == org.relyra.app.core.network.CallPhase.INCOMING) {
                            val display = database.contactDao().get(peerId)?.displayName ?: peerId.take(16)
                            notifier.postCall(peerId, display)
                        }
                    }
                    P2pSignalKind.CALL_ANSWER -> calls.receiveAnswer(peerId, signal.signedSignal)
                    P2pSignalKind.CALL_DECLINE -> { calls.receiveDecline(peerId, signal.signedSignal); notifier.cancelCall(peerId) }
                    P2pSignalKind.CALL_END -> { calls.receiveEnd(peerId, signal.signedSignal); notifier.cancelCall(peerId) }
                }
            },
        )
    }

    /** Groups and channels: every packet goes out through [delivery] (one-to-one end-to-end encryption). */
    val groups: GroupEngine by lazy {
        GroupEngine(
            store = groupStore,
            peers = contacts.peers,
            ownId = { database.identityDao().get()?.publicId },
            transmit = { peer, plaintext -> delivery.transmit(peer, plaintext) },
            incoming = incomingListener,
            mls = mls,
        )
    }

    /** Forces the encrypted database to open so failures surface at startup, not mid-use. */
    fun open() {
        database.openHelper.writableDatabase
    }
}
