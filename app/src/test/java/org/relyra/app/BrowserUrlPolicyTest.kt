package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.relyra.app.core.tools.BrowserUrlPolicy

class BrowserUrlPolicyTest {
    @Test
    fun addsHttpsToHostNames() {
        assertEquals("https://example.com", BrowserUrlPolicy.normalize("example.com"))
    }

    @Test
    fun acceptsExplicitHttpAndHttps() {
        assertEquals("https://example.com/path?q=1", BrowserUrlPolicy.normalize("https://example.com/path?q=1"))
        assertEquals("http://example.com", BrowserUrlPolicy.normalize("http://example.com"))
    }

    @Test
    fun rejectsNonWebSchemes() {
        assertNull(BrowserUrlPolicy.normalize("javascript:alert(1)"))
        assertNull(BrowserUrlPolicy.normalize("file:///etc/passwd"))
        assertNull(BrowserUrlPolicy.normalize("content://private/data"))
        assertNull(BrowserUrlPolicy.normalize("intent://example.com"))
        assertNull(BrowserUrlPolicy.normalize("data:text/html,hello"))
    }

    @Test
    fun rejectsCredentialsAndMalformedHosts() {
        assertNull(BrowserUrlPolicy.normalize("https://user:pass@example.com"))
        assertNull(BrowserUrlPolicy.normalize("https:///missing-host"))
        assertNull(BrowserUrlPolicy.normalize("https://example.com:99999"))
        assertNull(BrowserUrlPolicy.normalize("https://example.com/path with spaces"))
    }
}
