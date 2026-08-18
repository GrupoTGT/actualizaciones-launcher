package com.grupotgt.launcherkioscotgt.mdm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class InternalCommandTokenSlot(
    val action: String,
    val tokenHash: String,
    val expiresAtMs: Long
)

internal data class InternalCommandConsumeResult(
    val accepted: Boolean,
    val remaining: List<InternalCommandTokenSlot>
)

internal object InternalCommandTokenQueue {
    fun issue(
        current: List<InternalCommandTokenSlot>,
        action: String,
        tokenHash: String,
        expiresAtMs: Long,
        nowMs: Long
    ): List<InternalCommandTokenSlot> = current
        .filter { it.expiresAtMs >= nowMs }
        .plus(InternalCommandTokenSlot(action, tokenHash, expiresAtMs))
        .distinctBy(InternalCommandTokenSlot::tokenHash)
        .takeLast(MAX_PENDING_TOKENS)

    fun consume(
        current: List<InternalCommandTokenSlot>,
        action: String,
        tokenHash: String,
        nowMs: Long
    ): InternalCommandConsumeResult {
        val active = current.filter { it.expiresAtMs >= nowMs }
        val index = active.indexOfFirst {
            it.action == action && MdmCrypto.constantTimeEquals(it.tokenHash, tokenHash)
        }
        if (index < 0) return InternalCommandConsumeResult(false, active)
        return InternalCommandConsumeResult(true, active.filterIndexed { position, _ -> position != index })
    }

    private const val MAX_PENDING_TOKENS = 16
}

internal object InternalCommandGate {
    const val ACTION_START_MAINTENANCE = "START_MAINTENANCE"
    const val ACTION_FINISH_MAINTENANCE = "FINISH_MAINTENANCE"
    const val ACTION_FORCE_OTA = "FORCE_OTA"
    const val ACTION_APPLY_PILOT_OTA = "APPLY_PILOT_OTA"
    const val ACTION_RECONCILE_MANAGED_MODE = "RECONCILE_MANAGED_MODE"

    @Synchronized
    fun issue(context: Context, action: String): String {
        require(action in ALLOWED_ACTIONS) { "Unsupported internal command" }
        val token = MdmCrypto.newDeviceSecret()
        val now = System.currentTimeMillis()
        val updated = InternalCommandTokenQueue.issue(
            load(context),
            action,
            MdmCrypto.sha256Hex(token),
            now + TOKEN_TTL_MS,
            now
        )
        val persisted = persist(context, updated)
        check(persisted) { "Internal command token could not be persisted" }
        return token
    }

    @Synchronized
    fun consume(context: Context, action: String, token: String?): Boolean {
        if (action !in ALLOWED_ACTIONS || token.isNullOrBlank()) return false
        val result = InternalCommandTokenQueue.consume(
            load(context),
            action,
            MdmCrypto.sha256Hex(token),
            System.currentTimeMillis()
        )
        return persist(context, result.remaining) && result.accepted
    }

    private fun load(context: Context): List<InternalCommandTokenSlot> = runCatching {
        val encoded = preferences(context).getString(KEY_PENDING_TOKENS, null) ?: return emptyList()
        val array = JSONArray(encoded)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val action = item.optString("action")
                val hash = item.optString("token_hash")
                val expiresAt = item.optLong("expires_at_ms", 0L)
                if (action in ALLOWED_ACTIONS && hash.isNotBlank() && expiresAt > 0L) {
                    add(InternalCommandTokenSlot(action, hash, expiresAt))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun persist(context: Context, slots: List<InternalCommandTokenSlot>): Boolean {
        val encoded = JSONArray().apply {
            slots.forEach { slot ->
                put(
                    JSONObject()
                        .put("action", slot.action)
                        .put("token_hash", slot.tokenHash)
                        .put("expires_at_ms", slot.expiresAtMs)
                )
            }
        }.toString()
        return preferences(context).edit().putString(KEY_PENDING_TOKENS, encoded).commit()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val ALLOWED_ACTIONS = setOf(
        ACTION_START_MAINTENANCE,
        ACTION_FINISH_MAINTENANCE,
        ACTION_FORCE_OTA,
        ACTION_APPLY_PILOT_OTA,
        ACTION_RECONCILE_MANAGED_MODE
    )

    private const val PREFERENCES = "MdmInternalCommandGate"
    private const val KEY_PENDING_TOKENS = "pending_tokens"
    private const val TOKEN_TTL_MS = 30_000L
}
