package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.tools.SiteDecision
import org.relyra.app.core.tools.SiteRules
import org.relyra.app.core.tools.SiteRulesCodec

class BrowserSiteRulesTest {
    @Test
    fun siteKeyIsTheLowerCaseHost() {
        assertEquals("example.org", SiteRulesCodec.siteKey("https://Example.ORG/path?q=1"))
        assertEquals("a.example.org", SiteRulesCodec.siteKey("http://a.example.org:8080/"))
    }

    @Test
    fun siteKeyIsNullWithoutAHost() {
        assertNull(SiteRulesCodec.siteKey(""))
        assertNull(SiteRulesCodec.siteKey("not a url"))
        assertNull(SiteRulesCodec.siteKey("about:blank"))
    }

    @Test
    fun rulesSurviveEncodeAndDecode() {
        val original = mapOf(
            "example.org" to SiteRules(javaScript = SiteDecision.BLOCK, cookies = SiteDecision.ALLOW),
            "cam.example.net" to SiteRules(camera = SiteDecision.ALLOW, microphone = SiteDecision.BLOCK),
        )
        assertEquals(original, SiteRulesCodec.decode(SiteRulesCodec.encode(original)))
    }

    @Test
    fun defaultRulesAreNotStored() {
        val encoded = SiteRulesCodec.encode(mapOf("example.org" to SiteRules()))
        assertTrue(SiteRulesCodec.decode(encoded).isEmpty())
    }

    @Test
    fun damagedDataDecodesToNothing() {
        assertTrue(SiteRulesCodec.decode("{broken").isEmpty())
        assertTrue(SiteRulesCodec.decode("""{"example.org":"x","other.org":{"js":"MAYBE"}}""").isEmpty())
    }

    @Test
    fun storedSiteCountIsCapped() {
        val many = (0 until SiteRulesCodec.MAX_SITES + 20).associate { "h$it.example.org" to SiteRules(javaScript = SiteDecision.BLOCK) }
        assertEquals(SiteRulesCodec.MAX_SITES, SiteRulesCodec.decode(SiteRulesCodec.encode(many)).size)
    }
}
