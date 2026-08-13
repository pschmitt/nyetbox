package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.backup.SettingsBackupSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPreferenceTest {
    @Test
    fun `durable media cache is enabled by default while remaining opt-out capable`() {
        assertEquals(true, resolveSyncAttachmentsToDisk(null))
        assertEquals(true, resolveSyncAttachmentsToDisk(true))
        assertEquals(false, resolveSyncAttachmentsToDisk(false))
        assertEquals(true, SettingsBackupSettings().syncAttachmentsToDisk)
    }

    @Test
    fun `scanner lens preference defaults safely to the back camera`() {
        assertEquals(ScannerLens.Back, ScannerLens.fromStorage(null))
        assertEquals(ScannerLens.Back, ScannerLens.fromStorage("unknown"))
    }

    @Test
    fun `scanner lens preference round trips both choices`() {
        assertEquals(ScannerLens.Back, ScannerLens.fromStorage(ScannerLens.Back.storageKey))
        assertEquals(ScannerLens.Front, ScannerLens.fromStorage(ScannerLens.Front.storageKey))
    }

    @Test
    fun `rear lens preference defaults safely to the main rear lens`() {
        assertEquals(ScannerRearLens.Automatic, ScannerRearLens.fromStorage(null))
        assertEquals(ScannerRearLens.Automatic, ScannerRearLens.fromStorage("unknown"))
    }

    @Test
    fun `rear lens preference round trips supported choices`() {
        ScannerRearLens.entries.forEach { lens ->
            assertEquals(lens, ScannerRearLens.fromStorage(lens.storageKey))
        }
    }

    @Test
    fun `theme preferences default to system and round trip supported choices`() {
        assertEquals(ThemeMode.FollowSystem, ThemeMode.fromStorage(null))
        assertEquals(ThemeAccent.System, ThemeAccent.fromStorage(null))
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorage(mode.storageKey))
        }
        ThemeAccent.entries.forEach { accent ->
            assertEquals(accent, ThemeAccent.fromStorage(accent.storageKey))
        }
    }

    @Test
    fun `gesture defaults preserve the original shortcut and disable new directions`() {
        assertEquals(GestureAction.GlobalSearch, GestureAction.fromStorage(null))
        assertEquals(GestureAction.Off, GestureAction.fromStorage(null, GestureAction.Off))
        assertEquals(7, GestureShortcut.entries.size)
    }

    @Test
    fun `hidden field keys use a stable singular object name`() {
        assertEquals("device/model", hiddenFieldPreferenceKey("api/dcim/devices/", "Model"))
        assertEquals(
            "device-type/front_image",
            hiddenFieldPreferenceKey("api/dcim/device-types/", "front_image"),
        )
    }

    @Test
    fun `hidden field preference input is normalized and validated`() {
        assertEquals("device/model", normalizeHiddenFieldPreferenceKey(" Device / Model "))
        assertEquals(null, normalizeHiddenFieldPreferenceKey("model"))
        assertEquals("device/model", normalizeHiddenFieldPreferenceKey("device/model?"))
    }
}
