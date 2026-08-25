# Conscrypt contains compatibility adapters for Android releases below our
# minSdk, where these platform-private parameter classes may not exist.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Kadb has a fallback branch for the Android platform Conscrypt provider. This
# build always packages public Conscrypt and deliberately excludes the hidden
# API bypass dependency, so that fallback class is unreachable.
-dontwarn org.lsposed.hiddenapibypass.HiddenApiBypass

# Bouncy Castle loads the X.509 mapping class and registers the factory by
# string name. Preserve only that reflective surface; the LDAP/JNDI certificate
# stores and unrelated provider algorithms are not part of the Android build.
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509$Mappings { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.CertificateFactory { *; }
