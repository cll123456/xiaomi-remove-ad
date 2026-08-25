package app.jingqi.guard.system.adb

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.Security
import java.security.cert.CertificateFactory

class CryptoProviderBootstrapTest {
    @Test
    fun installsBcProviderWithX509CertificateFactory() {
        CryptoProviderBootstrap.ensureBouncyCastleX509Available()

        assertNotNull(Security.getProvider("BC"))
        assertNotNull(CertificateFactory.getInstance("X.509", "BC"))
    }
}
