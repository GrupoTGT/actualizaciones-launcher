package com.grupotgt.launcherkioscotgt.mdm

import org.json.JSONArray
import org.json.JSONObject

internal object MdmConfigParser {
    fun validate(deviceId: String, json: JSONObject): MdmConfigSnapshot {
        if (deviceId.isBlank() || json.optString("device_id") != deviceId) {
            error("Config snapshot device mismatch")
        }
        if (json.optInt("schema_version", -1) != 1 || !json.optBoolean("complete", false)) {
            error("Config snapshot is incomplete or unsupported")
        }
        val revision = json.optLong("revision", -1L)
        if (revision < 0L) error("Config revision is invalid")
        val profileId = json.optString("profile_id").trim()
        val terminal = json.optString("terminal").trim()
        val section = json.optString("section").trim()
        if (profileId.isEmpty() || terminal.isEmpty() || section.isEmpty()) {
            error("Config identity is incomplete")
        }
        val contacts = parseContacts(json.optJSONArray("contacts") ?: error("Contacts are missing"))
        val apps = parseApps(json.optJSONArray("apps") ?: error("Apps are missing"))
        val settingsJson = json.optJSONObject("settings") ?: error("Settings are missing")
        val settings = settingsJson.keys().asSequence().associateWith { key ->
            if (key.equals("PANEL_IT_PASSWORD", ignoreCase = true)) {
                error("Panel IT password cannot be cached from remote config")
            }
            settingsJson.optString(key)
        }
        if (contacts.isEmpty() && apps.isEmpty() && settings.isEmpty()) {
            error("Empty config cannot replace the last valid snapshot")
        }
        return MdmConfigSnapshot(revision, profileId, terminal, section, contacts, apps, settings)
    }

    private fun parseContacts(array: JSONArray): List<MdmContactConfig> {
        val ids = mutableSetOf<String>()
        val phones = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: error("Invalid contact entry")
            val id = item.optString("contact_id").trim()
            val name = item.optString("name").trim()
            val phone = item.optString("phone").filter(Char::isDigit)
            if (id.isEmpty() || name.isEmpty() || phone.length !in 6..15) error("Invalid contact")
            if (!ids.add(id) || !phones.add(phone)) error("Duplicate contact")
            MdmContactConfig(
                id, name, phone,
                item.optBoolean("can_call_terminal", false),
                item.optBoolean("terminal_can_call", false)
            )
        }
    }

    private fun parseApps(array: JSONArray): List<MdmAppConfig> {
        val ids = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
        val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: error("Invalid app entry")
            val id = item.optString("app_id").trim()
            val label = item.optString("label").trim()
            val packageName = item.optString("package_name").trim()
            if (id.isEmpty() || label.isEmpty() || !packagePattern.matches(packageName)) error("Invalid app")
            if (!ids.add(id) || !packages.add(packageName)) error("Duplicate app")
            MdmAppConfig(id, label, packageName, item.optInt("order", 0).coerceAtLeast(0))
        }.sortedWith(compareBy<MdmAppConfig> { it.order }.thenBy { it.packageName })
    }
}
