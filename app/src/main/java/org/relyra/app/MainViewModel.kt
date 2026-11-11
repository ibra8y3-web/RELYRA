package org.relyra.app

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.relyra.app.core.database.ContactEntity
import org.relyra.app.core.database.EventType
import org.relyra.app.core.database.IdentityEntity
import org.relyra.app.core.database.SecurityEventEntity
import org.relyra.app.core.database.ChatSummary
import org.relyra.app.core.database.MessageEntity
import org.relyra.app.core.identity.AddResult
import org.relyra.app.core.messaging.EnqueueResult
import org.relyra.app.core.network.AddRelayResult
import org.relyra.app.core.network.IceServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.delay
import android.net.Uri
import org.relyra.app.core.database.AttachmentEntity
import org.relyra.app.core.messaging.AttachmentLimits
import org.relyra.app.core.messaging.AttachmentPhase
import org.relyra.app.core.messaging.StoredAttachment
import org.relyra.app.core.transfer.DownloadResult
import org.relyra.app.core.transfer.PrepareResult
import org.relyra.app.core.database.GroupDeliveryCount
import org.relyra.app.core.database.GroupEntity
import org.relyra.app.core.database.GroupMemberEntity
import org.relyra.app.core.database.GroupMessageEntity
import org.relyra.app.core.database.GroupSummary
import org.relyra.app.core.messaging.GroupKind
import org.relyra.app.core.messaging.GroupOutcome
import org.relyra.app.core.messaging.GroupResult
import org.relyra.app.background.ConnectionService
import org.relyra.app.core.database.ChatPrefEntity
import org.relyra.app.core.diagnostics.DeviceInfo
import org.relyra.app.core.diagnostics.Diagnostics
import org.relyra.app.core.diagnostics.DiagnosticsReport
import org.relyra.app.core.storage.StorageUsage
import org.relyra.app.core.storage.ThemeMode
import org.relyra.app.core.database.VaultItemEntity
import org.relyra.app.core.vault.VaultImport
import org.relyra.app.core.resources.ResourceSnapshot

sealed interface StorageState {
    data object Opening : StorageState
    data object Ready : StorageState
    class Failed(val detail: String) : StorageState
}

sealed interface IdentityState {
    data object Loading : IdentityState
    data object None : IdentityState
    class Present(val identity: IdentityEntity) : IdentityState
}

class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList())

/** Something another app shared to RELYRA (text and/or one file). The user picks the recipient. */
class ShareRequest(val text: String?, val uri: Uri?)

