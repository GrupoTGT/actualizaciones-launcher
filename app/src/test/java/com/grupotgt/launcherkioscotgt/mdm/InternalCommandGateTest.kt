package com.grupotgt.launcherkioscotgt.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalCommandGateTest {
    @Test
    fun consecutiveActionsRemainIndependentlyConsumable() {
        val now = 1_000L
        val reconcileHash = MdmCrypto.sha256Hex("reconcile-token")
        val otaHash = MdmCrypto.sha256Hex("ota-token")
        var queue = InternalCommandTokenQueue.issue(
            emptyList(),
            InternalCommandGate.ACTION_RECONCILE_MANAGED_MODE,
            reconcileHash,
            now + 30_000L,
            now
        )
        queue = InternalCommandTokenQueue.issue(
            queue,
            InternalCommandGate.ACTION_APPLY_PILOT_OTA,
            otaHash,
            now + 30_000L,
            now
        )

        val ota = InternalCommandTokenQueue.consume(
            queue,
            InternalCommandGate.ACTION_APPLY_PILOT_OTA,
            otaHash,
            now + 1L
        )
        assertTrue(ota.accepted)

        val reconcile = InternalCommandTokenQueue.consume(
            ota.remaining,
            InternalCommandGate.ACTION_RECONCILE_MANAGED_MODE,
            reconcileHash,
            now + 2L
        )
        assertTrue(reconcile.accepted)
        assertTrue(reconcile.remaining.isEmpty())
    }

    @Test
    fun invalidOrExpiredTokenCannotConsumeAnotherPendingAction() {
        val now = 5_000L
        val reconcileHash = MdmCrypto.sha256Hex("reconcile-token")
        val maintenanceHash = MdmCrypto.sha256Hex("maintenance-token")
        var queue = InternalCommandTokenQueue.issue(
            emptyList(),
            InternalCommandGate.ACTION_RECONCILE_MANAGED_MODE,
            reconcileHash,
            now + 30_000L,
            now
        )
        queue = InternalCommandTokenQueue.issue(
            queue,
            InternalCommandGate.ACTION_START_MAINTENANCE,
            maintenanceHash,
            now + 1L,
            now
        )

        val invalid = InternalCommandTokenQueue.consume(
            queue,
            InternalCommandGate.ACTION_FORCE_OTA,
            maintenanceHash,
            now + 2L
        )
        assertFalse(invalid.accepted)

        val reconcile = InternalCommandTokenQueue.consume(
            invalid.remaining,
            InternalCommandGate.ACTION_RECONCILE_MANAGED_MODE,
            reconcileHash,
            now + 3L
        )
        assertTrue(reconcile.accepted)
    }
}
