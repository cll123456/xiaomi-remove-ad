package app.jingqi.guard.system

internal interface PrivilegedGateway {
    suspend fun isKnownPackageInstalled(packageId: Int): Boolean
    suspend fun removeKnownPackageForCurrentUser(packageId: Int): Boolean
    suspend fun restoreKnownPackageForCurrentUser(packageId: Int): Boolean
    suspend fun readKnownSetting(settingId: Int): String?
    suspend fun writeKnownSetting(settingId: Int, value: String?, deleteValue: Boolean): Boolean
}
