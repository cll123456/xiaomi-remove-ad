# Conscrypt contains compatibility adapters for Android releases below our
# minSdk, where these platform-private parameter classes may not exist.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Kadb has a fallback branch for the Android platform Conscrypt provider. This
# build always packages public Conscrypt and deliberately excludes the hidden
# API bypass dependency, so that fallback class is unreachable.
-dontwarn org.lsposed.hiddenapibypass.HiddenApiBypass