/** A request to open a conversation, from a notification tap. */
class OpenRequest(val id: String, val group: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val rely = app as RelyraApp
    private val prefs = app.getSharedPreferences("relyra_settings", Context.MODE_PRIVATE)

    val storage = MutableStateFlow<StorageState>(StorageState.Opening)
    val identity = MutableStateFlow<IdentityState>(IdentityState.Loading)
    val contacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val events = MutableStateFlow<List<SecurityEventEntity>>(emptyList())
    val eventCount = MutableStateFlow(0)
    val myCard = MutableStateFlow<String?>(null)
    val summaries = MutableStateFlow<List<ChatSummary>>(emptyList())
    val pendingCount = MutableStateFlow(0)
    val groups = MutableStateFlow<List<GroupEntity>>(emptyList())
    val groupSummaries = MutableStateFlow<List<GroupSummary>>(emptyList())
    val nostrKey = MutableStateFlow<String?>(null)
    val chatPrefs = MutableStateFlow<Map<String, ChatPrefEntity>>(emptyMap())
    val pendingShare = MutableStateFlow<ShareRequest?>(null)
    val openRequest = MutableStateFlow<OpenRequest?>(null)
    val diagnostics = MutableStateFlow<DiagnosticsReport?>(null)
    val resourceSnapshot = MutableStateFlow<ResourceSnapshot?>(null)
    val usage = MutableStateFlow<StorageUsage?>(null)
    val vaultItems = MutableStateFlow<List<VaultItemEntity>>(emptyList())
    /** The vault asks for biometrics or the screen lock each time; this is cleared whenever the app leaves the screen. */
    val vaultUnlocked = MutableStateFlow(false)
    val settings get() = rely.container.settings
    val relays get() = rely.container.network.relays
    val relayConnected get() = rely.container.nostr.connections
    val relayStatus get() = rely.container.network.status
    val nearbyEnabled get() = rely.container.network.nearbyEnabled
    val nearbyPeers get() = rely.container.nearby.peers
    val nearbyStatus get() = rely.container.nearby.status
    val bluetoothEnabled get() = rely.container.network.bluetoothEnabled
    val bluetoothPeers get() = rely.container.bluetooth.peers
    val bluetoothStatus get() = rely.container.bluetooth.status
    val wifiDirectEnabled get() = rely.container.network.wifiDirectEnabled
    val wifiDirectPeers get() = rely.container.wifiDirect.discovered
    val wifiDirectVerifiedPeers get() = rely.container.wifiDirect.verified
    val wifiDirectStatus get() = rely.container.wifiDirect.status
    val internetPeerStates get() = rely.container.internetP2p.peerStates
    val internetSignal = MutableStateFlow("")
    val torProxySettings get() = rely.container.network.torProxySettings
    val torProxyStatus get() = rely.container.network.torProxyStatus
    val fileServers get() = rely.container.blossom.servers
    val fileServerStatus get() = rely.container.blossom.status
    val downloadProgress get() = rely.container.transfers.progress
    val readReceipts = MutableStateFlow(prefs.getBoolean(KEY_RECEIPTS, true))

    val lockEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCK, false))
    val unlocked = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<UiMessage?>(null)
    val incomingLink = MutableStateFlow<String?>(null)
    val showRecoveryNext = MutableStateFlow(false)

    /** True while a system screen (camera scanner, share sheet) is open, so we do not re-lock on return. */
    @Volatile var externalInFlight = false

    private fun ctr() = rely.container

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val container = try {
                rely.container.also { it.open() }
            } catch (t: Throwable) {
                storage.value = StorageState.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
                return@launch
            }
            storage.value = StorageState.Ready
            if (rely.restoreApplied) message.value = UiMessage(R.string.backup_applied)
            launch {
                container.identities.observe().collect { e ->
                    identity.value = if (e == null) IdentityState.None else IdentityState.Present(e)
                    myCard.value = e?.let { runCatching { container.identities.cardUri(it) }.getOrNull() }
                    container.nostr.setIdentity(if (e == null) null else runCatching { container.identities.nostrSecret() }.getOrNull())
                    nostrKey.value = e?.let { runCatching { container.identities.nostrPublicKeyHex() }.getOrNull() }
                    container.nearby.configure(e?.publicId, container.network.nearbyEnabled.value)
                    val signer: ((ByteArray) -> ByteArray?)? =
                        if (e == null) null else { bytes -> container.identities.sign(bytes) }
                    container.bluetooth.configure(
                        publicId = e?.publicId,
                        sign = signer,
                        shouldEnable = container.network.bluetoothEnabled.value,
                    )
                    container.wifiDirect.configure(e?.publicId, container.network.wifiDirectEnabled.value)
                }
            }
            launch {
                container.network.nearbyEnabled.collect { enabled ->
                    val publicId = container.database.identityDao().get()?.publicId
                    container.nearby.configure(publicId, enabled)
                }
            }
            launch {
                container.network.bluetoothEnabled.collect { enabled ->
                    val identity = container.database.identityDao().get()
                    val signer: ((ByteArray) -> ByteArray?)? =
                        if (identity == null) null else { bytes -> container.identities.sign(bytes) }
                    container.bluetooth.configure(identity?.publicId, signer, enabled)
                }
            }
            launch {
                container.network.wifiDirectEnabled.collect { enabled ->
                    val publicId = container.database.identityDao().get()?.publicId
                    container.wifiDirect.configure(publicId, enabled)
                }
            }
            launch { container.contacts.observeAll().collect { contacts.value = it } }
            launch { container.events.observeRecent(200).collect { events.value = it } }
            launch { container.events.observeCount().collect { eventCount.value = it } }
            launch { container.messages.observeSummaries().collect { summaries.value = it } }
            launch { container.messages.observePending().collect { pendingCount.value = it } }
            launch { container.groupStore.observeGroups().collect { groups.value = it } }
            launch { container.groupStore.observeSummaries().collect { groupSummaries.value = it } }
            launch { container.vault.observe().collect { vaultItems.value = it } }
            launch { container.data.observePrefs().collect { list -> chatPrefs.value = list.associateBy { it.chatId } } }
            if (container.settings.keepConnected.value) ConnectionService.start(rely)
            container.messages.resetStaleDownloads()
            container.delivery.processOutbox()
            // While the app is open, retry whatever is due (the engine's backoff decides what is actually sent).
            while (true) {
                delay(30_000)
                runCatching { container.delivery.processOutbox() }
            }
        }
    }

    private fun fail(t: Throwable) {
        message.value = UiMessage(R.string.err_generic, listOf(t.message ?: t.javaClass.simpleName))
    }

    fun createIdentity(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                ctr().identities.create(name)
                showRecoveryNext.value = true
            } catch (t: Throwable) { fail(t) } finally { busy.value = false }
        }
    }

    fun restoreIdentity(name: String, recoveryKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                if (!ctr().identities.restore(name, recoveryKey)) message.value = UiMessage(R.string.err_recovery_invalid)
            } catch (t: Throwable) { fail(t) } finally { busy.value = false }
        }
    }

    fun rename(name: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ctr().identities.rename(name) }.onFailure(::fail) }
    }

    /** Creates a fresh one-time prekey bundle before sharing the contact card. */
    fun refreshIdentityCard(onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = ctr().database.identityDao().get() ?: return@launch
                val link = ctr().identities.cardUri(identity, rotateOneTime = true) ?: return@launch
                myCard.value = link
                withContext(Dispatchers.Main) { onReady(link) }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    suspend fun loadRecoveryKey(): String? = withContext(Dispatchers.IO) {
        val key = ctr().identities.recoveryKey()
        if (key != null) ctr().events.log(EventType.RECOVERY_VIEWED)
        key
    }

    fun resetIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ctr().identities.reset()
                prefs.edit { putBoolean(KEY_LOCK, false) }
                lockEnabled.value = false
                unlocked.value = false
                myCard.value = null
            } catch (t: Throwable) { fail(t) }
        }
    }

    fun addContact(input: String, name: String, onResult: (AddResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            val result = try { ctr().contacts.add(input, name) } catch (t: Throwable) { fail(t); null }
            busy.value = false
            if (result != null) withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun setVerified(c: ContactEntity, v: Boolean) { viewModelScope.launch(Dispatchers.IO) { ctr().contacts.setVerified(c, v) } }
    fun setBlocked(c: ContactEntity, b: Boolean) { viewModelScope.launch(Dispatchers.IO) { ctr().contacts.setBlocked(c, b) } }
    fun deleteContact(c: ContactEntity) { viewModelScope.launch(Dispatchers.IO) { ctr().contacts.delete(c) } }

    fun setLockEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOCK, enabled) }
        lockEnabled.value = enabled
        unlocked.value = true
        viewModelScope.launch(Dispatchers.IO) {
            ctr().events.log(if (enabled) EventType.APP_LOCK_ON else EventType.APP_LOCK_OFF)
        }
    }

    fun chatMessages(chatId: String): Flow<List<MessageEntity>> =
        if (storage.value is StorageState.Ready) ctr().messages.observeChat(chatId) else emptyFlow()

    fun sendMessage(chatId: String, body: String, onResult: (EnqueueResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                ctr().delivery.enqueue(chatId, body).also { ctr().delivery.processOutbox() }
            } catch (t: Throwable) { fail(t); null }
            if (result != null) withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun attachmentsOf(chatId: String): Flow<List<AttachmentEntity>> =
        if (storage.value is StorageState.Ready) ctr().messages.observeAttachments(chatId) else emptyFlow()

    /** Encrypts the picked file on this device, then queues it. The upload and delivery happen in the engine. */
    fun sendFile(chatId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val c = ctr()
            if (c.blossom.servers.value.none { it.enabled }) {
                message.value = UiMessage(R.string.err_no_file_server)
                return@launch
            }
            busy.value = true
            try {
                when (val p = c.transfers.prepare(getApplication<Application>().contentResolver, uri)) {
                    PrepareResult.Empty -> message.value = UiMessage(R.string.err_file_empty)
                    PrepareResult.TooLarge ->
                        message.value = UiMessage(R.string.err_file_too_large, listOf(AttachmentLimits.MAX_BYTES / (1024 * 1024)))
                    PrepareResult.Unreadable -> message.value = UiMessage(R.string.err_file_unreadable)
                    is PrepareResult.Ok -> {
                        val f = p.file
                        val r = c.delivery.enqueueAttachment(chatId) { id ->
                            StoredAttachment(id, f.name, f.mime, f.size, f.blobSize, f.plainSha256, f.blobSha256,
                                f.key, "", f.blobPath, AttachmentPhase.AWAITING_UPLOAD)
                        }
                        if (r is EnqueueResult.Queued) {
                            busy.value = false
                            c.delivery.processOutbox()
                        } else {
                            c.transfers.discard(f.blobPath)
                            message.value = when (r) {
                                EnqueueResult.Blocked -> UiMessage(R.string.err_msg_blocked)
                                EnqueueResult.NoEncryptionKey -> UiMessage(R.string.err_msg_no_key)
                                else -> UiMessage(R.string.err_generic, listOf(r.javaClass.simpleName))
                            }
                        }
                    }
                }
            } catch (t: Throwable) { fail(t) } finally { busy.value = false }
        }
    }

    /** Downloads are always started by the user, never automatically. */
    fun downloadAttachment(messageId: String) {
        if (settings.wifiOnlyDownloads.value && !DeviceInfo.onUnlimitedLink(rely)) {
            message.value = UiMessage(R.string.err_wifi_only)
            return
        }
        ctr().transfers.startDownload(messageId) { r ->
            if (r is DownloadResult.Failed) message.value = UiMessage(R.string.err_download, listOf(r.reason))
        }
    }

    fun exportAttachment(messageId: String, dest: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ctr().transfers.export(getApplication<Application>().contentResolver, messageId, dest)
            message.value = UiMessage(if (ok) R.string.file_saved else R.string.err_file_save)
        }
    }

    fun addFileServer(input: String): AddRelayResult = rely.container.blossom.add(input)
    fun removeFileServer(url: String) = rely.container.blossom.remove(url)
    fun setFileServerEnabled(url: String, enabled: Boolean) = rely.container.blossom.setEnabled(url, enabled)
    fun setFileServerSign(url: String, value: Boolean) = rely.container.blossom.setSignWithIdentity(url, value)
    fun testFileServer(url: String) = rely.container.blossom.test(url)

    // ---- groups and channels ---------------------------------------------------------------------------------

    fun groupMessages(id: String): Flow<List<GroupMessageEntity>> =
        if (storage.value is StorageState.Ready) ctr().groupStore.observeMessages(id) else emptyFlow()

    fun groupMembers(id: String): Flow<List<GroupMemberEntity>> =
        if (storage.value is StorageState.Ready) ctr().groupStore.observeMembers(id) else emptyFlow()

    fun groupDelivery(id: String): Flow<List<GroupDeliveryCount>> =
        if (storage.value is StorageState.Ready) ctr().groupStore.observeDelivery(id) else emptyFlow()

    fun groupOf(id: String): Flow<GroupEntity?> =
        if (storage.value is StorageState.Ready) ctr().groupStore.observeGroup(id) else emptyFlow()

    private fun groupError(r: GroupResult) {
        message.value = when (r) {
            GroupResult.OK -> return
            GroupResult.NOT_ALLOWED -> UiMessage(R.string.err_group_not_allowed)
            GroupResult.NO_RECIPIENTS -> UiMessage(R.string.err_group_no_recipients)
            GroupResult.UNKNOWN_CONTACT -> UiMessage(R.string.err_group_contact)
            GroupResult.TOO_LONG -> UiMessage(R.string.err_msg_too_long)
            GroupResult.INVALID, GroupResult.EMPTY -> UiMessage(R.string.err_group_invalid)
            else -> UiMessage(R.string.err_group_state)
        }
    }

    /** Runs a group operation off the main thread, reports a failure, then pushes whatever became due. */
    private fun groupOp(onDone: (GroupResult) -> Unit = {}, block: suspend () -> GroupResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = try { block() } catch (t: Throwable) { fail(t); return@launch }
            groupError(r)
            runCatching { ctr().delivery.processOutbox() }
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    fun createGroup(name: String, kind: GroupKind, members: List<String>, onDone: (GroupOutcome) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = try { ctr().groups.create(name, kind, members) } catch (t: Throwable) { fail(t); return@launch }
            groupError(r.result)
            runCatching { ctr().delivery.processOutbox() }
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    fun sendGroupMessage(groupId: String, body: String, onSent: () -> Unit) =
        groupOp(onDone = { if (it == GroupResult.OK) onSent() }) { ctr().groups.send(groupId, body) }

    fun addGroupMembers(groupId: String, ids: List<String>) = groupOp { ctr().groups.addMembers(groupId, ids) }
    fun removeGroupMember(groupId: String, id: String) = groupOp { ctr().groups.removeMember(groupId, id) }
    fun setGroupAdmin(groupId: String, id: String, admin: Boolean) = groupOp { ctr().groups.setAdmin(groupId, id, admin) }
    fun renameGroup(groupId: String, name: String) = groupOp { ctr().groups.rename(groupId, name) }
    fun transferGroup(groupId: String, id: String) = groupOp { ctr().groups.transferOwnership(groupId, id) }
    fun acceptGroup(groupId: String) = groupOp { ctr().groups.accept(groupId) }
    fun leaveGroup(groupId: String, onDone: () -> Unit = {}) =
        groupOp(onDone = { if (it == GroupResult.OK) onDone() }) { ctr().groups.leave(groupId) }
    fun deleteGroup(groupId: String, onDone: () -> Unit = {}) =
        groupOp(onDone = { if (it == GroupResult.OK) onDone() }) { ctr().groups.deleteLocal(groupId) }

    fun markGroupRead(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ctr().notifier.cancel(groupId)
            runCatching { ctr().groupStore.markRead(groupId) }
        }
    }

    fun cancelMessage(id: String) { viewModelScope.launch(Dispatchers.IO) { ctr().delivery.cancel(id) } }

    fun markChatRead(chatId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ctr().notifier.cancel(chatId)
            runCatching { ctr().delivery.markChatRead(chatId, readReceipts.value) }
        }
    }

    fun setReadReceipts(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_RECEIPTS, enabled) }
        readReceipts.value = enabled
    }

    fun addRelay(input: String): AddRelayResult = rely.container.network.add(input)
    fun removeRelay(url: String) = rely.container.network.remove(url)
    fun setRelayEnabled(url: String, enabled: Boolean) = rely.container.network.setEnabled(url, enabled)
    fun testRelay(url: String) = rely.container.network.test(url)
    fun setNearbyEnabled(enabled: Boolean) = ctr().network.setNearbyEnabled(enabled)
    fun setBluetoothEnabled(enabled: Boolean) = ctr().network.setBluetoothEnabled(enabled)
    fun discoverBluetoothPeers() = ctr().bluetooth.discoverBondedPeers()
    fun setWifiDirectEnabled(enabled: Boolean) = ctr().network.setWifiDirectEnabled(enabled)
    fun refreshWifiDirectPeers() = ctr().wifiDirect.refreshPeers()
    fun connectWifiDirect(deviceAddress: String) = ctr().wifiDirect.connect(deviceAddress)

    fun createInternetOffer(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val card = runCatching { ctr().internetP2p.createOffer(peerId.trim()) }.getOrNull()
            internetSignal.value = card ?: ""
            if (card == null) message.value = UiMessage(R.string.net_p2p_signal_failed)
        }
    }

    fun acceptInternetOffer(peerId: String, offer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val answer = runCatching { ctr().internetP2p.acceptOffer(peerId.trim(), offer.trim()) }.getOrNull()
            internetSignal.value = answer ?: ""
            if (answer == null) message.value = UiMessage(R.string.net_p2p_signal_failed)
        }
    }

    fun acceptInternetAnswer(peerId: String, answer: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val accepted = runCatching { ctr().internetP2p.acceptAnswer(peerId.trim(), answer.trim()) }.getOrDefault(false)
            message.value = UiMessage(if (accepted) R.string.net_p2p_answer_accepted else R.string.net_p2p_signal_failed)
        }
    }

    fun setInternetTurnServer(url: String, username: String, credential: String): Boolean =
        ctr().internetP2p.setIceServers(
            listOf(
                IceServerConfig("stun:stun.l.google.com:19302"),
                IceServerConfig("stun:stun.cloudflare.com:3478"),
                IceServerConfig(url.trim(), username, credential),
            )
        )

    fun resetInternetIceServers() {
        ctr().internetP2p.setIceServers(
            listOf(
                IceServerConfig("stun:stun.l.google.com:19302"),
                IceServerConfig("stun:stun.cloudflare.com:3478"),
            )
        )
    }

    fun closeInternetPeer(peerId: String) = ctr().internetP2p.close(peerId.trim())

    fun updateTorProxySettings(enabled: Boolean, strict: Boolean, port: Int): Boolean =
        ctr().network.updateTorProxySettings(enabled, strict, port)
    fun testTorProxy() = ctr().network.testTorProxy()

    // ---- conversation housekeeping ---------------------------------------------------------------------------

    private fun housekeeping(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { try { block() } catch (t: Throwable) { fail(t) } }
    }

    fun notificationsAllowed(): Boolean = ctr().notifier.systemAllows()
    fun cancelNotification(id: String) { ctr().notifier.cancel(id) }
    fun deleteMessage(id: String) = housekeeping { ctr().data.deleteMessage(id) }
    fun deleteGroupMessage(id: String) = housekeeping { ctr().data.deleteGroupMessage(id) }
    fun clearChat(chatId: String) = housekeeping { ctr().data.clearChat(chatId); message.value = UiMessage(R.string.chat_cleared) }
    fun clearGroupChat(groupId: String) = housekeeping { ctr().data.clearGroupChat(groupId); message.value = UiMessage(R.string.chat_cleared) }
    fun setPinned(id: String, v: Boolean) = housekeeping { ctr().data.setPinned(id, v) }
    fun setArchived(id: String, v: Boolean) = housekeeping { ctr().data.setArchived(id, v) }
    fun setMuted(id: String, v: Boolean) = housekeeping { ctr().data.setMuted(id, v) }

    // ---- settings ----------------------------------------------------------------------------------------------

    fun setKeepConnected(v: Boolean) {
        settings.setKeepConnected(v)
        if (v) ConnectionService.start(rely) else ConnectionService.stop(rely)
    }

    fun setTheme(m: ThemeMode) = settings.setTheme(m)

    fun refreshUsage() {
        viewModelScope.launch(Dispatchers.IO) { usage.value = runCatching { ctr().data.usage() }.getOrNull() }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = ctr().data.clearCache()
            usage.value = runCatching { ctr().data.usage() }.getOrNull()
            message.value = UiMessage(R.string.cache_cleared, listOf(android.text.format.Formatter.formatShortFileSize(rely, freed)))
        }
    }

    fun deleteOlderThan(days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val n = ctr().data.deleteOlderThan(days)
                usage.value = runCatching { ctr().data.usage() }.getOrNull()
                message.value = UiMessage(R.string.retention_done, listOf(n))
            } catch (t: Throwable) { fail(t) }
        }
    }

    // ---- vault -------------------------------------------------------------------------------------------------

    fun vaultImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                message.value = when (val r = ctr().vault.import(getApplication<Application>().contentResolver, uri)) {
                    is VaultImport.Ok -> UiMessage(R.string.vault_added)
                    VaultImport.Empty -> UiMessage(R.string.err_file_empty)
                    VaultImport.TooLarge -> UiMessage(R.string.err_file_too_large, listOf(org.relyra.app.core.vault.VaultEngine.MAX_BYTES / (1024 * 1024)))
                    VaultImport.Unreadable -> UiMessage(R.string.err_file_unreadable)
                }
            } catch (t: Throwable) { fail(t) } finally { busy.value = false }
        }
    }

    fun vaultExport(id: String, dest: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { ctr().vault.export(getApplication<Application>().contentResolver, id, dest) } catch (t: Throwable) { false }
            message.value = UiMessage(if (ok) R.string.file_saved else R.string.err_file_save)
        }
    }

    fun vaultVerify(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { ctr().vault.verify(id) } catch (t: Throwable) { false }
            message.value = UiMessage(if (ok) R.string.vault_intact else R.string.vault_damaged)
        }
    }

    fun vaultRename(id: String, name: String) = housekeeping { ctr().vault.rename(id, name) }
    fun vaultDelete(id: String) = housekeeping { ctr().vault.delete(id) }

    // ---- backup and restore ------------------------------------------------------------------------------------

    /** True once a verified backup is waiting for the app to be restarted. */
    val restorePending = MutableStateFlow(false)

    fun exportBackup(dest: Uri, passphrase: CharArray) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                val out = getApplication<Application>().contentResolver.openOutputStream(dest, "w")
                    ?: throw java.io.IOException("cannot write to the chosen location")
                ctr().backup.export(out, passphrase)
                message.value = UiMessage(R.string.backup_saved)
            } catch (t: Throwable) {
                message.value = UiMessage(R.string.err_backup, listOf(t.message ?: t.javaClass.simpleName))
            } finally {
                passphrase.fill('\u0000')
                busy.value = false
            }
        }
    }

    fun stageRestore(source: Uri, passphrase: CharArray) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                val input = getApplication<Application>().contentResolver.openInputStream(source)
                    ?: throw java.io.IOException("cannot read the chosen file")
                ctr().backup.stageRestore(input, passphrase)
                restorePending.value = true
            } catch (t: Throwable) {
                message.value = UiMessage(R.string.err_restore, listOf(t.message ?: t.javaClass.simpleName))
            } finally {
                passphrase.fill('\u0000')
                busy.value = false
            }
        }
    }

    fun cancelRestore() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ctr().backup.cancelPending() }
            restorePending.value = false
        }
    }

    fun refreshResourceSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            resourceSnapshot.value = runCatching { ctr().resourceManager.sample() }.getOrNull()
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            busy.value = true
            try {
                diagnostics.value = Diagnostics.collect(ctr(), rely)
            } catch (t: Throwable) { fail(t) } finally { busy.value = false }
        }
    }

    fun relock() {
        vaultUnlocked.value = false
        if (lockEnabled.value) unlocked.value = false
    }
    fun markUnlocked() { unlocked.value = true }
}

private const val KEY_LOCK = "app_lock"
private const val KEY_RECEIPTS = "read_receipts"
