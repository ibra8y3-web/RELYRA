package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.network.NearbyHandshakeProtocol
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NearbyHandshakeProtocolTest {
    @Test
    fun authenticatesBothIdentitiesBeforeAcceptingEncryptedPayload() {
        val serverKeys = IdentityCrypto.generate()
        val clientKeys = IdentityCrypto.generate()
        val serverId = IdentityCrypto.publicId(serverKeys.publicKey)
        val clientId = IdentityCrypto.publicId(clientKeys.publicKey)
        val payload = ByteArray(257) { (it % 127).toByte() }
        val received = AtomicReference<ByteArray?>()
        val executor = Executors.newSingleThreadExecutor()

        ServerSocket(0).use { listener ->
            val serverResult = executor.submit<String?> {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    runBlocking {
                        NearbyHandshakeProtocol.receive(
                            socket.getInputStream(),
                            socket.getOutputStream(),
                            serverId,
                            sign = { IdentityCrypto.sign(serverKeys.seed, it) },
                            knownPeer = { it == clientId },
                            onWire = { frame -> received.set(frame); true },
                        )
                    }
                }
            }

            val clientResult = Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort), 3_000)
                socket.soTimeout = 5_000
                runBlocking {
                    NearbyHandshakeProtocol.send(
                        socket.getInputStream(),
                        socket.getOutputStream(),
                        clientId,
                        expectedPeerId = serverId,
                        sign = { IdentityCrypto.sign(clientKeys.seed, it) },
                        knownPeer = { it == serverId },
                        payload = payload,
                    )
                }
            }

            assertEquals(serverId, clientResult)
            assertEquals(clientId, serverResult.get(6, TimeUnit.SECONDS))
            assertArrayEquals(payload, received.get())
        }
        executor.shutdownNow()
    }

    @Test
    fun rejectsAnIdentityThatIsNotInTheLocalContactBook() {
        val serverKeys = IdentityCrypto.generate()
        val clientKeys = IdentityCrypto.generate()
        val serverId = IdentityCrypto.publicId(serverKeys.publicKey)
        val clientId = IdentityCrypto.publicId(clientKeys.publicKey)
        val executor = Executors.newSingleThreadExecutor()

        ServerSocket(0).use { listener ->
            val serverResult = executor.submit<String?> {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    runBlocking {
                        NearbyHandshakeProtocol.receive(
                            socket.getInputStream(),
                            socket.getOutputStream(),
                            serverId,
                            sign = { IdentityCrypto.sign(serverKeys.seed, it) },
                            knownPeer = { false },
                            onWire = { true },
                        )
                    }
                }
            }
            val clientResult = Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort), 3_000)
                socket.soTimeout = 3_000
                runBlocking {
                    NearbyHandshakeProtocol.send(
                        socket.getInputStream(),
                        socket.getOutputStream(),
                        clientId,
                        expectedPeerId = serverId,
                        sign = { IdentityCrypto.sign(clientKeys.seed, it) },
                        knownPeer = { it == serverId },
                        payload = byteArrayOf(1),
                    )
                }
            }
            assertNull(clientResult)
            assertNull(serverResult.get(4, TimeUnit.SECONDS))
        }
        executor.shutdownNow()
    }

    @Test
    fun boundsFrameSizesAndAllowsEmptyIdentityProbe() {
        assertTrue(NearbyHandshakeProtocol.isValidFrameLength(0))
        assertTrue(NearbyHandshakeProtocol.isValidFrameLength(NearbyHandshakeProtocol.MAX_FRAME_BYTES))
        assertFalse(NearbyHandshakeProtocol.isValidFrameLength(-1))
        assertFalse(NearbyHandshakeProtocol.isValidFrameLength(NearbyHandshakeProtocol.MAX_FRAME_BYTES + 1))
    }
}
