package com.grupotgt.launcherkioscotgt.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class MdmPolicyTest {
    @Test
    fun canonicalJsonAndHmacMatchSharedBridgeGoldenVector() {
        val payload = JSONObject()
            .put("z", "</tag>")
            .put("a", JSONArray().put("ñ").put(true).put(1))
            .put("nested", JSONObject().put("b", "line\n").put("a", JSONObject.NULL))
        val canonical = MdmCanonicalJson.stringify(payload)
        assertEquals(
            "{\"a\":[\"ñ\",true,1],\"nested\":{\"a\":null,\"b\":\"line\\n\"},\"z\":\"</tag>\"}",
            canonical
        )
        assertEquals("8701f0e972647967a5bc953d769d0dc8f2d8db5a63695a77ef8b2e92c21c50b5", MdmCrypto.sha256Hex(canonical))
        assertEquals("P0r25EXcCkmVL3q80ZdeCLlJVKL7N5W_ghRYAhh8BzU", MdmCrypto.hmacBase64Url("vector-secret", canonical))
    }

    @Test
    fun managedModeRevisionRejectsReplayStaleAndContradiction() {
        assertTrue(ManagedModeRevisionPolicy.accepts(ManagedMode.BLINDADO, -1, ManagedMode.BLINDADO, 0))
        assertTrue(ManagedModeRevisionPolicy.accepts(ManagedMode.BLINDADO, 2, ManagedMode.BLINDADO, 2))
        assertTrue(ManagedModeRevisionPolicy.accepts(ManagedMode.BLINDADO, 2, ManagedMode.LIBRE_GESTIONADO, 3))
        assertFalse(ManagedModeRevisionPolicy.accepts(ManagedMode.BLINDADO, 2, ManagedMode.LIBRE_GESTIONADO, 2))
        assertFalse(ManagedModeRevisionPolicy.accepts(ManagedMode.BLINDADO, 2, ManagedMode.BLINDADO, 1))
    }

    @Test
    fun networkTelemetryDistinguishesAirplaneWifiWithoutInternetAndRecovery() {
        val airplane = MdmNetworkStateFactory.create(false, false, false, false, true)
        assertEquals("MODO AVION SIN INTERNET", airplane.status)
        val captiveWifi = MdmNetworkStateFactory.create(true, false, false, false, false)
        assertEquals("WIFI SIN INTERNET VALIDADO", captiveWifi.status)
        val recovered = MdmNetworkStateFactory.create(true, false, false, true, false)
        assertEquals("INTERNET VALIDADO", recovered.status)
        assertEquals("NO VERIFICABLE", recovered.vowifiState)
    }

    @Test
    fun configuredAndInstalledApplicationsRemainDifferentAndDeduplicated() {
        val inventory = MdmAppInventory.from(
            listOf("com.example.a", "com.example.a", "com.example.b"),
            listOf("com.example.a", "com.other.unmanaged")
        )
        assertEquals(listOf("com.example.a", "com.example.b"), inventory.configured)
        assertEquals(listOf("com.example.a"), inventory.installedConfigured)
        assertEquals(listOf("com.example.b"), inventory.missingConfigured)
    }

    @Test
    fun restoredManagedStateIsRejectedWhenDeviceBindingIsMissingOrDifferent() {
        assertFalse(MdmDeviceBindingPolicy.mustReset(null, "device-a", false))
        assertTrue(MdmDeviceBindingPolicy.mustReset(null, "device-a", true))
        assertFalse(MdmDeviceBindingPolicy.mustReset("device-a", "device-a", true))
        assertTrue(MdmDeviceBindingPolicy.mustReset("device-a", "device-b", true))
    }

    @Test
    fun transportRejectsEmpty4xxAndRetriesTimeoutAnd5xx() {
        assertThrows<IllegalStateException> { MdmTransportPolicy.validatedBody(200, true, "", 100) }
        assertThrows<IllegalStateException> { MdmTransportPolicy.validatedBody(404, false, "x", 100) }
        assertThrows<IOException> { MdmTransportPolicy.validatedBody(503, false, "x", 100) }
        assertThrows<IOException> { MdmTransportPolicy.validatedBody(408, false, "x", 100) }
        assertThrows<IOException> { MdmTransportPolicy.validatedBody(429, false, "x", 100) }
        assertTrue(MdmTransportPolicy.shouldRetry(IOException("timeout")))
        assertFalse(MdmTransportPolicy.shouldRetry(IllegalStateException("invalid signature")))
    }

    @Test
    fun managedHttpClientUsesBoundedAppsScriptTimeouts() {
        val client = MdmHttpClientFactory.create()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.writeTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(75_000, client.callTimeoutMillis)
    }

    @Test
    fun pilotOtaAssignmentIsBoundToDeviceHostDigestSizeAndExpiry() {
        val now = 10_000L
        val valid = JSONObject()
            .put("assignment_id", "SALA3-V65-PILOT")
            .put("device_id", "device-sala-3")
            .put("version_code", 65)
            .put("version_name", "65.0-pilot")
            .put(
                "apk_url",
                "https://github.com/GrupoTGT/actualizaciones-launcher/releases/download/" +
                    "v65.0-pilot/LauncherKioscoTGT-v65.0-pilot.apk"
            )
            .put("sha256", "a".repeat(64))
            .put("size_bytes", 6_630_440L)
            .put("expires_at_ms", now + 60_000L)
        val assignment = MdmPilotOtaAssignment.parse("device-sala-3", valid, now)
        assertTrue(assignment.isEligible(64L, now))
        assertFalse(assignment.isEligible(65L, now))
        assertThrows<IllegalArgumentException> {
            MdmPilotOtaAssignment.parse("another-device", valid, now)
        }
        assertThrows<IllegalArgumentException> {
            MdmPilotOtaAssignment.parse(
                "device-sala-3",
                JSONObject(valid.toString()).put("apk_url", "https://example.com/update.apk"),
                now
            )
        }
        assertThrows<IllegalArgumentException> {
            MdmPilotOtaAssignment.parse("device-sala-3", valid, now + 60_001L)
        }
    }

    private inline fun <reified T : Throwable> assertThrows(noinline block: () -> Unit) {
        org.junit.Assert.assertThrows(T::class.java, block)
    }
}
