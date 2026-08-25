package app.jingqi.guard.system.adb

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateFactory

/** Replaces Android's intentionally reduced BC provider with the packaged provider Kadb requests by name. */
internal object CryptoProviderBootstrap {
    @Synchronized
    fun ensureBouncyCastleX509Available() {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing is BouncyCastleProvider && supportsX509(existing)) return

        if (existing != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        val installedPosition = Security.addProvider(BouncyCastleProvider())
        check(installedPosition != -1 || Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) {
            "无法安装本机配对所需的加密提供程序"
        }
        check(supportsX509(requireNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)))) {
            "本机配对所需的 X.509 证书能力不可用"
        }
    }

    private fun supportsX509(provider: Provider): Boolean = runCatching {
        CertificateFactory.getInstance("X.509", provider)
    }.isSuccess
}
