package com.grupotgt.launcherkioscotgt.mdm

internal data class MdmAppInventory(
    val configured: List<String>,
    val installedConfigured: List<String>,
    val missingConfigured: List<String>
) {
    companion object {
        fun from(configured: Collection<String>, installed: Collection<String>): MdmAppInventory {
            val configuredSet = configured.map(String::trim).filter(String::isNotEmpty).toSortedSet()
            val installedSet = installed.map(String::trim).filter(String::isNotEmpty).toSet()
            val installedConfigured = configuredSet.filter(installedSet::contains)
            return MdmAppInventory(
                configured = configuredSet.toList(),
                installedConfigured = installedConfigured,
                missingConfigured = configuredSet.filterNot(installedSet::contains)
            )
        }
    }
}
