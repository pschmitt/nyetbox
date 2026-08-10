package dev.pschmitt.nyetbox.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiTokenFormatTest {
    @Test
    fun `composes the current named token format`() {
        assertEquals(
            "nbt_home-phone.secret-value",
            composeNamedApiToken(" home-phone ", " secret-value "),
        )
    }

    @Test
    fun `parses both current and legacy named token prefixes`() {
        assertEquals(
            NamedApiToken("nbt_", "home-phone", "secret-value"),
            parseNamedApiToken(" nbt_home-phone.secret-value "),
        )
        assertEquals(
            NamedApiToken("nbp_", "legacy", "secret"),
            parseNamedApiToken("nbp_legacy.secret"),
        )
    }

    @Test
    fun `rejects token parts that cannot be serialized safely`() {
        assertNull(composeNamedApiToken("", "secret"))
        assertNull(composeNamedApiToken("name.with-dot", "secret"))
        assertNull(composeNamedApiToken("name", "secret.with-dot"))
        assertNull(parseNamedApiToken("nbp_name.secret.with-dot"))
    }
}
