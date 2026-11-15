package org.relyra.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.relyra.app.core.backup.WebDavClient
import java.io.File
import java.net.URLDecoder

/** An in-memory WebDAV server: PROPFIND, MKCOL, HEAD, PUT, MOVE, GET (+Range), DELETE. */
private class FakeDav : Dispatcher() {
    val files = LinkedHashMap<String, ByteArray>()
    val folders = HashSet<String>()
    var corruptGet = false
    var redirectAll = false
    var ignoreRange = false
    val seen = mutableListOf<RecordedRequest>()

    private fun key(r: RecordedRequest) = URLDecoder.decode(r.path!!.substringBefore('?'), "UTF-8").trim('/')

    override fun dispatch(request: RecordedRequest): MockResponse {
        seen += request
        if (redirectAll) return MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/")
        if (request.getHeader("Authorization") == null) return MockResponse().setResponseCode(401)
        val k = key(request)
        return when (request.method) {
            "PROPFIND" -> {
                val depth = request.getHeader("Depth")
                val sb = StringBuilder("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">")
                sb.append("<D:response><D:href>/${k}${if (k.isEmpty()) "" else "/"}</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>")
                if (depth == "1") files.filterKeys { it.substringBeforeLast('/', "") == k && !it.endsWith(".part") }.forEach { (path, data) ->
                    sb.append("<D:response><D:href>/${path.replace(" ", "%20")}</D:href><D:propstat><D:prop><D:getcontentlength>${data.size}</D:getcontentlength><D:resourcetype/></D:prop></D:propstat></D:response>")
                }
                MockResponse().setResponseCode(207).setBody(sb.append("</D:multistatus>").toString())
            }
            "MKCOL" -> if (k in folders) MockResponse().setResponseCode(405) else { folders += k; MockResponse().setResponseCode(201) }
            "HEAD" -> files[k]?.let { MockResponse().setResponseCode(200).setHeader("Content-Length", it.size) } ?: MockResponse().setResponseCode(404)
            "PUT" -> { files[k] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
            "MOVE" -> {
                val dest = URLDecoder.decode(request.getHeader("Destination")!!.substringAfter("//").substringAfter('/'), "UTF-8").trim('/')
                if (request.getHeader("Overwrite") == "F" && dest in files) MockResponse().setResponseCode(412)
                else { files[dest] = files.remove(k)!!; MockResponse().setResponseCode(201) }
            }
            "GET" -> {
                val data = files[k] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")
                if (range != null && !ignoreRange) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    MockResponse().setResponseCode(206).setBody(Buffer().write(data.copyOfRange(from, data.size)))
                } else {
                    val out = if (corruptGet) data.copyOf().also { it[0] = (it[0] + 1).toByte() } else data
                    MockResponse().setResponseCode(200).setBody(Buffer().write(out))
                }
            }
            "DELETE" -> { files.remove(k); MockResponse().setResponseCode(204) }
            else -> MockResponse().setResponseCode(405)
        }
    }
}

class WebDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var dav: FakeDav
    private lateinit var tmp: File

    @Before fun setUp() {
        dav = FakeDav(); server = MockWebServer(); server.dispatcher = dav; server.start()
        tmp = File.createTempFile("webdav", "dir").let { it.delete(); it.mkdirs(); it }
    }
    @After fun tearDown() { server.shutdown(); tmp.deleteRecursively() }

