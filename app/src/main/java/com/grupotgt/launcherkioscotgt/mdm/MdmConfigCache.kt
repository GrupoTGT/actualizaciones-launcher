package com.grupotgt.launcherkioscotgt.mdm

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

internal data class MdmContactConfig(
    val id: String,
    val name: String,
    val phone: String,
    val canCallTerminal: Boolean,
    val terminalCanCall: Boolean
)

internal data class MdmAppConfig(
    val id: String,
    val label: String,
    val packageName: String,
    val order: Int
)

internal data class MdmConfigSnapshot(
    val revision: Long,
    val profileId: String,
    val terminal: String,
    val section: String,
    val contacts: List<MdmContactConfig>,
    val apps: List<MdmAppConfig>,
    val settings: Map<String, String>
)

internal class MdmConfigCache(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds")
    fun currentDeviceId(): String = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.ANDROID_ID
    ).orEmpty()

    @Synchronized
    fun update(deviceId: String, snapshotJson: JSONObject): Result<MdmConfigSnapshot> = runCatching {
        val parsed = validate(deviceId, snapshotJson)
        val canonical = MdmCanonicalJson.stringify(snapshotJson)
        val hash = MdmCrypto.sha256Hex(canonical)
        val currentRevision = preferences.getLong(KEY_REVISION, -1L)
        val currentHash = preferences.getString(KEY_HASH, null)
        if (parsed.revision < currentRevision) error("Config revision is stale")
        if (parsed.revision == currentRevision && currentHash != null && currentHash != hash) {
            error("Config content changed without a new revision")
        }
        if (parsed.revision == currentRevision && currentHash == hash) return@runCatching parsed

        val persisted = preferences.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_REVISION, parsed.revision)
            .putString(KEY_HASH, hash)
            .putString(KEY_CANONICAL_JSON, canonical)
            .commit()
        check(persisted) { "Config snapshot could not be persisted" }
        parsed
    }

    @Synchronized
    fun load(deviceId: String = currentDeviceId()): Result<MdmConfigSnapshot> = runCatching {
        if (deviceId.isBlank() || preferences.getString(KEY_DEVICE_ID, null) != deviceId) {
            error("Config cache belongs to another device")
        }
        val canonical = preferences.getString(KEY_CANONICAL_JSON, null)
            ?: error("Config cache is empty")
        val expectedHash = preferences.getString(KEY_HASH, null)
            ?: error("Config cache hash is missing")
        if (!MdmCrypto.constantTimeEquals(expectedHash, MdmCrypto.sha256Hex(canonical))) {
            error("Config cache integrity check failed")
        }
        validate(deviceId, JSONObject(canonical))
    }

    private fun validate(deviceId: String, json: JSONObject): MdmConfigSnapshot {
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

        val contactsJson = json.optJSONArray("contacts") ?: error("Contacts are missing")
        val contacts = parseContacts(contactsJson)
        val appsJson = json.optJSONArray("apps") ?: error("Apps are missing")
        val apps = parseApps(appsJson)
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
                id,
                name,
                phone,
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
            if (id.isEmpty() || label.isEmpty() || !packagePattern.matches(packageName)) {
                error("Invalid app")
            }
            if (!ids.add(id) || !packages.add(packageName)) error("Duplicate app")
            MdmAppConfig(id, label, packageName, item.optInt("order", 0).coerceAtLeast(0))
        }.sortedWith(compareBy<MdmAppConfig> { it.order }.thenBy { it.packageName })
    }

    private companion object {
        const val PREFERENCES = "MdmCanonicalConfigCache"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_REVISION = "revision"
        const val KEY_HASH = "sha256"
        const val KEY_CANONICAL_JSON = "canonical_json"
    }
}
