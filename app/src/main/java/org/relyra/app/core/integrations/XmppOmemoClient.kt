package org.relyra.app.core.integrations

import android.content.Context
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback
import org.jivesoftware.smackx.omemo.trust.TrustState
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smackx.omemo.OmemoMessage
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jxmpp.jid.BareJid
import org.jxmpp.jid.impl.JidCreate
import java.io.File
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * XMPP TCP + XEP-0384 OMEMO adapter.
 * Unknown devices remain undecided and encryption fails closed until the user verifies/trusts
 * the fingerprint. The store is app-private; a Keystore-backed custom OMEMO store is still needed
 * before claiming hardware-bound encryption at rest for OMEMO state.
 */

data class XmppIncomingMessage(val from: String, val body: String, val receivedAt: Long)

class XmppOmemoClient(context: Context) {
    private val _incoming = MutableStateFlow<List<XmppIncomingMessage>>(emptyList())
    val incoming: StateFlow<List<XmppIncomingMessage>> = _incoming
    private val incomingListener = object : OmemoMessageListener {
        override fun onOmemoMessageReceived(stanza: Stanza, decryptedMessage: OmemoMessage.Received) {
            val body = decryptedMessage.body ?: return
            val from = stanza.from?.toString() ?: return
            _incoming.value = (_incoming.value + XmppIncomingMessage(from, body, System.currentTimeMillis())).takeLast(200)
        }
        override fun onOmemoCarbonCopyReceived(direction: CarbonExtension.Direction, carbonCopy: Message, wrappingMessage: Message, decryptedCarbonCopy: OmemoMessage.Received) {
            val body = decryptedCarbonCopy.body ?: return
            val from = carbonCopy.from?.toString() ?: return
            _incoming.value = (_incoming.value + XmppIncomingMessage(from, body, System.currentTimeMillis())).takeLast(200)
        }
    }

    private val app = context.applicationContext
    private var connection: XMPPTCPConnection? = null
    private var manager: OmemoManager? = null
    private val trustPrefs = app.getSharedPreferences("relyra_xmpp_trust", Context.MODE_PRIVATE)
    private val trust = ConcurrentHashMap<String, TrustState>().apply { putAll(loadTrustDecisions()) }

    @Synchronized
    fun connect(domain: String, host: String, port: Int, username: String, password: String): String {
        require(domain.matches(Regex("[A-Za-z0-9.-]{1,253}")))
        require(host.isNotBlank() && port in 1..65535 && username.isNotBlank() && password.isNotEmpty())
        disconnect()
        AndroidSmackInitializer.initialize(app)
        synchronized(SmackOmemoSetup) {
            if (!SmackOmemoSetup.initialized) {
                SignalOmemoService.acknowledgeLicense()
                SignalOmemoService.setup()
                val service = SignalOmemoService.getInstance() as SignalOmemoService
                val storeDirectory = File(app.noBackupFilesDir, "xmpp-omemo").apply { mkdirs() }
                service.setOmemoStoreBackend(
                    SignalCachingOmemoStore(SignalFileBasedOmemoStore(storeDirectory))
                )
                SmackOmemoSetup.initialized = true
            }
        }
        val config = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(JidCreate.domainBareFrom(domain))
            .setHost(host)
            .setPort(port)
            .setSecurityMode(org.jivesoftware.smack.ConnectionConfiguration.SecurityMode.required)
            .setUsernameAndPassword(username, password)
            .build()
        val client = XMPPTCPConnection(config)
        try {
            val omemo = OmemoManager.getInstanceFor(client)
            omemo.setTrustCallback(object : OmemoTrustCallback {
                override fun getTrust(device: OmemoDevice, fingerprint: OmemoFingerprint): TrustState =
                    trust[key(device, fingerprint)] ?: TrustState.undecided
                override fun setTrust(device: OmemoDevice, fingerprint: OmemoFingerprint, state: TrustState) {
                    synchronized(trust) {
                        trust[key(device, fingerprint)] = state
                        runCatching { saveTrustDecisions() }
                    }
                }
            })
            omemo.addOmemoMessageListener(incomingListener)
            client.connect()
            client.login()
            omemo.initialize()
            connection = client
            manager = omemo
            return omemo.ownJid.toString()
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            throw t
        }
    }

    fun knownDevices(jid: String): List<XmppOmemoFingerprint> {
        val omemo = requireManager()
        val bare = JidCreate.bareFrom(jid)
        omemo.requestDeviceListUpdateFor(bare)
        return omemo.getDevicesOf(bare).map { device ->
            val fingerprint = omemo.getFingerprint(device)
            XmppOmemoFingerprint(device, fingerprint.toString(), omemo.isTrustedOmemoIdentity(device, fingerprint), fingerprint)
        }
    }

    /** Call only after the user has compared the displayed fingerprint through another channel. */
    fun trustFingerprint(device: OmemoDevice, fingerprint: OmemoFingerprint) {
        requireManager().trustOmemoIdentity(device, fingerprint)
    }

    fun distrustFingerprint(device: OmemoDevice, fingerprint: OmemoFingerprint) {
        requireManager().distrustOmemoIdentity(device, fingerprint)
    }

    /** Applies a trust decision only to a currently advertised device and exact fingerprint. */
    fun setDeviceTrust(jid: String, fingerprintText: String, trusted: Boolean) {
        val match = knownDevices(jid).firstOrNull { it.fingerprint == fingerprintText }
            ?: error("OMEMO fingerprint is no longer advertised by this contact")
        if (trusted) trustFingerprint(match.device, match.rawFingerprint)
        else distrustFingerprint(match.device, match.rawFingerprint)
    }

    @Synchronized
    fun sendEncrypted(recipient: String, plaintext: String) {
        require(plaintext.isNotBlank() && plaintext.length <= 65536)
        val client = connection?.takeIf { it.isAuthenticated } ?: error("XMPP is not connected")
        val omemo = requireManager()
        val jid: BareJid = JidCreate.bareFrom(recipient)
        val encrypted = omemo.encrypt(jid, plaintext)
        val builder = client.stanzaFactory.buildMessageStanza()
        client.sendStanza(encrypted.buildMessage(builder, jid))
    }

    @Synchronized
    fun disconnect() {
        manager?.removeOmemoMessageListener(incomingListener)
        manager = null
        connection?.let { runCatching { it.disconnect() } }
        connection = null
    }

    private fun trustKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(TRUST_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(TRUST_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        return generator.generateKey()
    }

    private fun loadTrustDecisions(): Map<String, TrustState> = runCatching {
        val packed = trustPrefs.getString("decisions", null) ?: return emptyMap()
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, trustKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                runCatching { TrustState.valueOf(json.getString(key)) }.getOrNull()?.let { put(key, it) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun saveTrustDecisions() {
        val json = JSONObject()
        trust.forEach { (key, state) -> json.put(key, state.name) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, trustKey())
        val packed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        trustPrefs.edit().putString("decisions", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }
    private fun requireManager() = manager ?: error("XMPP OMEMO session is not initialized")
    private fun key(device: OmemoDevice, fingerprint: OmemoFingerprint) = device.toString() + ":" + fingerprint.toString()

    companion object { private const val TRUST_KEY_ALIAS = "relyra.xmpp.omemo.trust.v1"; private object SmackOmemoSetup { @Volatile var initialized = false } }
}

data class XmppOmemoFingerprint(val device: OmemoDevice, val fingerprint: String, val trusted: Boolean, internal val rawFingerprint: OmemoFingerprint)
