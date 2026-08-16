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

internal class MdmTelemetryClient(
    private val endpoint: String = MdmBridgeConfig.ENDPOINT,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    fun send(deviceId: String, secret: String, payload: JSONObject, callback: (Result<Unit>) -> Unit): Call {
        val nonce = MdmCrypto.newNonce()
        val timestamp = System.currentTimeMillis()
        val bodyHash = MdmCrypto.sha256Hex(MdmCanonicalJson.stringify(payload))
        val canonical = listOf(
            CONTRACT_VERSION.toString(), ACTION_TELEMETRY, deviceId,
            timestamp.toString(), nonce, bodyHash
        ).joinToString("\n")
        val envelope = JSONObject()
            .put("contract_version", CONTRACT_VERSION)
            .put("action", ACTION_TELEMETRY)
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
                override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        callback(runCatching {
                            val raw = response.body?.string().orEmpty()
                            verifyResponse(
                                MdmTransportPolicy.validatedBody(
                                    response.code, response.isSuccessful, raw, MAX_RESPONSE_BYTES
                                ),
                                deviceId, nonce, secret
                            )
                        })
                    }
                }
            })
        }
    }

    internal fun verifyResponse(raw: String, deviceId: String, requestNonce: String, secret: String) {
        val response = JSONObject(raw)
        if (!response.optBoolean("ok", false)) {
            error("SAFE BRIDGE rejected telemetry: ${response.optString("error", "UNKNOWN")}")
        }
        val signature = response.optString("response_signature")
        if (signature.isBlank()) error("Unsigned response")
        response.remove("response_signature")
        val expected = MdmCrypto.hmacBase64Url(secret, MdmCanonicalJson.stringify(response))
        if (!MdmCrypto.constantTimeEquals(expected, signature)) error("Invalid response signature")
        if (response.optInt("contract_version") != CONTRACT_VERSION ||
            response.optString("device_id") != deviceId ||
            response.optString("request_nonce") != requestNonce ||
            !response.optJSONObject("data")?.optBoolean("telemetry_accepted", false).orFalse()
        ) error("Response does not acknowledge telemetry")
    }

    private fun Boolean?.orFalse(): Boolean = this == true

    private companion object {
        const val CONTRACT_VERSION = 1
        const val ACTION_TELEMETRY = "telemetry"
        const val MAX_RESPONSE_BYTES = 20_000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
