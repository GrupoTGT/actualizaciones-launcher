package com.grupotgt.launcherkioscotgt.mdm

internal data class MdmNetworkState(
    val transport: String,
    val wifiConnected: Boolean,
    val mobileConnected: Boolean,
    val internetValidated: Boolean,
    val airplaneMode: Boolean,
    val vowifiState: String = "NO VERIFICABLE"
) {
    val status: String
        get() = when {
            airplaneMode && !internetValidated -> "MODO AVION SIN INTERNET"
            wifiConnected && !internetValidated -> "WIFI SIN INTERNET VALIDADO"
            internetValidated -> "INTERNET VALIDADO"
            else -> "SIN CONEXION VALIDADA"
        }
}

internal object MdmNetworkStateFactory {
    fun create(
        wifiConnected: Boolean,
        mobileConnected: Boolean,
        ethernetConnected: Boolean,
        internetValidated: Boolean,
        airplaneMode: Boolean
    ): MdmNetworkState {
        val transport = when {
            wifiConnected -> "WIFI"
            mobileConnected -> "MOVIL"
            ethernetConnected -> "ETHERNET"
            else -> "SIN CONEXION"
        }
        return MdmNetworkState(
            transport, wifiConnected, mobileConnected, internetValidated, airplaneMode
        )
    }
}
