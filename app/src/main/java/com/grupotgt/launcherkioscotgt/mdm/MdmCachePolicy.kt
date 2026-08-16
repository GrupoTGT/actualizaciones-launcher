package com.grupotgt.launcherkioscotgt.mdm

internal enum class MdmCacheDecision { APPLY, UNCHANGED, REJECT_STALE, REJECT_CONFLICT }

internal object MdmCachePolicy {
    fun decide(
        currentRevision: Long,
        currentHash: String?,
        candidateRevision: Long,
        candidateHash: String
    ): MdmCacheDecision = when {
        candidateRevision < currentRevision -> MdmCacheDecision.REJECT_STALE
        candidateRevision == currentRevision && currentHash != null && currentHash != candidateHash ->
            MdmCacheDecision.REJECT_CONFLICT
        candidateRevision == currentRevision && currentHash == candidateHash -> MdmCacheDecision.UNCHANGED
        else -> MdmCacheDecision.APPLY
    }
}
