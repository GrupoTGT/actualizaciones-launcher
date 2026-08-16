package com.grupotgt.launcherkioscotgt.mdm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MdmConfigParserTest {
    private val deviceId = "device-12345678"

    @Test
    fun validSnapshotPreservesOfflineAgendaAppsAndProfile() {
        val parsed = MdmConfigParser.validate(deviceId, snapshot())
        assertEquals(9L, parsed.revision)
        assertEquals("PROFILE_A", parsed.profileId)
        assertEquals(2, parsed.contacts.size)
        assertEquals(listOf("com.example.alpha", "com.example.beta"), parsed.apps.map { it.packageName })
    }

    @Test
    fun profileChangeIsAcceptedOnlyAsExplicitNewSnapshot() {
        val changed = snapshot().put("revision", 10).put("profile_id", "PROFILE_B")
        assertEquals("PROFILE_B", MdmConfigParser.validate(deviceId, changed).profileId)
        assertThrows(IllegalStateException::class.java) {
            MdmConfigParser.validate(deviceId, changed.put("profile_id", ""))
        }
    }

    @Test
    fun duplicateAgendaPhoneIsRejected() {
        val json = snapshot()
        json.getJSONArray("contacts").put(contact("c3", "Duplicado", "600000001"))
        assertThrows(IllegalStateException::class.java) { MdmConfigParser.validate(deviceId, json) }
    }

    @Test
    fun duplicateApplicationPackageIsRejected() {
        val json = snapshot()
        json.getJSONArray("apps").put(app("a3", "Duplicada", "com.example.alpha", 3))
        assertThrows(IllegalStateException::class.java) { MdmConfigParser.validate(deviceId, json) }
    }

    @Test
    fun corruptEmptyOrForeignSnapshotCannotReplaceLastValid() {
        assertThrows(Exception::class.java) { MdmConfigParser.validate(deviceId, JSONObject("{broken")) }
        assertThrows(IllegalStateException::class.java) {
            MdmConfigParser.validate(deviceId, snapshot().put("complete", false))
        }
        assertThrows(IllegalStateException::class.java) {
            MdmConfigParser.validate(deviceId, snapshot().put("device_id", "another-device"))
        }
    }

    @Test
    fun offlineCacheSurvivesAndReconnectAppliesOnlyNewerConsistentRevision() {
        assertEquals(MdmCacheDecision.UNCHANGED, MdmCachePolicy.decide(9, "hash-a", 9, "hash-a"))
        assertEquals(MdmCacheDecision.REJECT_STALE, MdmCachePolicy.decide(9, "hash-a", 8, "hash-old"))
        assertEquals(MdmCacheDecision.REJECT_CONFLICT, MdmCachePolicy.decide(9, "hash-a", 9, "hash-other"))
        assertEquals(MdmCacheDecision.APPLY, MdmCachePolicy.decide(9, "hash-a", 10, "hash-new"))
    }

    private fun snapshot(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("complete", true)
        .put("device_id", deviceId)
        .put("revision", 9)
        .put("profile_id", "PROFILE_A")
        .put("terminal", "Sala 3")
        .put("section", "Finales")
        .put("contacts", JSONArray()
            .put(contact("c1", "Uno", "600000001"))
            .put(contact("c2", "Dos", "600000002")))
        .put("apps", JSONArray()
            .put(app("a2", "Beta", "com.example.beta", 2))
            .put(app("a1", "Alpha", "com.example.alpha", 1)))
        .put("settings", JSONObject().put("SCREEN_TIMEOUT", "10"))

    private fun contact(id: String, name: String, phone: String) = JSONObject()
        .put("contact_id", id).put("name", name).put("phone", phone)
        .put("can_call_terminal", true).put("terminal_can_call", true)

    private fun app(id: String, label: String, packageName: String, order: Int) = JSONObject()
        .put("app_id", id).put("label", label).put("package_name", packageName).put("order", order)
}
