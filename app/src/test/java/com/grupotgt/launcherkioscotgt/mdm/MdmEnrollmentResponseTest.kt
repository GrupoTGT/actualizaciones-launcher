package com.grupotgt.launcherkioscotgt.mdm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MdmEnrollmentResponseTest {
    private val endpoint = "https://script.google.com/macros/s/test/exec"
    private val deviceId = "device-12345678"
    private val nonce = "nonce-1234567890123456"
    private val secret = MdmCrypto.newDeviceSecret()

    @Test
    fun pendingUnknownDeviceStaysLockedAndWithoutCommands() {
        val result = MdmEnrollmentClient(endpoint).parseAndVerifyResponse(
            signedEnrollment("PENDING_APPROVAL", false, "BLINDADO", 0), deviceId, nonce, secret
        )
        assertEquals("PENDING_APPROVAL", result.approvalState)
        assertFalse(result.commandsEnabled)
        assertEquals("BLINDADO", result.mode)
    }

    @Test
    fun knownLinkedDeviceAcceptsSignedProfileAndMode() {
        val result = MdmEnrollmentClient(endpoint).parseAndVerifyResponse(
            signedEnrollment("APPROVED", true, "LIBRE GESTIONADO", 7), deviceId, nonce, secret
        )
        assertEquals("APPROVED", result.approvalState)
        assertTrue(result.commandsEnabled)
        assertEquals("PROFILE_SALA", result.profileId)
        assertEquals("LIBRE GESTIONADO", result.mode)
        assertEquals(7L, result.modeRevision)
    }

    @Test
    fun invalidSignatureIsRejected() {
        val response = JSONObject(signedEnrollment("APPROVED", true, "BLINDADO", 2))
            .put("response_signature", "invalid")
            .toString()
        assertThrows(IllegalStateException::class.java) {
            MdmEnrollmentClient(endpoint).parseAndVerifyResponse(response, deviceId, nonce, secret)
        }
    }

    @Test
    fun emptyAndCorruptResponsesAreRejected() {
        assertThrows(Exception::class.java) {
            MdmEnrollmentClient(endpoint).parseAndVerifyResponse("", deviceId, nonce, secret)
        }
        assertThrows(Exception::class.java) {
            MdmEnrollmentClient(endpoint).parseAndVerifyResponse("{broken", deviceId, nonce, secret)
        }
    }

    @Test
    fun telemetryWithoutExplicitAckIsRejected() {
        val body = JSONObject()
            .put("ok", true)
            .put("contract_version", 1)
            .put("server_time_ms", 1L)
            .put("device_id", deviceId)
            .put("request_nonce", nonce)
            .put("data", JSONObject())
        body.put("response_signature", MdmCrypto.hmacBase64Url(secret, MdmCanonicalJson.stringify(body)))
        assertThrows(IllegalStateException::class.java) {
            MdmTelemetryClient(endpoint).verifyResponse(body.toString(), deviceId, nonce, secret)
        }
    }

    private fun signedEnrollment(state: String, commands: Boolean, mode: String, revision: Long): String {
        val body = JSONObject()
            .put("ok", true)
            .put("contract_version", 1)
            .put("server_time_ms", 1L)
            .put("device_id", deviceId)
            .put("request_nonce", nonce)
            .put("data", JSONObject()
                .put("approval_state", state)
                .put("commands_enabled", commands)
                .put("credential_fingerprint", MdmCrypto.fingerprint(secret))
                .put("terminal", "Sala 3")
                .put("profile_id", "PROFILE_SALA")
                .put("mode", mode)
                .put("mode_revision", revision))
        body.put("response_signature", MdmCrypto.hmacBase64Url(secret, MdmCanonicalJson.stringify(body)))
        return body.toString()
    }
}
