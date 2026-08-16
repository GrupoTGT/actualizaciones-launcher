package com.grupotgt.launcherkioscotgt.mdm

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
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
        val parsed = MdmConfigParser.validate(deviceId, snapshotJson)
        val canonical = MdmCanonicalJson.stringify(snapshotJson)
        val hash = MdmCrypto.sha256Hex(canonical)
        val currentRevision = preferences.getLong(KEY_REVISION, -1L)
        val currentHash = preferences.getString(KEY_HASH, null)
        when (MdmCachePolicy.decide(currentRevision, currentHash, parsed.revision, hash)) {
            MdmCacheDecision.REJECT_STALE -> error("Config revision is stale")
            MdmCacheDecision.REJECT_CONFLICT -> error("Config content changed without a new revision")
            MdmCacheDecision.UNCHANGED -> return@runCatching parsed
            MdmCacheDecision.APPLY -> Unit
        }

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
        MdmConfigParser.validate(deviceId, JSONObject(canonical))
    }

    private companion object {
        const val PREFERENCES = "MdmCanonicalConfigCache"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_REVISION = "revision"
        const val KEY_HASH = "sha256"
        const val KEY_CANONICAL_JSON = "canonical_json"
    }
}
