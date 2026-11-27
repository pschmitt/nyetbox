package dev.pschmitt.nyetbox.scanner

import dev.pschmitt.nyetbox.qrsetup.QrConfigCodec
import dev.pschmitt.nyetbox.qrsetup.QrConfigEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetBoxUrlParserTest {

    @Test
    fun `parses device id from full url with trailing slash`() {
        assertEquals(
            NetBoxTarget.Device(393),
            NetBoxUrlParser.parse("https://netbox.example.com/dcim/devices/393/"),
        )
    }

    @Test
    fun `parses device id from url without trailing slash`() {
        assertEquals(
            NetBoxTarget.Device(393),
            NetBoxUrlParser.parse("https://netbox.example.com/dcim/devices/393"),
        )
    }

    @Test
    fun `parses bare numeric id as a device`() {
        assertEquals(NetBoxTarget.Device(393), NetBoxUrlParser.parse("393"))
    }

    @Test
    fun `parses a setup QR payload without treating it as a NetBox object`() {
        val payload =
            QrConfigCodec.encodePayload(
                QrConfigEnvelope(
                    createdAt = 1,
                    baseUrl = "https://netbox.example.test",
                    token = "nbt_key.secret",
                )
            )

        assertEquals(
            NetBoxTarget.Setup("https://netbox.example.test", "nbt_key.secret"),
            NetBoxUrlParser.parse(payload),
        )
    }

    @Test
    fun `parses an nyetbox device deep link`() {
        assertEquals(NetBoxTarget.Device(393), NetBoxUrlParser.parse("nyetbox://device/393"))
    }

    @Test
    fun `parses an encoded nyetbox device asset tag deep link`() {
        assertEquals(
            NetBoxTarget.DeviceAssetTag("#CZN-0001"),
            NetBoxUrlParser.parse("nyetbox://device/asset_tag/%23CZN-0001"),
        )
    }

    @Test
    fun `parses an unencoded fragment asset tag for convenient hand-authored links`() {
        assertEquals(
            NetBoxTarget.DeviceAssetTag("#CZN-0001"),
            NetBoxUrlParser.parse("nyetbox://device/asset_tag/#CZN-0001"),
        )
    }

    @Test
    fun `parses simple and api-style nyetbox object links`() {
        assertEquals(
            NetBoxTarget.Object("api/dcim/racks/", 12),
            NetBoxUrlParser.parse("nyetbox://rack/12"),
        )
        assertEquals(
            NetBoxTarget.Object("api/plugins/netbox_documents/documents/", 7),
            NetBoxUrlParser.parse("nyetbox://object/plugins/netbox_documents/documents/7"),
        )
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(NetBoxTarget.Device(393), NetBoxUrlParser.parse("  393  \n"))
    }

    @Test
    fun `parses a non-device object url into its endpoint path and id`() {
        assertEquals(
            NetBoxTarget.Object("api/dcim/racks/", 12),
            NetBoxUrlParser.parse("https://netbox.example.com/dcim/racks/12/"),
        )
    }

    @Test
    fun `parses an ipam object url`() {
        assertEquals(
            NetBoxTarget.Object("api/ipam/prefixes/", 7),
            NetBoxUrlParser.parse("https://netbox.example.com/ipam/prefixes/7/"),
        )
    }

    @Test
    fun `returns null for unrelated text`() {
        assertNull(NetBoxUrlParser.parse("not a netbox url"))
    }

    @Test
    fun `recognizes a plain asset tag payload`() {
        assertEquals("CZN-0001", NetBoxUrlParser.parseAssetTag(" CZN-0001\n"))
        assertEquals("#CZN-0001", NetBoxUrlParser.parseAssetTag("#CZN-0001"))
    }

    @Test
    fun `does not treat urls or numeric ids as asset tags`() {
        assertNull(NetBoxUrlParser.parseAssetTag("https://netbox.example.com/dcim/devices/393/"))
        assertNull(NetBoxUrlParser.parseAssetTag("393"))
        assertNull(NetBoxUrlParser.parseAssetTag("not a netbox url"))
    }
}
