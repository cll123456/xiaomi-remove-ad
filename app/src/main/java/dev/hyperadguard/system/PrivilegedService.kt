package dev.hyperadguard.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Runs only inside Shizuku's shell-identity user-service process. */
class PrivilegedService : IPrivilegedService.Stub {
    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun execute(command: Array<out String>): String {
        require(command.isNotEmpty())
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "TIMEOUT"
        }
        return "${process.exitValue()}\n$output"
    }

    override fun captureScreenshot(): ByteArray {
        val process = ProcessBuilder("screencap", "-p").redirectErrorStream(true).start()
        val png = process.inputStream.use { it.readBytes() }
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly()
            return byteArrayOf()
        }
        val source = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return byteArrayOf()
        val targetWidth = 600
        val targetHeight = (source.height.toLong() * targetWidth / source.width).toInt()
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (scaled !== source) source.recycle()
        return ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
            scaled.recycle()
            output.toByteArray()
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
