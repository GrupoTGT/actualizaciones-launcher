package com.grupotgt.launcherkioscotgt.mdm

import android.content.Context
import org.json.JSONObject
import java.net.URI

internal data class MdmPilotOtaAssignment(
    val assignmentId: String,
    val deviceId: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val expiresAtMs: Long
) {
    fun isEligible(currentVersionCode: Long, nowMs: Long): Boolean =
        versionCode.toLong() > currentVersionCode && expiresAtMs > nowMs

    fun toJson(): JSONObject = JSONObject()
        .put("assignment_id", assignmentId)
        .put("device_id", deviceId)
        .put("version_code", versionCode)
        .put("version_name", versionName)
        .put("apk_url", apkUrl)
        .put("sha256", sha256)
        .put("size_bytes", sizeBytes)
        .put("expires_at_ms", expiresAtMs)

    companion object {
        fun parse(expectedDeviceId: String, json: JSONObject, nowMs: Long): MdmPilotOtaAssignment {
            val assignmentId = json.optString("assignment_id").trim()
            val deviceId = json.optString("device_id").trim()
            val versionCode = json.optInt("version_code", -1)
            val versionName = json.optString("version_name").trim()
            val apkUrl = json.optString("apk_url").trim()
            val sha256 = json.optString("sha256").trim().lowercase()
            val sizeBytes = json.optLong("size_bytes", -1L)
            val expiresAtMs = json.optLong("expires_at_ms", -1L)
            require(assignmentId.matches(Regex("[A-Za-z0-9._-]{8,100}"))) { "Invalid OTA assignment id" }
            require(deviceId == expectedDeviceId && deviceId.isNotBlank()) { "OTA assignment device mismatch" }
            require(versionCode > 0 && versionName.isNotBlank()) { "Invalid OTA version" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid OTA SHA-256" }
            require(sizeBytes > 0L) { "Invalid OTA size" }
            require(expiresAtMs > nowMs) { "Expired OTA assignment" }
            val uri = URI(apkUrl)
            require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true)) {
                "Untrusted OTA host"
            }
            require(uri.path.startsWith("/GrupoTGT/actualizaciones-launcher/releases/download/")) {
                "Untrusted OTA path"
            }
            return MdmPilotOtaAssignment(
                assignmentId, deviceId, versionCode, versionName, apkUrl,
                sha256, sizeBytes, expiresAtMs
            )
        }
    }
}

internal object MdmPilotOtaStore {
    fun update(
        context: Context,
        deviceId: String,
        json: JSONObject,
        nowMs: Long = System.currentTimeMillis()
    ): Result<MdmPilotOtaAssignment> = runCatching {
        val assignment = MdmPilotOtaAssignment.parse(deviceId, json, nowMs)
        check(
            preferences(context).edit()
                .putString(KEY_ASSIGNMENT, assignment.toJson().toString())
                .commit()
        ) { "OTA assignment could not be persisted" }
        assignment
    }

    fun load(
        context: Context,
        deviceId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Result<MdmPilotOtaAssignment> = runCatching {
        val encoded = preferences(context).getString(KEY_ASSIGNMENT, null)
            ?: error("No pilot OTA assignment")
        MdmPilotOtaAssignment.parse(deviceId, JSONObject(encoded), nowMs)
    }

    fun clear(context: Context): Boolean = preferences(context).edit().remove(KEY_ASSIGNMENT).commit()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val PREFERENCES = "MdmPilotOtaAssignment"
    private const val KEY_ASSIGNMENT = "signed_assignment"
}
