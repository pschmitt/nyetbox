package dev.pschmitt.nyetbox.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AboutSettingsTest {

    @Test
    fun `build type label distinguishes debug and release`() {
        assertEquals("Debug build", aboutBuildTypeLabel(isDebug = true))
        assertEquals("Release build", aboutBuildTypeLabel(isDebug = false))
    }

    @Test
    fun `server version uses the NetBox status response field`() {
        val status =
            Json.parseToJsonElement("""{"netbox-version":"4.6.8","django-version":"6.0.8"}""")
                .jsonObject

        assertEquals("4.6.8", parseNetBoxServerVersion(status))
    }
}
