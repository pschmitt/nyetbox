package dev.pschmitt.nyetbox.ui.common

import androidx.compose.ui.graphics.Color
import dev.pschmitt.nyetbox.data.repository.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DetailAccentTest {
    @Test
    fun sameEndpointPathAlwaysResolvesToTheSameColor() {
        val first = visualColorForEndpointPath("api/dcim/devices/")
        val second = visualColorForEndpointPath("api/dcim/devices/")
        assertEquals(first, second)
    }

    @Test
    fun differentEndpointPathsResolveToDifferentFallbackColors() {
        val devices = visualColorForEndpointPath("api/dcim/devices/")
        val racks = visualColorForEndpointPath("api/dcim/racks/")
        assertNotEquals(devices, racks)
    }

    @Test
    fun nullOverrideUsesTheDeterministicFallback() {
        val withNull = visualColorForEndpointPath("api/dcim/racks/", override = null)
        val withSystem = visualColorForEndpointPath("api/dcim/racks/", override = ThemeAccent.System)
        assertEquals(withSystem, withNull)
        assertEquals(Color(0xFFEF6C00), withNull)
    }

    @Test
    fun perObjectTypeOverrideWinsOverTheFallbackColor() {
        val overridden = visualColorForEndpointPath("api/dcim/racks/", override = ThemeAccent.Pink)
        assertEquals(Color(0xFFC2185B), overridden)
        assertNotEquals(visualColorForEndpointPath("api/dcim/racks/"), overridden)
    }

    @Test
    fun everyNonSystemAccentMapsToItsOwnFixedColor() {
        assertEquals(Color(0xFF1565C0), visualColorForEndpointPath("api/x/", override = ThemeAccent.Blue))
        assertEquals(
            Color(0xFF7B1FA2),
            visualColorForEndpointPath("api/x/", override = ThemeAccent.Purple),
        )
        assertEquals(
            Color(0xFFEF6C00),
            visualColorForEndpointPath("api/x/", override = ThemeAccent.Orange),
        )
        assertEquals(Color(0xFF2E7D32), visualColorForEndpointPath("api/x/", override = ThemeAccent.Green))
    }
}
