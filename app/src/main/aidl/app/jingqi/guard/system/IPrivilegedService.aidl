package app.jingqi.guard.system;

interface IPrivilegedService {
    boolean isKnownPackageInstalled(int packageId);
    boolean removeKnownPackageForCurrentUser(int packageId);
    boolean restoreKnownPackageForCurrentUser(int packageId);
    String readKnownSetting(int settingId);
    boolean writeKnownSetting(int settingId, String value, boolean deleteValue);
    boolean matchesKnownSplashProfile(int profileId);
    void destroy();
}
