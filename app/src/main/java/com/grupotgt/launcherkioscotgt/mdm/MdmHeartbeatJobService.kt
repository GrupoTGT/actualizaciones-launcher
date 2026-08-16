package com.grupotgt.launcherkioscotgt.mdm

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.grupotgt.launcherkioscotgt.AppLog
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
            val call = MdmTelemetryClient().send(deviceId, secret, payload) { sent ->
                sent.onSuccess {
                    AppLog.success("MDM HEARTBEAT -> telemetría firmada confirmada")
                }.onFailure { error ->
                    AppLog.error("MDM HEARTBEAT -> no confirmada: ${error.message}")
                }
                if (activeCalls.remove(params.jobId) != null) {
                    jobFinished(params, MdmTransportPolicy.shouldRetry(sent.exceptionOrNull()))
                }
            }
            activeCalls[params.jobId] = call
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
        scheduler.schedule(builder.build())
    }

    @SuppressLint("SpecifyJobSchedulerIdRange")
    fun enqueueImmediate(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val component = ComponentName(context, MdmHeartbeatJobService::class.java)
        scheduler.schedule(
            JobInfo.Builder(IMMEDIATE_JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1_000L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        )
    }
}
