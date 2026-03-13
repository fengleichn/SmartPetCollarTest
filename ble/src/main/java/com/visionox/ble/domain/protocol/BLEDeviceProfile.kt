package com.visionox.ble.domain.protocol

import java.util.UUID

interface BLEDeviceProfile {
    val serviceUuid: UUID
    val dataCharacteristicUuid: UUID
    val deviceNameFilter: String?  // 设备名过滤条件
    val nameMatchMode: NameMatchMode  // 匹配方式
    val deviceAddress: String?

    /**
     * 是否在扫描时使用 Service UUID 过滤
     * - true: 扫描时按 serviceUuid 过滤（设备广播包必须包含该 UUID）
     * - false: 扫描时不按服务过滤，仅靠名称/地址匹配（适用于广播包不携带服务 UUID 的设备）
     */
    val scanWithServiceUuidFilter: Boolean
        get() = false  // 默认不过滤，因为很多设备广播包不携带服务 UUID

    enum class NameMatchMode {
        PREFIX,   // 前缀匹配 (startsWith)
        EXACT,    // 精确匹配 (equals)
        CONTAINS  // 包含匹配 (contains)
    }

    fun matches(name: String?, address: String?): Boolean {
        val filter = deviceNameFilter
        val devAddr = deviceAddress

        val nameMatches = when {
            filter == null -> true
            name == null -> false
            else -> when (nameMatchMode) {
                NameMatchMode.PREFIX -> name.startsWith(filter, ignoreCase = true)
                NameMatchMode.EXACT -> name.equals(filter, ignoreCase = true)
                NameMatchMode.CONTAINS -> name.contains(filter, ignoreCase = true)
            }
        }

        val addressMatches = devAddr == null || address?.equals(devAddr, ignoreCase = true) == true

        return nameMatches && addressMatches
    }
}