    private fun client(pass: String = "pw") = WebDavClient(server.url("/dav/").toString(), "user", pass.toCharArray(), allowInsecureHttp = true)
    private fun backup(size: Int = 200_000, seed: Int = 1) = File(tmp, "b$seed.rlybk").also { f -> f.writeBytes(ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }) }

    @Test fun plainHttpIsRefusedByDefault() {
        try { WebDavClient("http://nas.local/dav/", "u", "p".toCharArray()); fail() } catch (e: WebDavClient.WebDavException) { assertTrue(e.message!!.contains("HTTP")) }
        try { WebDavClient("https://u:p@nas.local/dav/", "u", "p".toCharArray()); fail() } catch (e: WebDavClient.WebDavException) { }
    }

    @Test fun uploadStoresVerifiedCopyAndLeavesNoPartFile() {
        val f = backup()
        val hash = client().uploadVerified("relyra", "relyra-20260101.rlybk", f)
        assertArrayEquals(f.readBytes(), dav.files["dav/relyra/relyra-20260101.rlybk"])
        assertEquals(32, hash.size)
        assertTrue(dav.files.keys.none { it.endsWith(".part") })
    }

    @Test fun uploadNeverOverwritesExistingBackup() {
        val c = client(); val f = backup()
        c.uploadVerified("relyra", "relyra-1.rlybk", f)
        try { c.uploadVerified("relyra", "relyra-1.rlybk", backup(seed = 2)); fail() } catch (e: WebDavClient.WebDavException) { assertEquals(412, e.status) }
        assertArrayEquals(f.readBytes(), dav.files["dav/relyra/relyra-1.rlybk"])
    }

    @Test fun corruptStoredCopyIsDetectedAndRemoved() {
        dav.corruptGet = true
        try { client().uploadVerified("relyra", "relyra-2.rlybk", backup()); fail() } catch (e: WebDavClient.WebDavException) { assertTrue(e.message!!.contains("verification")) }
        assertFalse(dav.files.containsKey("dav/relyra/relyra-2.rlybk"))
    }

    @Test fun redirectsAreNeverFollowedAndWrongPasswordIsReported() {
        dav.redirectAll = true
        try { client().testConnection(); fail() } catch (e: WebDavClient.WebDavException) { assertEquals(302, e.status) }
        assertEquals(1, dav.seen.size) // nothing was sent to the redirect target
        dav.redirectAll = false
        try { WebDavClient(server.url("/dav/").toString(), "", CharArray(0), true).testConnection(); fail() } catch (e: WebDavClient.WebDavException) { assertEquals(401, e.status) }
        assertTrue(client().testConnection())
    }

    @Test fun downloadResumesFromPartialFile() {
        val f = backup(300_000)
        client().uploadVerified("relyra", "relyra-3.rlybk", f)
        val target = File(tmp, "restore.rlybk")
        target.writeBytes(f.readBytes().copyOf(120_000))
        client().download("relyra", "relyra-3.rlybk", target)
        assertArrayEquals(f.readBytes(), target.readBytes())
        assertTrue(dav.seen.any { it.method == "GET" && it.getHeader("Range") == "bytes=120000-" })
    }

    @Test fun downloadRestartsWhenServerIgnoresRange() {
        val f = backup(50_000)
        client().uploadVerified("relyra", "relyra-4.rlybk", f)
        dav.ignoreRange = true
        val target = File(tmp, "r2.rlybk").also { it.writeBytes(f.readBytes().copyOf(1000)) }
        client().download("relyra", "relyra-4.rlybk", target)
        assertArrayEquals(f.readBytes(), target.readBytes())
    }

    @Test fun listAndPruneKeepNewest() {
        val c = client()
        listOf("relyra-20260101.rlybk", "relyra-20260102.rlybk", "relyra-20260103.rlybk").forEachIndexed { i, n -> c.uploadVerified("relyra", n, backup(1000, i + 1)) }
        assertEquals(3, c.list("relyra").size)
        assertEquals(2, c.prune("relyra", 1))
        assertEquals(listOf("relyra-20260103.rlybk"), c.list("relyra").map { it.name })
    }

    @Test fun illegalNamesAreRejectedBeforeAnyRequest() {
        val c = client()
        for (bad in listOf("../etc", "a/b", "", "x y", ".")) {
            try { c.uploadVerified("relyra", bad, backup(10)); fail(bad) } catch (e: WebDavClient.WebDavException) { }
        }
        assertTrue(dav.seen.isEmpty())
    }

    @Test fun credentialsAreSentAsBasicAuth() {
        client("s3cret").testConnection()
        assertNotNull(dav.seen.first().getHeader("Authorization")!!.takeIf { it.startsWith("Basic ") })
    }

    @Test fun listingParserHandlesNamespacesAndEncodedNames() {
        val xml = "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/dav/r/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>" +
            "<d:response><d:href>/dav/r/relyra-1.rlybk</d:href><d:propstat><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>"
        val e = WebDavClient.parseListing(xml, "/dav/r/")
        assertEquals(1, e.size); assertEquals("relyra-1.rlybk", e[0].name); assertEquals(42L, e[0].size)
    }
}
