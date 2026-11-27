package dev.pschmitt.nyetbox.ui.directory

import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarLabelsTest {
    @Test
    fun `displays hostname from https URL`() {
        assertEquals(
            "netbox.example.com",
            displayNetBoxHostname("https://netbox.example.com/"),
        )
    }

    @Test
    fun `displays hostname from http URL with path`() {
        assertEquals(
            "inventory.example",
            displayNetBoxHostname("http://inventory.example/netbox/"),
        )
    }

    @Test
    fun `falls back to the authority for malformed URLs`() {
        assertEquals("inventory.example", displayNetBoxHostname("inventory.example/api/"))
    }
}
