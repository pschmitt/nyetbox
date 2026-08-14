package dev.pschmitt.nyetbox.ui.generic

import dev.pschmitt.nyetbox.data.repository.CustomFieldDefinition
import dev.pschmitt.nyetbox.ui.common.MediaUploadKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericFieldRendererTest {

    private fun parse(rawJson: String): JsonObject =
        Json.decodeFromString(JsonObject.serializer(), rawJson)

    @Test
    fun `device type photo labels select the matching replacement upload`() {
        assertEquals(
            MediaUploadKind.DeviceTypeFront,
            deviceTypePhotoUploadKind("Front Image"),
        )
        assertEquals(
            MediaUploadKind.DeviceTypeRear,
            deviceTypePhotoUploadKind("Rear Image"),
        )
        assertNull(deviceTypePhotoUploadKind("Model"))
    }

    @Test
    fun `shortens displayed urls from the configured netbox origin`() {
        assertEquals(
            "/dcim/device-types/244/?tab=details#photos",
            shortenDisplayedUrl(
                "https://netbox.brkn.lol/dcim/device-types/244/?tab=details#photos",
                "https://netbox.brkn.lol",
            ),
        )
    }

    @Test
    fun `keeps external urls fully qualified`() {
        val external = "https://vendor.example.com/support/device"
        assertEquals(external, shortenDisplayedUrl(external, "https://netbox.brkn.lol"))
    }

    @Test
    fun `keeps urls unchanged when the netbox origin is unknown`() {
        val url = "https://netbox.brkn.lol/dcim/device-types/244/"
        assertEquals(url, shortenDisplayedUrl(url))
    }

    @Test
    fun `keeps malformed url text unchanged`() {
        assertEquals("not a url", shortenDisplayedUrl("not a url"))
    }

    @Test
    fun `detects a netbox media URL as a FileAttachment using the sibling filename field`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"document":"https://netbox.brkn.lol/media/netbox-documents/188_x.pdf","filename":"x.pdf"}"""
                )
            )
        // "filename" is also its own top-level field and renders as its own PlainText row too -
        // only asserting the FileAttachment row we actually care about here.
        assertTrue(
            rows.contains(
                FieldRow.FileAttachment(
                    "Document",
                    "https://netbox.brkn.lol/media/netbox-documents/188_x.pdf",
                    "x.pdf",
                )
            )
        )
    }

    @Test
    fun `renders the netbox documents plugin detail shape without special casing`() {
        val rows =
            buildFieldRows(
                parse(
                    """{
                        "document":"https://netbox.brkn.lol/media/netbox-documents/127_manual.pdf",
                        "filename":"manual.pdf",
                        "document_type":"manual",
                        "assigned_object":{"id":127,"url":"https://netbox.brkn.lol/api/dcim/device-types/127/","display":"Aranet4 Home"},
                        "comments":"",
                        "tags":[]
                    }"""
                )
            )

        assertEquals(
            listOf(
                FieldRow.FileAttachment(
                    "Document",
                    "https://netbox.brkn.lol/media/netbox-documents/127_manual.pdf",
                    "manual.pdf",
                ),
                FieldRow.PlainText("Filename", "manual.pdf"),
                FieldRow.PlainText("Document Type", "manual"),
                FieldRow.Reference(
                    "Assigned Object",
                    RefTarget("Aranet4 Home", "api/dcim/device-types/", 127),
                ),
            ),
            rows,
        )
    }

    @Test
    fun `falls back to the URL's last path segment when there is no filename field`() {
        val rows =
            buildFieldRows(parse("""{"image":"https://x/media/image-attachments/foo.png"}"""))
        assertEquals(
            listOf(
                FieldRow.FileAttachment(
                    "Image",
                    "https://x/media/image-attachments/foo.png",
                    "foo.png",
                )
            ),
            rows,
        )
    }

    @Test
    fun `renders device type front and rear images as inline Image rows`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"front_image":"https://x/media/devicetype-images/front.jpg","rear_image":"https://x/media/devicetype-images/rear.jpg"}"""
                )
            )
        assertEquals(
            listOf(
                FieldRow.Image("Front Image", "https://x/media/devicetype-images/front.jpg"),
                FieldRow.Image("Rear Image", "https://x/media/devicetype-images/rear.jpg"),
            ),
            rows,
        )
    }

    @Test
    fun `a plain http url field becomes an ExternalLink, not PlainText`() {
        val rows =
            buildFieldRows(parse("""{"external_url":"https://vendor.example.com/support"}"""))
        assertEquals(
            listOf(FieldRow.ExternalLink("External URL", "https://vendor.example.com/support")),
            rows,
        )
    }

    @Test
    fun `a url-shaped custom field also becomes an ExternalLink`() {
        val rows =
            buildFieldRows(
                parse("""{"custom_fields":{"vendor_support_url":"https://vendor.example.com/x"}}""")
            )
        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.ExternalLink("Vendor Support URL", "https://vendor.example.com/x"),
            ),
            rows,
        )
    }

    @Test
    fun `skips id, url, display and display url`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"id":1,"url":"https://x/api/dcim/racks/1/","display":"Rack 1","display_url":"https://netbox.brkn.lol/dcim/racks/1/"}"""
                )
            )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `renders comments as Markdown, not PlainText`() {
        val rows = buildFieldRows(parse("""{"comments":"- Started: `2021-10-01`"}"""))
        assertEquals(listOf(FieldRow.Markdown("Comments", "- Started: `2021-10-01`")), rows)
    }

    @Test
    fun `description is not treated as Markdown`() {
        val rows = buildFieldRows(parse("""{"description":"Not markdown"}"""))
        assertEquals(listOf(FieldRow.PlainText("Description", "Not markdown")), rows)
    }

    @Test
    fun `expands custom_fields into individual rows`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"name":"x","custom_fields":{"warranty_expires":"2027-01-01","internal_owner":"NetOps"}}"""
                )
            )
        assertEquals(
            listOf(
                FieldRow.PlainText("Name", "x"),
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.PlainText("Warranty Expires", "2027-01-01"),
                FieldRow.PlainText("Internal Owner", "NetOps"),
            ),
            rows,
        )
    }

    @Test
    fun `custom_fields with only null values contributes nothing`() {
        val rows = buildFieldRows(parse("""{"custom_fields":{"unset_field":null}}"""))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `configured markdown custom field renders as Markdown`() {
        val rows =
            buildFieldRows(
                parse("""{"custom_fields":{"purchase_store":"[Store](https://store.example)"}}"""),
                markdownCustomFieldNames = setOf("purchase_store"),
            )

        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.Markdown("Purchase Store", "[Store](https://store.example)"),
            ),
            rows,
        )
    }

    @Test
    fun `custom fields use definition labels markdown types groups and weights`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"custom_fields":{"purchase_store":"[Store](https://store.example)","purchase_date":"2026-01-01"}}"""
                ),
                listOf(
                    CustomFieldDefinition(
                        "purchase_store",
                        "markdown",
                        "Store",
                        "Purchase info",
                        20,
                    ),
                    CustomFieldDefinition(
                        "purchase_date",
                        "text",
                        "Purchase date",
                        "Purchase info",
                        10,
                    ),
                ),
            )

        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Purchase info"),
                FieldRow.Markdown("Purchase date", "2026-01-01"),
                FieldRow.Markdown("Store", "[Store](https://store.example)"),
            ),
            rows,
        )
    }

    @Test
    fun `text and longtext custom fields render through Markdown`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"custom_fields":{"purchase_store":"[Store](https://store.example)","purchase_notes":"**Received**"}}"""
                ),
                listOf(
                    CustomFieldDefinition("purchase_store", "text", "Store", null, 1),
                    CustomFieldDefinition("purchase_notes", "longtext", "Notes", null, 2),
                ),
            )

        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.Markdown("Store", "[Store](https://store.example)"),
                FieldRow.Markdown("Notes", "**Received**"),
            ),
            rows,
        )
    }

    @Test
    fun `custom field pairing codes expose a Matter QR action without a special field name`() {
        val rows =
            buildFieldRows(
                parse("""{"custom_fields":{"anything":"0439-591-1333"}}"""),
                listOf(CustomFieldDefinition("anything", "text", "Anything", null, 1)),
            )

        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.PlainText("Anything", "0439-591-1333", matterPairingCode = true),
            ),
            rows,
        )
        assertTrue(isMatterPairingCode("0439-591-1333"))
        assertTrue(!isMatterPairingCode("0439-591-133"))
    }

    @Test
    fun `custom_fields reference values become tappable Reference rows`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"custom_fields":{"owner_contact":{"id":9,"url":"https://x/api/tenancy/contacts/9/","display":"Jane"}}}"""
                )
            )
        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.Reference("Owner Contact", RefTarget("Jane", "api/tenancy/contacts/", 9)),
            ),
            rows,
        )
    }

    @Test
    fun `ungrouped custom fields are grouped under Other`() {
        val rows =
            buildFieldRows(parse("""{"custom_fields":{"asset_owner":"NetOps","notes":"Ready"}}"""))

        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.PlainText("Asset Owner", "NetOps"),
                FieldRow.PlainText("Notes", "Ready"),
            ),
            rows,
        )
    }

    @Test
    fun `clusterFieldRows groups a real custom-field group's members into one cluster`() {
        val rows =
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Purchase info"),
                FieldRow.PlainText("Vendor", "Acme"),
                FieldRow.PlainText("Purchase date", "2026-01-01"),
            )

        assertEquals(
            listOf(
                FieldRowCluster.Standalone(FieldRow.Section("Custom fields")),
                FieldRowCluster.Grouped(
                    "Purchase info",
                    listOf(
                        FieldRow.PlainText("Vendor", "Acme"),
                        FieldRow.PlainText("Purchase date", "2026-01-01"),
                    ),
                ),
            ),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows keeps the synthetic Other bucket standalone alongside a real group`() {
        val rows =
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Purchase info"),
                FieldRow.PlainText("Vendor", "Acme"),
                FieldRow.CustomGroup("Other"),
                FieldRow.PlainText("Notes", "Ready"),
            )

        assertEquals(
            listOf(
                FieldRowCluster.Standalone(FieldRow.Section("Custom fields")),
                FieldRowCluster.Grouped(
                    "Purchase info",
                    listOf(FieldRow.PlainText("Vendor", "Acme")),
                ),
                FieldRowCluster.Standalone(FieldRow.CustomGroup("Other")),
                FieldRowCluster.Standalone(FieldRow.PlainText("Notes", "Ready")),
            ),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows never clusters the synthetic Other bucket on its own`() {
        val rows =
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.PlainText("Asset Owner", "NetOps"),
                FieldRow.PlainText("Notes", "Ready"),
            )

        // No real group present - behaves identically to today: every row stands alone, the
        // "Other" heading included, with no spurious card wrapping it.
        assertEquals(rows.map { FieldRowCluster.Standalone(it) }, clusterFieldRows(rows))
    }

    @Test
    fun `clusterFieldRows clusters a run of native PlainText and Reference fields`() {
        val site = FieldRow.Reference("Site", RefTarget("HQ", "api/dcim/sites/", 1))
        val manufacturer =
            FieldRow.Reference("Manufacturer", RefTarget("Acme", "api/dcim/manufacturers/", 2))
        val assetTag = FieldRow.PlainText("Asset tag", "EYO-0001", copyable = true)
        val rows = listOf(site, manufacturer, assetTag)

        assertEquals(
            listOf(FieldRowCluster.Grouped(label = KEY_DETAILS_CARD_TITLE, rows = rows)),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows puts a lone native field in the Details card`() {
        val rows = listOf(FieldRow.PlainText("Model", "PowerEdge R730"))

        assertEquals(
            listOf(FieldRowCluster.Grouped(KEY_DETAILS_CARD_TITLE, rows)),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows groups comments and audit timestamps with native fields`() {
        val rows =
            listOf(
                FieldRow.Reference("Site", RefTarget("HQ", "api/dcim/sites/", 1)),
                FieldRow.PlainText("Serial", "ABC-123"),
                FieldRow.Markdown("Comments", "Installed in 2026"),
                FieldRow.Metadata("Last Updated", "2026-08-06T12:00:00Z"),
            )

        assertEquals(
            listOf(FieldRowCluster.Grouped(KEY_DETAILS_CARD_TITLE, rows)),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows groups reverse-related counts into the Linked items card`() {
        val rows =
            listOf(
                FieldRow.PlainText("Name", "HQ"),
                FieldRow.Count(
                    "Circuits",
                    "2",
                    CountTarget("api/circuits/circuits/", "Circuits", "site", 17),
                ),
                FieldRow.Count(
                    "Devices",
                    "136",
                    CountTarget("api/dcim/devices/", "Devices", "site", 17),
                ),
                FieldRow.Count(
                    "Racks",
                    "4",
                    CountTarget("api/dcim/racks/", "Racks", "site", 17),
                ),
            )

        assertEquals(
            listOf(
                FieldRowCluster.Grouped(
                    KEY_DETAILS_CARD_TITLE,
                    listOf(FieldRow.PlainText("Name", "HQ")),
                ),
                FieldRowCluster.Grouped(
                    KEY_LINKED_ITEMS_CARD_TITLE,
                    rows.drop(1),
                ),
            ),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows keeps linked item counts separate from custom fields`() {
        val rows =
            listOf(
                FieldRow.Count(
                    "Virtual Machines",
                    "5",
                    CountTarget(
                        "api/virtualization/virtual-machines/",
                        "Virtual Machines",
                        "site",
                        17,
                    ),
                ),
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.PlainText("Notes", "Ready"),
            )

        assertEquals(
            listOf(
                FieldRowCluster.Grouped(KEY_LINKED_ITEMS_CARD_TITLE, listOf(rows.first())),
                FieldRowCluster.Standalone(FieldRow.Section("Custom fields")),
                FieldRowCluster.Standalone(FieldRow.CustomGroup("Other")),
                FieldRowCluster.Standalone(FieldRow.PlainText("Notes", "Ready")),
            ),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows does not break a native run at a boolean field`() {
        // Confirms the fix for a real bug: a rack's boolean `desc_units` field sits between
        // plain/reference fields (weight_unit, outer_width, ...) in NetBox's actual JSON, and used
        // to fragment one "Details" card into three (two of them confusingly both titled
        // "Details"). A boolean now stays part of the same run, in its original position.
        val site = FieldRow.Reference("Site", RefTarget("HQ", "api/dcim/sites/", 1))
        val descUnits = FieldRow.BooleanValue("Desc. Units", true)
        val outerWidth = FieldRow.PlainText("Outer width", "600")
        val rows = listOf(site, descUnits, outerWidth)

        assertEquals(
            listOf(FieldRowCluster.Grouped(label = KEY_DETAILS_CARD_TITLE, rows = rows)),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows breaks a native run at non-clusterable row types`() {
        val site = FieldRow.Reference("Site", RefTarget("HQ", "api/dcim/sites/", 1))
        val manufacturer =
            FieldRow.Reference("Manufacturer", RefTarget("Acme", "api/dcim/manufacturers/", 2))
        val frontImage = FieldRow.Image("Front image", "https://netbox.example/media/front.jpg")
        val assetTag = FieldRow.PlainText("Asset tag", "EYO-0001")
        val rows = listOf(site, manufacturer, frontImage, assetTag)

        // Only the first native run is titled "Details" - a resumed run must not produce a second
        // card that also says "Details".
        assertEquals(
            listOf(
                FieldRowCluster.Grouped(
                    label = KEY_DETAILS_CARD_TITLE,
                    rows = listOf(site, manufacturer),
                ),
                FieldRowCluster.Standalone(frontImage),
                FieldRowCluster.Grouped(label = null, rows = listOf(assetTag)),
            ),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows never clusters native fields once past the Custom fields section`() {
        val rows =
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Other"),
                FieldRow.PlainText("Asset Owner", "NetOps"),
                FieldRow.PlainText("Notes", "Ready"),
            )

        // These two PlainText rows are custom fields in the synthetic "Other" bucket, not native
        // fields - the type-driven native clustering rule only applies before the Section marker.
        assertEquals(rows.map { FieldRowCluster.Standalone(it) }, clusterFieldRows(rows))
    }

    @Test
    fun `clusterFieldRows combines native field clustering with a real custom-field group`() {
        val site = FieldRow.Reference("Site", RefTarget("HQ", "api/dcim/sites/", 1))
        val manufacturer =
            FieldRow.Reference("Manufacturer", RefTarget("Acme", "api/dcim/manufacturers/", 2))
        val section = FieldRow.Section("Custom fields")
        val group = FieldRow.CustomGroup("Purchase info")
        val vendor = FieldRow.PlainText("Vendor", "Acme")
        val rows = listOf(site, manufacturer, section, group, vendor)

        assertEquals(
            listOf(
                FieldRowCluster.Grouped(
                    label = KEY_DETAILS_CARD_TITLE,
                    rows = listOf(site, manufacturer),
                ),
                FieldRowCluster.Standalone(section),
                FieldRowCluster.Grouped("Purchase info", listOf(vendor)),
            ),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `clusterFieldRows combines with buildFieldRows for a real NetBox custom-field group`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"custom_fields":{"purchase_store":"[Store](https://store.example)","purchase_date":"2026-01-01","asset_owner":"NetOps"}}"""
                ),
                listOf(
                    CustomFieldDefinition(
                        "purchase_store",
                        "markdown",
                        "Store",
                        "Purchase info",
                        20,
                    ),
                    CustomFieldDefinition(
                        "purchase_date",
                        "text",
                        "Purchase date",
                        "Purchase info",
                        10,
                    ),
                    // No definition for "asset_owner" - defaults through renderField like any
                    // other undeclared custom field, landing in the synthetic "Other" bucket as a
                    // plain text row rather than Markdown.
                ),
            )

        assertEquals(
            listOf(
                FieldRowCluster.Standalone(FieldRow.Section("Custom fields")),
                FieldRowCluster.Grouped(
                    "Purchase info",
                    listOf(
                        FieldRow.Markdown("Purchase date", "2026-01-01"),
                        FieldRow.Markdown("Store", "[Store](https://store.example)"),
                    ),
                ),
                FieldRowCluster.Standalone(FieldRow.CustomGroup("Other")),
                FieldRowCluster.Standalone(FieldRow.PlainText("Asset Owner", "NetOps")),
            ),
            clusterFieldRows(rows),
        )
    }

    @Test
    fun `skips null and blank fields`() {
        val rows = buildFieldRows(parse("""{"comments":null,"description":""}"""))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `renders a plain string field`() {
        val rows = buildFieldRows(parse("""{"model":"PowerEdge R730"}"""))
        assertEquals(listOf(FieldRow.PlainText("Model", "PowerEdge R730")), rows)
    }

    @Test
    fun `renders created by using the nested user display name`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"created_by":{"id":7,"url":"https://x/api/users/users/7/","display":"Ada Lovelace","username":"ada"}}"""
                )
            )
        assertEquals(listOf(FieldRow.PlainText("Created By", "Ada Lovelace")), rows)
    }

    @Test
    fun `renders created by display companion when the API only returns a user id`() {
        val rows = buildFieldRows(parse("""{"created_by":7,"created_by_display":"Ada Lovelace"}"""))
        assertEquals(listOf(FieldRow.PlainText("Created By", "Ada Lovelace")), rows)
    }

    @Test
    fun `keeps the created by id as a fallback`() {
        val rows = buildFieldRows(parse("""{"created_by":7}"""))
        assertEquals(listOf(FieldRow.PlainText("Created By", "7")), rows)
    }

    @Test
    fun `renders known location counts as filtered list targets`() {
        val rows =
            buildFieldRows(
                parse("""{"id":17,"rack_count":1,"device_count":136,"prefix_count":0}"""),
                endpointPath = "api/dcim/locations/",
            )

        assertEquals(
            listOf(
                FieldRow.Count(
                    "Racks",
                    "1",
                    CountTarget("api/dcim/racks/", "Racks", "location", 17),
                ),
                FieldRow.Count(
                    "Devices",
                    "136",
                    CountTarget("api/dcim/devices/", "Devices", "location", 17),
                ),
            ),
            rows,
        )
    }

    @Test
    fun `renders rack and device type counts as filtered device targets`() {
        val rackRows =
            buildFieldRows(
                parse("""{"id":1,"device_count":6}"""),
                endpointPath = "api/dcim/racks/",
            )
        val deviceTypeRows =
            buildFieldRows(
                parse("""{"id":244,"device_count":3}"""),
                endpointPath = "api/dcim/device-types/",
            )

        assertEquals(
            FieldRow.Count(
                "Devices",
                "6",
                CountTarget("api/dcim/devices/", "Devices", "rack", 1),
            ),
            rackRows.single(),
        )
        assertEquals(
            FieldRow.Count(
                "Devices",
                "3",
                CountTarget("api/dcim/devices/", "Devices", "device_type", 244),
            ),
            deviceTypeRows.single(),
        )
    }

    @Test
    fun `infers virtual machine counts from any virtualization parent`() {
        val rows =
            buildFieldRows(
                parse("""{"id":9,"virtual_machine_count":5}"""),
                endpointPath = "api/virtualization/clusters/",
            )

        assertEquals(
            FieldRow.Count(
                "Virtual Machines",
                "5",
                CountTarget(
                    "api/virtualization/virtual-machines/",
                    "Virtual Machines",
                    "cluster",
                    9,
                ),
            ),
            rows.single(),
        )
    }

    @Test
    fun `infers cross-app counts from tenancy parents`() {
        val rows =
            buildFieldRows(
                parse("""{"id":12,"virtual_machine_count":2}"""),
                endpointPath = "api/tenancy/tenants/",
            )

        assertEquals(
            CountTarget(
                "api/virtualization/virtual-machines/",
                "Virtual Machines",
                "tenant",
                12,
            ),
            (rows.single() as FieldRow.Count).target,
        )
    }

    @Test
    fun `identifier fields are copyable`() {
        val rows =
            buildFieldRows(
                parse("""{"serial":"ABC123","asset_tag":"AT-001","primary_ip4":"10.0.0.5/24"}""")
            )
        assertEquals(
            listOf(
                FieldRow.PlainText("Serial", "ABC123", copyable = true),
                FieldRow.PlainText("Asset Tag", "AT-001", copyable = true),
                FieldRow.PlainText("Primary IP4", "10.0.0.5/24", copyable = true),
            ),
            rows,
        )
    }

    @Test
    fun `primary ip reference is copyable while other references are not`() {
        val primaryIp =
            buildFieldRows(
                parse(
                    """{"primary_ip":{"id":7,"url":"https://x/api/ipam/ip-addresses/7/","display":"10.0.0.5/24"}}"""
                )
            )
        val site =
            buildFieldRows(
                parse("""{"site":{"id":3,"url":"https://x/api/dcim/sites/3/","display":"HQ"}}""")
            )
        assertEquals(true, (primaryIp.single() as FieldRow.Reference).copyable)
        assertEquals(false, (site.single() as FieldRow.Reference).copyable)
    }

    @Test
    fun `renders booleans as semantic state rows`() {
        val rows = buildFieldRows(parse("""{"is_full_depth":true,"airflow":false}"""))
        assertTrue(rows.contains(FieldRow.BooleanValue("Is Full Depth", true)))
        assertTrue(rows.contains(FieldRow.BooleanValue("Airflow", false)))
    }

    @Test
    fun `renders a choice-style object using its label`() {
        val rows = buildFieldRows(parse("""{"status":{"value":"active","label":"Active"}}"""))
        assertEquals(listOf(FieldRow.PlainText("Status", "Active")), rows)
    }

    @Test
    fun `renders a nested reference object as a tappable Reference`() {
        val rows =
            buildFieldRows(
                parse(
                    """{"site":{"id":3,"url":"https://netbox.brkn.lol/api/dcim/sites/3/","display":"HQ"}}"""
                )
            )
        assertEquals(
            listOf(FieldRow.Reference("Site", RefTarget("HQ", "api/dcim/sites/", 3))),
            rows,
        )
    }

    @Test
    fun `renders tags as a navigable TagList`() {
        val rows =
            buildFieldRows(
                parse(
                    """
                    {"tags":[
                        {"id":1,"url":"https://x/api/extras/tags/1/","display":"prod","name":"prod"},
                        {"id":2,"url":"https://x/api/extras/tags/2/","display":"edge","name":"edge"}
                    ]}
                    """
                        .trimIndent()
                )
            )
        assertEquals(
            listOf(
                FieldRow.TagList(
                    "Tags",
                    listOf(
                        RefTarget("prod", "api/extras/tags/", 1),
                        RefTarget("edge", "api/extras/tags/", 2),
                    ),
                )
            ),
            rows,
        )
    }

    @Test
    fun `renders linked template counts with correct plural labels and endpoints`() {
        val rows =
            buildFieldRows(
                parse("""{"id":244,"power_port_template_count":1,"device_bay_template_count":2}"""),
                endpointPath = "api/dcim/device-types/",
            )

        assertEquals(
            listOf(
                FieldRow.Count(
                    "Power Port Templates",
                    "1",
                    CountTarget(
                        "api/dcim/power-port-templates/",
                        "Power Port Templates",
                        "device_type",
                        244,
                    ),
                ),
                FieldRow.Count(
                    "Device Bay Templates",
                    "2",
                    CountTarget(
                        "api/dcim/device-bay-templates/",
                        "Device Bay Templates",
                        "device_type",
                        244,
                    ),
                ),
            ),
            rows,
        )
    }

    @Test
    fun `renders an array of plain strings as a ChipList`() {
        val rows = buildFieldRows(parse("""{"aliases":["foo","bar"]}"""))
        assertEquals(listOf(FieldRow.ChipList("Aliases", listOf("foo", "bar"))), rows)
    }

    @Test
    fun `flattens an unrecognized nested object best-effort`() {
        val rows = buildFieldRows(parse("""{"weight":{"value":5.0,"unit":"kg"}}"""))
        assertEquals(listOf(FieldRow.PlainText("Weight", "value: 5.0, unit: kg")), rows)
    }

    @Test
    fun `humanizes ip-style keys with acronym casing`() {
        val rows = buildFieldRows(parse("""{"primary_ip4":"10.0.0.1/24"}"""))
        assertEquals("Primary IP4", rows.single().label)
    }

    @Test
    fun `returns null endpoint for a malformed reference url`() {
        val rows = buildFieldRows(parse("""{"site":{"id":3,"url":"not-a-url","display":"HQ"}}"""))
        // Falls through to the flatten fallback since it's not a usable reference.
        assertNull(rows.filterIsInstance<FieldRow.Reference>().firstOrNull())
    }

    @Test
    fun doesNotRenderAHeadingForAnEmptyCustomFieldGroup() {
        val rows =
            buildFieldRows(
                parse("""{"custom_fields":{"empty_purchase_field":null,"asset_owner":"NetOps"}}"""),
                listOf(
                    CustomFieldDefinition(
                        "empty_purchase_field",
                        "text",
                        "Purchase note",
                        "Purchase Information",
                        1,
                    ),
                    CustomFieldDefinition(
                        "asset_owner",
                        "text",
                        "Asset owner",
                        "Operations",
                        1,
                    ),
                ),
            )

        assertEquals(
            listOf(
                FieldRow.Section("Custom fields"),
                FieldRow.CustomGroup("Operations"),
                FieldRow.Markdown("Asset owner", "NetOps"),
            ),
            rows,
        )
    }

    @Test
    fun renders_system_timestamps_as_metadata_rows() {
        val rows =
            buildFieldRows(
                parse(
                    """{"created":"2026-08-01T20:00:00Z","last_updated":"2026-08-02T20:00:00Z","name":"Router"}"""
                )
            )

        assertEquals(
            listOf(
                FieldRow.Metadata("Created", "2026-08-01T20:00:00Z"),
                FieldRow.Metadata("Last Updated", "2026-08-02T20:00:00Z"),
                FieldRow.PlainText("Name", "Router"),
            ),
            rows,
        )
    }
}
