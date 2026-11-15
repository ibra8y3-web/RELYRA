package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.relyra.app.core.integrations.PluginSdk
import org.relyra.app.core.integrations.PluginValidation
import org.junit.Test

class PluginSdkTest {
    private fun manifest(permissions: String = "[]", actions: String = """[{"id":"a1","type":"notice","title":"T","text":"hello"}]"""): String =
        """{"schemaVersion":1,"id":"dev.sample","name":"Sample","version":"1.0.0","description":"d","permissions":$permissions,"actions":$actions}"""

    @Test
    fun acceptsMinimalNoticePlugin() {
        val result = PluginSdk.parse(manifest())
        assertTrue(result is PluginValidation.Accepted)
        assertEquals("dev.sample", (result as PluginValidation.Accepted).manifest.id)
    }

    @Test
    fun copyTextRequiresClipboardPermission() {
        val action = """[{"id":"c","type":"copy_text","title":"Copy","text":"x"}]"""
        assertTrue(PluginSdk.parse(manifest(actions = action)) is PluginValidation.Rejected)
        assertTrue(PluginSdk.parse(manifest(permissions = """["clipboard.write"]""", actions = action)) is PluginValidation.Accepted)
    }

    @Test
    fun openUrlRequiresPermissionAndHttps() {
        fun open(url: String) = """[{"id":"o","type":"open_url","title":"Open","url":"$url"}]"""
        val granted = """["external.open"]"""
        assertTrue(PluginSdk.parse(manifest(granted, open("https://example.org"))) is PluginValidation.Accepted)
        assertTrue(PluginSdk.parse(manifest(granted, open("http://example.org"))) is PluginValidation.Rejected)
        assertTrue(PluginSdk.parse(manifest("[]", open("https://example.org"))) is PluginValidation.Rejected)
    }

    @Test
    fun rejectsUnknownPermissionAndMoreThanTwentyActions() {
        assertTrue(PluginSdk.parse(manifest(permissions = """["root.shell"]""")) is PluginValidation.Rejected)
        val many = (0 until 21).joinToString(",", "[", "]") { """{"id":"a$it","type":"notice","title":"T","text":"t"}""" }
        assertTrue(PluginSdk.parse(manifest(actions = many)) is PluginValidation.Rejected)
    }

    @Test
    fun exampleManifestIsAccepted() {
        assertTrue(PluginSdk.parse(PluginSdk.exampleManifest()) is PluginValidation.Accepted)
    }
}
