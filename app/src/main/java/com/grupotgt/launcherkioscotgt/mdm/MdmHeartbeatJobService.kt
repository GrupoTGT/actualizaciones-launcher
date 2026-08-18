package com.grupotgt.launcherkioscotgt.mdm

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.grupotgt.launcherkioscotgt.AppLog
import com.grupotgt.launcherkioscotgt.MainActivity
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

class MdmHeartbeatJobService : JobService() {
    private val activeCalls = ConcurrentHashMap<Int, Call>()

    override fun onStartJob(params: JobParameters): Boolean {
        AppLog.inicializar(this)
        val result = runCatching {
            val cache = MdmConfigCache(this)
            val deviceId = cache.currentDeviceId()
            require(deviceId.isNotBlank()) { "device_id unavailable" }
            val secret = MdmCredentialStore(this).requireExistingSecret()
            val payload = MdmTelemetryCollector.collect(this)
            val client = MdmTelemetryClient()
            val prepared = client.prepare(deviceId, secret, payload)
            activeCalls[params.jobId] = prepared.call
            client.enqueue(prepared) { sent ->
                sent.onSuccess { response ->
                    AppLog.success("MDM HEARTBEAT -> telemetría firmada confirmada")
                    runCatching { applyAuthenticatedResponse(deviceId, response) }
                        .onFailure { error ->
                            AppLog.error("MDM HEARTBEAT -> respuesta no aplicada: ${error.message}")
                        }
                }.onFailure { error ->
                    AppLog.error("MDM HEARTBEAT -> no confirmada: ${error.message}")
                }
                if (activeCalls.remove(params.jobId) != null) {
                    jobFinished(params, MdmTransportPolicy.shouldRetry(sent.exceptionOrNull()))
                }
            }
        }
        result.onFailure { error ->
            AppLog.error("MDM HEARTBEAT -> no iniciado: ${error.message}")
            jobFinished(params, true)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeCalls.remove(params.jobId)?.cancel()
        return true
    }

    private fun applyAuthenticatedResponse(deviceId: String, response: MdmTelemetryResult) {
        if (response.approvalState != "APPROVED") return
        val cache = MdmConfigCache(this)
        val previousConfigRevision = cache.load().getOrNull()?.revision
        var updatedConfigRevision = previousConfigRevision
        response.configSnapshot?.let { snapshot ->
            cache.update(deviceId, snapshot)
                .onSuccess { updatedConfigRevision = it.revision }
                .onFailure { error ->
                    AppLog.error("MDM HEARTBEAT -> snapshot rechazado: ${error.message}")
                }
        }
        val configChanged = updatedConfigRevision != null &&
            updatedConfigRevision != previousConfigRevision
        val modeChanged = if (response.commandsEnabled) {
            val before = ManagedModeStore.state(this)
            if (!ManagedModeStore.acceptAuthenticated(this, response.mode, response.modeRevision)) {
                AppLog.warning("MDM HEARTBEAT -> modo rechazado por revisión obsoleta o contradictoria")
                false
            } else {
                before.desiredMode.wireValue != response.mode ||
                    before.desiredRevision != response.modeRevision ||
                    before.appliedMode.wireValue != response.mode ||
                    before.appliedRevision != response.modeRevision
            }
        } else {
            false
        }
        val pilotOta = response.pilotOtaAssignment?.let { assignmentJson ->
            MdmPilotOtaStore.update(this, deviceId, assignmentJson)
                .onFailure { error ->
                    AppLog.error("MDM HEARTBEAT -> asignación OTA piloto rechazada: ${error.message}")
                }
                .getOrNull()
        }
        if (modeChanged || configChanged) {
            val intent = Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RECONCILE_MANAGED_MODE)
                .putExtra(MainActivity.EXTRA_RECONCILE_MANAGED_MODE, true)
                .putExtra(
                    MainActivity.EXTRA_INTERNAL_COMMAND_TOKEN,
                    InternalCommandGate.issue(this, InternalCommandGate.ACTION_RECONCILE_MANAGED_MODE)
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            AppLog.info(
                "MDM HEARTBEAT -> reconciliación solicitada; " +
                    "command=${response.commandId}; configChanged=$configChanged"
            )
        }
        if (pilotOta != null) {
            val currentVersion = currentVersionCode()
            if (pilotOta.isEligible(currentVersion, System.currentTimeMillis())) {
                val intent = Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_APPLY_PILOT_OTA)
                    .putExtra(MainActivity.EXTRA_APPLY_PILOT_OTA, true)
                    .putExtra(
                        MainActivity.EXTRA_INTERNAL_COMMAND_TOKEN,
                        InternalCommandGate.issue(this, InternalCommandGate.ACTION_APPLY_PILOT_OTA)
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                AppLog.info(
                    "MDM HEARTBEAT -> OTA piloto autenticada solicitada; " +
                        "assignment=${pilotOta.assignmentId}; target=${pilotOta.versionCode}"
                )
            } else if (pilotOta.versionCode.toLong() <= currentVersion) {
                MdmPilotOtaStore.clear(this)
            }
        }
    }

    private fun currentVersionCode(): Long {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}

internal object MdmHeartbeatScheduler {
    private const val PERIODIC_JOB_ID = 64_005
    private const val IMMEDIATE_JOB_ID = 64_006
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val component = ComponentName(context, MdmHeartbeatJobService::class.java)
        val builder = JobInfo.Builder(PERIODIC_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        builder.setPeriodic(PERIOD_MS, 5 * 60 * 1000L)
        if (scheduler.schedule(builder.build()) != JobScheduler.RESULT_SUCCESS) {
            AppLog.error("MDM HEARTBEAT -> JobScheduler rechazó el trabajo periódico")
        }
    }

    @SuppressLint("SpecifyJobSchedulerIdRange")
    fun enqueueImmediate(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val component = ComponentName(context, MdmHeartbeatJobService::class.java)
        val result = scheduler.schedule(
            JobInfo.Builder(IMMEDIATE_JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1_000L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        )
        if (result != JobScheduler.RESULT_SUCCESS) {
            AppLog.error("MDM HEARTBEAT -> JobScheduler rechazó el trabajo inmediato")
        }
    }
}
