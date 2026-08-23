package app.jingqi.guard.system.adb

import app.jingqi.guard.system.PrivilegedContract

internal sealed interface KnownAdbOperation {
    data class IsPackageInstalled(val packageId: Int) : KnownAdbOperation
    data class RemovePackage(val packageId: Int) : KnownAdbOperation
    data class RestorePackage(val packageId: Int) : KnownAdbOperation
    data class ReadSetting(val settingId: Int) : KnownAdbOperation
    data class WriteSetting(val settingId: Int, val value: String?, val deleteValue: Boolean) : KnownAdbOperation
}

internal object AdbCommandCatalog {
    fun commandFor(operation: KnownAdbOperation): String = when (operation) {
        is KnownAdbOperation.IsPackageInstalled -> {
            val packageName = requirePackage(operation.packageId)
            "pm list packages --user 0 ${quote(packageName)}"
        }
        is KnownAdbOperation.RemovePackage -> {
            val packageName = requirePackage(operation.packageId)
            "pm uninstall -k --user 0 ${quote(packageName)}"
        }
        is KnownAdbOperation.RestorePackage -> {
            val packageName = requirePackage(operation.packageId)
            "cmd package install-existing --user 0 ${quote(packageName)}"
        }
        is KnownAdbOperation.ReadSetting -> {
            val setting = requireSetting(operation.settingId)
            "settings get ${setting.namespace} ${quote(setting.key)}"
        }
        is KnownAdbOperation.WriteSetting -> {
            val setting = requireSetting(operation.settingId)
            if (operation.deleteValue) {
                require(operation.value == null) { "Delete operation cannot carry a value" }
                "settings delete ${setting.namespace} ${quote(setting.key)}"
            } else {
                val value = requireNotNull(operation.value)
                require(PrivilegedContract.isAllowedSettingValue(operation.settingId, value)) {
                    "Rejected value for setting id ${operation.settingId}"
                }
                "settings put ${setting.namespace} ${quote(setting.key)} ${quote(value)}"
            }
        }
    }

    private fun requirePackage(id: Int): String = requireNotNull(PrivilegedContract.packageName(id)) {
        "Unknown package id: $id"
    }

    private fun requireSetting(id: Int): PrivilegedContract.KnownSetting =
        requireNotNull(PrivilegedContract.setting(id)) { "Unknown setting id: $id" }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
