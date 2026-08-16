package com.grupotgt.launcherkioscotgt.mdm

import android.content.Context

internal object InternalCommandGate {
    const val ACTION_START_MAINTENANCE = "START_MAINTENANCE"
    const val ACTION_FINISH_MAINTENANCE = "FINISH_MAINTENANCE"
    const val ACTION_FORCE_OTA = "FORCE_OTA"
    const val ACTION_RECONCILE_MANAGED_MODE = "RECONCILE_MANAGED_MODE"

    @Synchronized
    fun issue(context: Context, action: String): String {
        require(action in ALLOWED_ACTIONS) { "Unsupported internal command" }
        val token = MdmCrypto.newDeviceSecret()
        val persisted = preferences(context).edit()
            .putString(KEY_ACTION, action)
            .putString(KEY_TOKEN_HASH, MdmCrypto.sha256Hex(token))
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + TOKEN_TTL_MS)
            .commit()
        check(persisted) { "Internal command token could not be persisted" }
        return token
    }

    @Synchronized
    fun consume(context: Context, action: String, token: String?): Boolean {
        val prefs = preferences(context)
        val expectedAction = prefs.getString(KEY_ACTION, null)
        val expectedHash = prefs.getString(KEY_TOKEN_HASH, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        if (action !in ALLOWED_ACTIONS || token.isNullOrBlank()) return false
        if (expectedAction != action || expectedHash.isNullOrBlank()) return false
        if (System.currentTimeMillis() > expiresAt) {
            prefs.edit().clear().commit()
            return false
        }
        if (!MdmCrypto.constantTimeEquals(expectedHash, MdmCrypto.sha256Hex(token))) return false

        return prefs.edit().clear().commit()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val ALLOWED_ACTIONS = setOf(
        ACTION_START_MAINTENANCE,
        ACTION_FINISH_MAINTENANCE,
        ACTION_FORCE_OTA,
        ACTION_RECONCILE_MANAGED_MODE
    )

    private const val PREFERENCES = "MdmInternalCommandGate"
    private const val KEY_ACTION = "action"
    private const val KEY_TOKEN_HASH = "token_hash"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val TOKEN_TTL_MS = 30_000L
}
