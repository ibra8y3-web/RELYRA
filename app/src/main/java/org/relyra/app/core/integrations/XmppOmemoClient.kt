package org.relyra.app.core.integrations

import android.content.Context
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.omemo.OmemoConfiguration
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback
import org.jivesoftware.smackx.omemo.trust.TrustState
import org.jxmpp.jid.BareJid
import org.jxmpp.jid.impl.JidCreate
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * XMPP TCP + XEP-0384 OMEMO adapter.
 * Unknown devices remain undecided and encryption fails closed until the user verifies/trusts
 * the fingerprint. The store is app-private; a Keystore-backed custom OMEMO store is still needed
 * before claiming hardware-bound encryption at rest for OMEMO state.
 */
class XmppOmemoClient(context: Context) {
    private val app = context.applicationContext
    private var connection: XMPPTCPConnection? = null
    private var manager: OmemoManager? = null
    private val trust = ConcurrentHashMap<String, TrustState>()

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
                OmemoConfiguration.setFileBasedOmemoStoreDefaultPath(File(app.noBackupFilesDir, "xmpp-omemo"))
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
            client.connect()
            client.login()
            val omemo = OmemoManager.getInstanceFor(client)
            omemo.setTrustCallback(object : OmemoTrustCallback {
                override fun getTrust(device: OmemoDevice, fingerprint: OmemoFingerprint): TrustState =
                    trust[key(device, fingerprint)] ?: TrustState.undecided
                override fun setTrust(device: OmemoDevice, fingerprint: OmemoFingerprint, state: TrustState) {
                    trust[key(device, fingerprint)] = state
                }
            })
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
            XmppOmemoFingerprint(device, fingerprint.toString(), omemo.isTrustedOmemoIdentity(device, fingerprint))
        }
    }

    /** Call only after the user has compared the displayed fingerprint through another channel. */
    fun trustFingerprint(device: OmemoDevice, fingerprint: OmemoFingerprint) {
        requireManager().trustOmemoIdentity(device, fingerprint)
    }

    fun distrustFingerprint(device: OmemoDevice, fingerprint: OmemoFingerprint) {
        requireManager().distrustOmemoIdentity(device, fingerprint)
    }

    @Synchronized
    fun sendEncrypted(recipient: String, plaintext: String) {
        require(plaintext.isNotBlank() && plaintext.length <= 65536)
        val client = connection?.takeIf { it.isAuthenticated } ?: error("XMPP is not connected")
        val omemo = requireManager()
        val jid: BareJid = JidCreate.bareFrom(recipient)
        val encrypted = omemo.encrypt(jid, plaintext)
        client.sendStanza(encrypted.asMessage(jid))
    }

    @Synchronized
    fun disconnect() {
        manager = null
        connection?.let { runCatching { it.disconnect() } }
        connection = null
    }

    private fun requireManager() = manager ?: error("XMPP OMEMO session is not initialized")
    private fun key(device: OmemoDevice, fingerprint: OmemoFingerprint) = device.toString() + ":" + fingerprint.toString()

    companion object { private object SmackOmemoSetup { @Volatile var initialized = false } }
}

data class XmppOmemoFingerprint(val device: OmemoDevice, val fingerprint: String, val trusted: Boolean)
