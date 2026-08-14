package dev.pschmitt.nyetbox.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true }

private fun parse(raw: String): JsonObject = json.decodeFromString(JsonObject.serializer(), raw)

class TargetedSyncEngineTest {
    // Trimmed from a real `GET /api/dcim/devices/<id>/` response.
    @Test
    fun `finds every nested id+url object on a device`() {
        val device =
            parse(
                """
                {
                  "id": 30,
                  "name": "8-inch monitor",
                  "site": {
                    "id": 3,
                    "url": "https://netbox.brkn.lol/api/dcim/sites/3/",
                    "display": "gu5a"
                  },
                  "rack": {
                    "id": 1,
                    "url": "https://netbox.brkn.lol/api/dcim/racks/1/",
                    "display": "Samson SRK16"
                  },
                  "location": {
                    "id": 17,
                    "url": "https://netbox.brkn.lol/api/dcim/locations/17/",
                    "display": "Office"
                  }
                }
                """
                    .trimIndent()
            )

        assertEquals(
            setOf(
                "api/dcim/sites/" to 3,
                "api/dcim/racks/" to 1,
                "api/dcim/locations/" to 17,
            ),
            forwardReferences(device).toSet(),
        )
    }

    // Trimmed from a real `GET /api/dcim/cables/<id>/` response - confirms the walk recurses into
    // a_terminations/b_terminations to reach the far-end device two levels deep (termination
    // object, then its nested `device`), not just the top-level fields.
    @Test
    fun `finds terminations and their far-end devices inside a cable`() {
        val cable =
            parse(
                """
                {
                  "id": 1,
                  "a_terminations": [
                    {
                      "object_type": "dcim.interface",
                      "object_id": 1,
                      "object": {
                        "id": 1,
                        "url": "https://netbox.brkn.lol/api/dcim/interfaces/1/",
                        "device": {
                          "id": 1,
                          "url": "https://netbox.brkn.lol/api/dcim/devices/1/"
                        },
                        "cable": {
                          "id": 1,
                          "url": "https://netbox.brkn.lol/api/dcim/cables/1/"
                        }
                      }
                    }
                  ],
                  "b_terminations": [
                    {
                      "object_type": "dcim.interface",
                      "object_id": 22,
                      "object": {
                        "id": 22,
                        "url": "https://netbox.brkn.lol/api/dcim/interfaces/22/",
                        "device": {
                          "id": 5,
                          "url": "https://netbox.brkn.lol/api/dcim/devices/5/"
                        },
                        "cable": {
                          "id": 1,
                          "url": "https://netbox.brkn.lol/api/dcim/cables/1/"
                        }
                      }
                    }
                  ]
                }
                """
                    .trimIndent()
            )

        assertEquals(
            setOf(
                "api/dcim/interfaces/" to 1,
                "api/dcim/devices/" to 1,
                "api/dcim/cables/" to 1,
                "api/dcim/interfaces/" to 22,
                "api/dcim/devices/" to 5,
            ),
            forwardReferences(cable).toSet(),
        )
    }

    @Test
    fun `finds nothing in an object with no nested references`() {
        val plain = parse("""{"id": 1, "name": "flat object", "count": 3}""")

        assertEquals(emptyList<Pair<String, Int>>(), forwardReferences(plain))
    }

    @Test
    fun `ignores an id-only or url-only object`() {
        val partial =
            parse(
                """
                {
                  "id": 1,
                  "missing_url": {"id": 5, "display": "no url field"},
                  "missing_id": {"url": "https://netbox.brkn.lol/api/dcim/sites/9/"}
                }
                """
                    .trimIndent()
            )

        assertEquals(emptyList<Pair<String, Int>>(), forwardReferences(partial))
    }
}
