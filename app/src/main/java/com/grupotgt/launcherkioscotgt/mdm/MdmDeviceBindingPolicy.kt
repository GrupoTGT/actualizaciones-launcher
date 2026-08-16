package com.grupotgt.launcherkioscotgt.mdm

internal object MdmDeviceBindingPolicy {
    fun mustReset(boundDeviceId: String?, currentDeviceId: String, containsState: Boolean): Boolean {
        require(currentDeviceId.isNotBlank()) { "device_id unavailable" }
        return (boundDeviceId.isNullOrBlank() && containsState) ||
            (!boundDeviceId.isNullOrBlank() && boundDeviceId != currentDeviceId)
    }
}
