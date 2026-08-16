package com.grupotgt.launcherkioscotgt.mdm

import android.content.Context
import android.provider.Settings

internal enum class ManagedMode(val wireValue: String) {
    BLINDADO("BLINDADO"),
    LIBRE_GESTIONADO("LIBRE GESTIONADO");

    companion object {
        fun parse(value: String?): ManagedMode = if (value == LIBRE_GESTIONADO.wireValue) {
            LIBRE_GESTIONADO
        } else {
            BLINDADO
        }
    }
}

internal object ManagedModeRevisionPolicy {
    fun accepts(
        currentMode: ManagedMode,
        currentRevision: Long,
        proposedMode: ManagedMode,
        proposedRevision: Long
    ): Boolean = proposedRevision >= 0L &&
        proposedRevision >= currentRevision &&
        !(proposedRevision == currentRevision && currentRevision >= 0L && proposedMode != currentMode)
}

internal object ManagedModeStore {
    internal data class State(
        val desiredMode: ManagedMode,
        val desiredRevision: Long,
        val appliedMode: ManagedMode,
        val appliedRevision: Long,
        val phase: String,
        val lastError: String
    )

    @Synchronized
    fun acceptAuthenticated(context: Context, modeValue: String, revision: Long): Boolean {
        val mode = ManagedMode.parse(modeValue)
        val prefs = preferences(context)
        val currentRevision = prefs.getLong(KEY_DESIRED_REVISION, -1L)
        val currentMode = ManagedMode.parse(prefs.getString(KEY_DESIRED_MODE, null))
        if (!ManagedModeRevisionPolicy.accepts(currentMode, currentRevision, mode, revision)) return false
        if (revision == currentRevision && mode == currentMode) return true

        return prefs.edit()
            .putString(KEY_DESIRED_MODE, mode.wireValue)
            .putLong(KEY_DESIRED_REVISION, revision)
            .putString(KEY_PHASE, PHASE_PENDING)
            .remove(KEY_LAST_ERROR)
            .commit()
    }

    fun desiredMode(context: Context): ManagedMode = ManagedMode.parse(
        preferences(context).getString(KEY_DESIRED_MODE, null)
    )

    fun desiredRevision(context: Context): Long = preferences(context)
        .getLong(KEY_DESIRED_REVISION, -1L)

    fun isManagedFree(context: Context): Boolean = desiredMode(context) == ManagedMode.LIBRE_GESTIONADO

    fun state(context: Context): State {
        val prefs = preferences(context)
        return State(
            desiredMode = ManagedMode.parse(prefs.getString(KEY_DESIRED_MODE, null)),
            desiredRevision = prefs.getLong(KEY_DESIRED_REVISION, -1L),
            appliedMode = ManagedMode.parse(prefs.getString(KEY_APPLIED_MODE, null)),
            appliedRevision = prefs.getLong(KEY_APPLIED_REVISION, -1L),
            phase = prefs.getString(KEY_PHASE, PHASE_PENDING).orEmpty(),
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
        )
    }

    fun markApplying(context: Context, mode: ManagedMode): Boolean = preferences(context).edit()
        .putString(KEY_PHASE, "APPLYING_${mode.name}")
        .remove(KEY_LAST_ERROR)
        .commit()

    fun markApplied(context: Context, mode: ManagedMode): Boolean {
        val prefs = preferences(context)
        return prefs.edit()
            .putString(KEY_APPLIED_MODE, mode.wireValue)
            .putLong(KEY_APPLIED_REVISION, prefs.getLong(KEY_DESIRED_REVISION, -1L))
            .putString(KEY_PHASE, PHASE_STABLE)
            .remove(KEY_LAST_ERROR)
            .commit()
    }

    fun markError(context: Context, message: String) {
        preferences(context).edit()
            .putString(KEY_PHASE, PHASE_ERROR)
            .putString(KEY_LAST_ERROR, message.take(240))
            .commit()
    }

    @Synchronized
    private fun preferences(context: Context): android.content.SharedPreferences {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val currentDeviceId = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val boundDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        val containsState = prefs.contains(KEY_DESIRED_MODE) || prefs.contains(KEY_APPLIED_MODE)
        val reset = MdmDeviceBindingPolicy.mustReset(boundDeviceId, currentDeviceId, containsState)
        if (reset) {
            check(prefs.edit().clear().putString(KEY_DEVICE_ID, currentDeviceId).commit()) {
                "Managed mode binding could not be reset safely"
            }
        } else if (boundDeviceId != currentDeviceId) {
            check(prefs.edit().putString(KEY_DEVICE_ID, currentDeviceId).commit()) {
                "Managed mode binding could not be persisted"
            }
        }
        return prefs
    }

    private const val PREFERENCES = "MdmManagedModeState"
    private const val KEY_DESIRED_MODE = "desired_mode"
    private const val KEY_DESIRED_REVISION = "desired_revision"
    private const val KEY_APPLIED_MODE = "applied_mode"
    private const val KEY_APPLIED_REVISION = "applied_revision"
    private const val KEY_PHASE = "phase"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_DEVICE_ID = "bound_device_id_v1"
    private const val PHASE_PENDING = "PENDING"
    private const val PHASE_STABLE = "STABLE"
    private const val PHASE_ERROR = "ERROR"
}
