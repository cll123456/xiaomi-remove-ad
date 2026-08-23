package app.jingqi.guard.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/** Runs only inside the locally started shell-identity user-service process. */
class PrivilegedService : IPrivilegedService.Stub {
    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun isKnownPackageInstalled(packageId: Int): Boolean {
        val packageName = requireNotNull(PrivilegedContract.packageName(packageId)) {
            "Unknown package id: $packageId"
        }
        val result = runCommand("pm", "list", "packages", "--user", "0", packageName)
        return result.success && result.output.lineSequence().any { it == "package:$packageName" }
    }

    override fun removeKnownPackageForCurrentUser(packageId: Int): Boolean {
        val packageName = requireNotNull(PrivilegedContract.packageName(packageId)) {
            "Unknown package id: $packageId"
        }
        if (!isKnownPackageInstalled(packageId)) return true
        val result = runCommand("pm", "uninstall", "-k", "--user", "0", packageName)
        return result.success && !isKnownPackageInstalled(packageId)
    }

    override fun restoreKnownPackageForCurrentUser(packageId: Int): Boolean {
        val packageName = requireNotNull(PrivilegedContract.packageName(packageId)) {
            "Unknown package id: $packageId"
        }
        if (isKnownPackageInstalled(packageId)) return true
        val result = runCommand("cmd", "package", "install-existing", "--user", "0", packageName)
        return result.success && isKnownPackageInstalled(packageId)
    }

    override fun readKnownSetting(settingId: Int): String {
        val setting = requireNotNull(PrivilegedContract.setting(settingId)) {
            "Unknown setting id: $settingId"
        }
        val result = runCommand("settings", "get", setting.namespace, setting.key)
        check(result.success) { result.output.ifBlank { "Unable to read known setting" } }
        return result.output.trim().takeUnless { it == "null" } ?: PrivilegedContract.NULL_VALUE
    }

    override fun writeKnownSetting(settingId: Int, value: String, deleteValue: Boolean): Boolean {
        val setting = requireNotNull(PrivilegedContract.setting(settingId)) {
            "Unknown setting id: $settingId"
        }
        val result = if (deleteValue) {
            runCommand("settings", "delete", setting.namespace, setting.key)
        } else {
            require(PrivilegedContract.isAllowedSettingValue(settingId, value)) {
                "Rejected value for setting id $settingId"
            }
            runCommand("settings", "put", setting.namespace, setting.key, value)
        }
        if (!result.success) return false
        val actual = readKnownSetting(settingId)
        return if (deleteValue) actual == PrivilegedContract.NULL_VALUE else actual == value
    }

    override fun matchesKnownSplashProfile(profileId: Int): Boolean {
        val profile = when (profileId) {
            PrivilegedContract.SPLASH_CTRIP -> SplashProfile(
                left = 0.735f,
                top = 0.068f,
                right = 0.970f,
                bottom = 0.110f,
                whiteRange = 0.025f..0.100f
            )
            PrivilegedContract.SPLASH_IQIYI -> SplashProfile(
                left = 0.800f,
                top = 0.030f,
                right = 0.900f,
                bottom = 0.058f,
                whiteRange = 0.040f..0.110f
            )
            else -> throw IllegalArgumentException("Unknown splash profile id: $profileId")
        }
        val bitmap = captureScreen() ?: return false
        return try {
            profile.matches(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    private fun captureScreen(): Bitmap? {
        val process = ProcessBuilder("screencap", "-p").redirectErrorStream(true).start()
        val png = process.inputStream.use { it.readBytes() }
        if (!process.waitFor(SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0 || png.isEmpty()) return null
        return BitmapFactory.decodeByteArray(png, 0, png.size)
    }

    private fun runCommand(vararg command: String): CommandResult {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(false, "Command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return CommandResult(process.exitValue() == 0, output)
    }

    private data class CommandResult(val success: Boolean, val output: String)

    private data class SplashProfile(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val whiteRange: ClosedFloatingPointRange<Float>
    ) {
        fun matches(bitmap: Bitmap): Boolean {
            if (bitmap.width < 2 || bitmap.height < 2) return false
            val x0 = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
            val y0 = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
            val x1 = (bitmap.width * right).toInt().coerceIn(x0 + 1, bitmap.width)
            val y1 = (bitmap.height * bottom).toInt().coerceIn(y0 + 1, bitmap.height)
            var total = 0
            var white = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val color = bitmap.getPixel(x, y)
                    val red = android.graphics.Color.red(color)
                    val green = android.graphics.Color.green(color)
                    val blue = android.graphics.Color.blue(color)
                    val maximum = maxOf(red, green, blue)
                    val minimum = minOf(red, green, blue)
                    total++
                    if (minimum > 180 && maximum - minimum < 70) white++
                }
            }
            return total > 0 && white.toFloat() / total in whiteRange
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 15L
        const val SCREENSHOT_TIMEOUT_SECONDS = 5L
    }
}
