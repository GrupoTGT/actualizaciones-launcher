package com.grupotgt.launcherkioscotgt.mdm

import java.io.IOException

internal object MdmTransportPolicy {
    fun validatedBody(
        statusCode: Int,
        successful: Boolean,
        rawBody: String,
        maxBytes: Int,
        service: String = "SAFE BRIDGE"
    ): String {
        if (!successful) {
            if (statusCode == 408 || statusCode == 429 || statusCode in 500..599) {
                throw IOException("$service HTTP $statusCode")
            }
            error("$service HTTP $statusCode")
        }
        if (rawBody.isBlank() || rawBody.length > maxBytes) error("$service returned an invalid body")
        return rawBody
    }

    fun shouldRetry(error: Throwable?): Boolean = error is IOException
}
