package com.grupotgt.launcherkioscotgt.mdm

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.grupotgt.launcherkioscotgt.AppLog

internal object MdmEnrollmentCoordinator {
    @SuppressLint("HardwareIds") // Must preserve the fleet's existing ANDROID_ID identity.
    fun enroll(
        context: Context,
        endpoint: String,
        onAuthenticatedEnrollment: ((MdmEnrollmentResult) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val credentialStore = MdmCredentialStore(appContext)
        val deviceId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        if (deviceId.isBlank()) {
            AppLog.error("MDM SAFE BRIDGE -> device_id no disponible")
            return
        }
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val appVersion = packageInfo.versionName ?: "NO DISPONIBLE"
        val secret = runCatching { credentialStore.getOrCreateSecret() }
            .getOrElse { error ->
                AppLog.error("MDM SAFE BRIDGE -> credencial local no disponible: ${error.message}")
                return
            }
        runCatching {
            MdmEnrollmentClient(endpoint).enroll(
                deviceId = deviceId,
                secret = secret,
                appVersion = appVersion,
                model = Build.MODEL ?: "NO DISPONIBLE",
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            ) { result ->
                result.onSuccess { enrollment ->
                    appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_APPROVAL_STATE, enrollment.approvalState)
                        .putString(KEY_FINGERPRINT, enrollment.credentialFingerprint)
                        .putString(KEY_PROFILE, enrollment.profileId)
                        .putString(KEY_MODE, enrollment.mode)
                        .putBoolean(KEY_COMMANDS_ENABLED, enrollment.commandsEnabled)
                        .apply()
                    AppLog.info(
                        "MDM SAFE BRIDGE -> autoalta ${enrollment.approvalState}; " +
                            "huella=${enrollment.credentialFingerprint}; comandos=${enrollment.commandsEnabled}"
                    )
                    enrollment.configSnapshot?.let { snapshot ->
                        MdmConfigCache(appContext).update(deviceId, snapshot)
                            .onSuccess { valid ->
                                AppLog.info(
                                    "MDM CACHE -> snapshot válido rev=${valid.revision}; " +
                                        "contactos=${valid.contacts.size}; apps=${valid.apps.size}"
                                )
                            }
                            .onFailure { error ->
                                AppLog.error("MDM CACHE -> snapshot rechazado: ${error.message}")
                            }
                    }
                    onAuthenticatedEnrollment?.invoke(enrollment)
                }.onFailure { error ->
                    AppLog.error("MDM SAFE BRIDGE -> autoalta no confirmada: ${error.message}")
                }
            }
        }.onFailure { error ->
            AppLog.error("MDM SAFE BRIDGE -> solicitud de autoalta no iniciada: ${error.message}")
        }
    }

    private const val PREFERENCES = "MdmEnrollmentState"
    private const val KEY_APPROVAL_STATE = "approval_state"
    private const val KEY_FINGERPRINT = "credential_fingerprint"
    private const val KEY_PROFILE = "profile_id"
    private const val KEY_MODE = "mode"
    private const val KEY_COMMANDS_ENABLED = "commands_enabled"
}
