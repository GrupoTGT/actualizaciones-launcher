package com.grupotgt.launcherkioscotgt.mdm

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

internal data class MdmEnrollmentResult(
    val approvalState: String,
    val commandsEnabled: Boolean,
    val credentialFingerprint: String,
    val terminal: String,
    val profileId: String,
    val mode: String,
    val modeRevision: Long,
    val configSnapshot: JSONObject?
)

internal class MdmEnrollmentClient(
    private val endpoint: String,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    fun enroll(
        deviceId: String,
        secret: String,
        appVersion: String,
        model: String,
        androidVersion: String,
        callback: (Result<MdmEnrollmentResult>) -> Unit
    ): Call {
        require(endpoint.startsWith("https://script.google.com/macros/s/") && endpoint.endsWith("/exec")) {
            "Invalid SAFE BRIDGE endpoint"
        }
        val nonce = MdmCrypto.newNonce()
        val timestamp = System.currentTimeMillis()
        val payload = JSONObject()
            .put("device_secret", secret)
            .put("app_version", appVersion)
            .put("model", model)
            .put("android", androidVersion)
        val bodyHash = MdmCrypto.sha256Hex(MdmCanonicalJson.stringify(payload))
        val canonical = listOf(
            CONTRACT_VERSION.toString(),
            ACTION_ENROLL,
            deviceId,
            timestamp.toString(),
            nonce,
            bodyHash
        ).joinToString("\n")
        val envelope = JSONObject()
            .put("contract_version", CONTRACT_VERSION)
            .put("action", ACTION_ENROLL)
            .put("device_id", deviceId)
            .put("timestamp_ms", timestamp)
            .put("nonce", nonce)
            .put("payload", payload)
            .put("body_sha256", bodyHash)
            .put("signature", MdmCrypto.hmacBase64Url(secret, canonical))
        val request = Request.Builder()
            .url(endpoint)
            .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Cache-Control", "no-store")
            .build()
        return httpClient.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        callback(runCatching {
                            if (!response.isSuccessful) error("SAFE BRIDGE HTTP ${response.code}")
                            val raw = response.body?.string().orEmpty()
                            if (raw.isBlank() || raw.length > MAX_RESPONSE_BYTES) {
                                error("SAFE BRIDGE returned an invalid body")
                            }
                            parseAndVerifyResponse(raw, deviceId, nonce, secret)
                        })
                    }
                }
            })
        }
    }

    private fun parseAndVerifyResponse(
        raw: String,
        deviceId: String,
        requestNonce: String,
        secret: String
    ): MdmEnrollmentResult {
        val response = JSONObject(raw)
        if (!response.optBoolean("ok", false)) {
            error("SAFE BRIDGE rejected enrollment: ${response.optString("error", "UNKNOWN")}")
        }
        val signature = response.optString("response_signature")
        if (signature.isBlank()) error("SAFE BRIDGE response is unsigned")
        response.remove("response_signature")
        val expected = MdmCrypto.hmacBase64Url(secret, MdmCanonicalJson.stringify(response))
        if (!MdmCrypto.constantTimeEquals(expected, signature)) {
            error("SAFE BRIDGE response signature is invalid")
        }
        if (response.optInt("contract_version") != CONTRACT_VERSION ||
            response.optString("device_id") != deviceId ||
            response.optString("request_nonce") != requestNonce
        ) {
            error("SAFE BRIDGE response does not match the request")
        }
        val data = response.getJSONObject("data")
        val mode = if (data.optString("mode") == "LIBRE GESTIONADO") {
            "LIBRE GESTIONADO"
        } else {
            "BLINDADO"
        }
        return MdmEnrollmentResult(
            approvalState = data.optString("approval_state", "PENDING_APPROVAL"),
            commandsEnabled = data.optBoolean("commands_enabled", false),
            credentialFingerprint = data.getString("credential_fingerprint"),
            terminal = data.optString("terminal"),
            profileId = data.optString("profile_id", "PENDIENTE_SEGURO"),
            mode = mode,
            modeRevision = data.optLong("mode_revision", 0L).coerceAtLeast(0L),
            configSnapshot = data.optJSONObject("config_snapshot")
        )
    }

    private companion object {
        const val CONTRACT_VERSION = 1
        const val ACTION_ENROLL = "enroll"
        const val MAX_RESPONSE_BYTES = 100_000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
