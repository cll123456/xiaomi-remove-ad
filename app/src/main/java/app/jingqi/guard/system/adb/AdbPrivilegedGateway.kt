package app.jingqi.guard.system.adb

import app.jingqi.guard.system.PrivilegedContract
import app.jingqi.guard.system.PrivilegedGateway

internal class AdbPrivilegedGateway : PrivilegedGateway {
    override suspend fun isKnownPackageInstalled(packageId: Int): Boolean {
        val packageName = requireNotNull(PrivilegedContract.packageName(packageId))
        val result = EmbeddedAdbRuntime.executeKnown(KnownAdbOperation.IsPackageInstalled(packageId))
        return result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "package:$packageName" }
    }

    override suspend fun removeKnownPackageForCurrentUser(packageId: Int): Boolean {
        if (!isKnownPackageInstalled(packageId)) return true
        val result = EmbeddedAdbRuntime.executeKnown(KnownAdbOperation.RemovePackage(packageId))
        return result.exitCode == 0 && !isKnownPackageInstalled(packageId)
    }

    override suspend fun restoreKnownPackageForCurrentUser(packageId: Int): Boolean {
        if (isKnownPackageInstalled(packageId)) return true
        val result = EmbeddedAdbRuntime.executeKnown(KnownAdbOperation.RestorePackage(packageId))
        return result.exitCode == 0 && isKnownPackageInstalled(packageId)
    }

    override suspend fun readKnownSetting(settingId: Int): String? {
        val result = EmbeddedAdbRuntime.executeKnown(KnownAdbOperation.ReadSetting(settingId))
        check(result.exitCode == 0) { result.allOutput.ifBlank { "无法读取固定系统设置" } }
        return result.output.trim().takeUnless { it == "null" }
    }

    override suspend fun writeKnownSetting(settingId: Int, value: String?, deleteValue: Boolean): Boolean {
        val result = EmbeddedAdbRuntime.executeKnown(
            KnownAdbOperation.WriteSetting(settingId, value, deleteValue)
        )
        if (result.exitCode != 0) return false
        val actual = readKnownSetting(settingId)
        return if (deleteValue) actual == null else actual == value
    }
}
