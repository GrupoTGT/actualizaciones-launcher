package com.grupotgt.launcherkioscotgt.mdm

import android.content.Context

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

internal object ManagedModeStore {
    @Synchronized
    fun acceptAuthenticated(context: Context, modeValue: String, revision: Long): Boolean {
        if (revision < 0L) return false
        val mode = ManagedMode.parse(modeValue)
        val prefs = preferences(context)
        val currentRevision = prefs.getLong(KEY_DESIRED_REVISION, -1L)
        val currentMode = ManagedMode.parse(prefs.getString(KEY_DESIRED_MODE, null))
        if (revision < currentRevision) return false
        if (revision == currentRevision && currentRevision >= 0L && mode != currentMode) return false
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

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val PREFERENCES = "MdmManagedModeState"
    private const val KEY_DESIRED_MODE = "desired_mode"
    private const val KEY_DESIRED_REVISION = "desired_revision"
    private const val KEY_APPLIED_MODE = "applied_mode"
    private const val KEY_APPLIED_REVISION = "applied_revision"
    private const val KEY_PHASE = "phase"
    private const val KEY_LAST_ERROR = "last_error"
    private const val PHASE_PENDING = "PENDING"
    private const val PHASE_STABLE = "STABLE"
    private const val PHASE_ERROR = "ERROR"
}
