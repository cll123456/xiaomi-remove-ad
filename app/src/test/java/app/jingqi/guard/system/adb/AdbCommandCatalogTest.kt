package app.jingqi.guard.system.adb

import app.jingqi.guard.system.PrivilegedContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbCommandCatalogTest {
    @Test
    fun packageOperationsUseOnlyTheCompiledPackageId() {
        assertEquals(
            "pm list packages --user 0 'com.miui.systemAdSolution'",
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.IsPackageInstalled(PrivilegedContract.PACKAGE_MSA)
            )
        )
        assertEquals(
            "pm uninstall -k --user 0 'com.miui.systemAdSolution'",
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.RemovePackage(PrivilegedContract.PACKAGE_MSA)
            )
        )
        assertEquals(
            "cmd package install-existing --user 0 'com.miui.systemAdSolution'",
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.RestorePackage(PrivilegedContract.PACKAGE_MSA)
            )
        )
    }

    @Test
    fun knownSettingCommandsAreDeterministic() {
        assertEquals(
            "settings get system 'predownload_cloud_enable'",
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.ReadSetting(PrivilegedContract.SETTING_PREDOWNLOAD_CLOUD)
            )
        )
        assertEquals(
            "settings put system 'predownload_cloud_enable' '0'",
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.WriteSetting(
                    PrivilegedContract.SETTING_PREDOWNLOAD_CLOUD,
                    value = "0",
                    deleteValue = false
                )
            )
        )
        assertEquals(
            "settings delete system 'predownload_cloud_enable'",
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.WriteSetting(
                    PrivilegedContract.SETTING_PREDOWNLOAD_CLOUD,
                    value = null,
                    deleteValue = true
                )
            )
        )
    }

    @Test
    fun unknownIdsAndUnapprovedValuesAreRejectedBeforeShell() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbCommandCatalog.commandFor(KnownAdbOperation.IsPackageInstalled(Int.MAX_VALUE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.WriteSetting(
                    PrivilegedContract.SETTING_PREDOWNLOAD_CLOUD,
                    value = "0; id",
                    deleteValue = false
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdbCommandCatalog.commandFor(
                KnownAdbOperation.WriteSetting(
                    PrivilegedContract.SETTING_PREDOWNLOAD_TASKS,
                    value = "[\n]",
                    deleteValue = false
                )
            )
        }
    }
}
