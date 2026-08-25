package app.jingqi.guard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.jingqi.guard.rules.BuiltInRuleCatalog
import app.jingqi.guard.rules.NodePolicy
import app.jingqi.guard.rules.VisualSplashRule
import app.jingqi.guard.system.adb.AdbPairingService
import app.jingqi.guard.system.adb.EmbeddedAdbRuntime
import app.jingqi.guard.system.adb.PairingStatus
import app.jingqi.guard.system.adb.WirelessPairingText
import app.jingqi.guard.system.adb.WirelessPairingTextDetector
import java.util.ArrayDeque
import java.util.concurrent.Executors

class SplashSkipAccessibilityService : AccessibilityService() {
    private var activePackage = ""
    private var packageEnteredAt = 0L
    private var lastAttemptAt = 0L
    private var canvasHandledForEntry = false
    private var lastSubmittedPairingCode = ""
    private var lastPairingSubmissionAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        getSharedPreferences("splash_skip", MODE_PRIVATE).edit()
            .remove("debug_events")
            .remove("debug_event_package")
            .remove("debug_canvas")
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Canvas/video splash windows can temporarily expose no accessibility
        // root at all. The event package is still available and must be used to
        // schedule screenshot-based checks before falling back to node scanning.
        val root = rootInActiveWindow
        val packageName = event.packageName?.toString() ?: root?.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()
        if (maybeSubmitWirelessPairingCode(packageName, root, now)) return
        if (packageName != activePackage) {
            activePackage = packageName
            packageEnteredAt = now
            canvasHandledForEntry = false
            scheduleCanvasSplashChecks(packageName)
        }
        if (now - packageEnteredAt > STARTUP_WINDOW_MS || now - lastAttemptAt < 250) return

        val mode = modeFor(packageName)
        if (mode == Mode.BLOCKED) return
        root ?: return
        val skip = findSkipNode(root, mode) ?: return
        lastAttemptAt = now
        val clicked = clickNodeOrParent(skip)
        if (!clicked && mode == Mode.VERIFIED) tapNode(skip)
        if (clicked || mode == Mode.VERIFIED) recordSkip(packageName)
    }

    override fun onInterrupt() = Unit

    /**
     * The user must explicitly start pairing first. During that short session we inspect only
     * Android Settings and require pairing-specific labels before accepting an exact six-digit
     * value. This keeps the system dialog foreground, so HyperOS cannot rotate the pairing code.
     */
    private fun maybeSubmitWirelessPairingCode(
        packageName: String,
        root: AccessibilityNodeInfo?,
        now: Long
    ): Boolean {
        if (packageName != ANDROID_SETTINGS_PACKAGE || root == null) return false
        val pairing = EmbeddedAdbRuntime.activePairingStatus() ?: return false
        if (pairing.phase != PairingStatus.Phase.WAITING_FOR_CODE) return false

        val code = WirelessPairingTextDetector.findCode(collectPairingText(root)) ?: return false
        if (code == lastSubmittedPairingCode && now - lastPairingSubmissionAt < PAIRING_DEDUP_MS) {
            return true
        }
        if (!AdbPairingService.submitCode(this, code)) return false
        lastSubmittedPairingCode = code
        lastPairingSubmissionAt = now
        return true
    }

    private fun collectPairingText(root: AccessibilityNodeInfo): List<WirelessPairingText> {
        val result = ArrayList<WirelessPairingText>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_PAIRING_NODES) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName.orEmpty()
            node.text?.toString()?.takeIf(String::isNotBlank)?.let {
                result += WirelessPairingText(it, viewId)
            }
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let {
                result += WirelessPairingText(it, viewId)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return result
    }

    override fun onDestroy() {
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun scheduleCanvasSplashChecks(packageName: String) {
        val config = BuiltInRuleCatalog.find(packageName)?.visualSplash ?: return
        handler.postDelayed({
            if (activePackage == packageName && !canvasHandledForEntry &&
                SystemClock.elapsedRealtime() - packageEnteredAt <= config.activeWindowMillis
            ) {
                inspectCanvasSplash(packageName, config)
            }
        }, config.startupDelayMillis)
    }

    /**
     * Ctrip and iQIYI draw the close control into a video/canvas layer. Android's
     * accessibility screenshot API evaluates a fixed region here in memory; the
     * image is never written, returned to the app UI, or uploaded.
     */
    private fun inspectCanvasSplash(packageName: String, config: VisualSplashRule) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = runCatching {
                        val buffer = screenshot.hardwareBuffer
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            try {
                                hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                hardwareBitmap?.recycle()
                            }
                        } finally {
                            buffer.close()
                        }
                    }.getOrNull() ?: return
                    val matched = try {
                        SplashImageMatcher.matches(config.profileId, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    if (matched) handler.post {
                        if (activePackage == packageName && !canvasHandledForEntry) {
                            tapCanvasSkip(packageName, config)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) = Unit
            }
        )
    }

    private fun tapCanvasSkip(packageName: String, config: VisualSplashRule) {
        canvasHandledForEntry = true
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val path = Path().apply { moveTo(width * config.tapX, height * config.tapY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                recordSkip(packageName)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                canvasHandledForEntry = false
            }
        }, null)
    }

    private fun modeFor(packageName: String): Mode {
        if (packageName == applicationContext.packageName) return Mode.BLOCKED
        BuiltInRuleCatalog.find(packageName)?.let { rule ->
            return when (rule.nodePolicy) {
                NodePolicy.VERIFIED -> Mode.VERIFIED
                NodePolicy.GENERAL -> Mode.GENERAL
                NodePolicy.FINANCIAL_EXACT -> Mode.FINANCIAL_EXACT
                NodePolicy.BLOCKED -> Mode.BLOCKED
            }
        }
        if (SENSITIVE_HINTS.any { packageName.contains(it, ignoreCase = true) }) {
            return Mode.BLOCKED
        }
        val appInfo = try { packageManager.getApplicationInfo(packageName, 0) } catch (_: Throwable) { return Mode.BLOCKED }
        if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return Mode.BLOCKED
        val appLabel = runCatching { appInfo.loadLabel(packageManager).toString() }.getOrDefault("")
        if (SENSITIVE_LABELS.any { appLabel.contains(it, ignoreCase = true) }) return Mode.BLOCKED
        return Mode.GENERAL
    }

    private fun findSkipNode(root: AccessibilityNodeInfo, mode: Mode): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val skipText = label.matches(SKIP_PATTERN)
            val strictSkipText = label.matches(STRICT_SKIP_PATTERN)
            val skipId = viewId.contains("skip", ignoreCase = true)
            val splashId = viewId.contains("splash", ignoreCase = true)
            val matches = when (mode) {
                Mode.VERIFIED -> skipText || skipId
                Mode.GENERAL -> skipText && (skipId || hasClickableParent(node))
                Mode.FINANCIAL_EXACT -> strictSkipText && (skipId || splashId) && hasClickableParent(node)
                Mode.BLOCKED -> false
            }
            if (matches) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    private fun hasClickableParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(4) {
            val current = node ?: return false
            if (current.isClickable) return true
            node = current.parent
        }
        return false
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(4) {
            val current = node ?: return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = current.parent
        }
        return false
    }

    private fun tapNode(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun recordSkip(packageName: String) {
        val prefs = getSharedPreferences("splash_skip", MODE_PRIVATE)
        prefs.edit()
            .putString("last_package", packageName)
            .putLong("last_time", System.currentTimeMillis())
            .putLong("total", prefs.getLong("total", 0L) + 1L)
            .apply()
    }

    private enum class Mode { VERIFIED, GENERAL, FINANCIAL_EXACT, BLOCKED }

    companion object {
        private const val STARTUP_WINDOW_MS = 15_000L
        private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
        private const val MAX_PAIRING_NODES = 240
        private const val PAIRING_DEDUP_MS = 15_000L
        private val SENSITIVE_HINTS = listOf(
            "bank", "mbank", "wallet", "alipay", "unionpay", "payment", "finance",
            "securities", "broker", "authenticator", "password", "keychain"
        )
        private val SENSITIVE_LABELS = listOf("银行", "支付", "证券", "钱包", "金融", "保险")
        private val SKIP_PATTERN = Regex("^跳过(?:广告)?(?:\\s*[0-9]+\\s*(?:秒|s)?)?.*$", RegexOption.IGNORE_CASE)
        private val STRICT_SKIP_PATTERN = Regex("^跳过(?:广告)?(?:\\s*[0-9]+\\s*(?:秒|s)?)?$", RegexOption.IGNORE_CASE)
    }
}

private object SplashImageMatcher {
    private data class Profile(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val whiteRange: ClosedFloatingPointRange<Float>
    )

    fun matches(profileId: Int, bitmap: Bitmap): Boolean {
        val profile = when (profileId) {
            app.jingqi.guard.system.PrivilegedContract.SPLASH_CTRIP -> Profile(
                left = 0.735f,
                top = 0.068f,
                right = 0.970f,
                bottom = 0.110f,
                whiteRange = 0.025f..0.100f
            )
            app.jingqi.guard.system.PrivilegedContract.SPLASH_IQIYI -> Profile(
                left = 0.800f,
                top = 0.030f,
                right = 0.900f,
                bottom = 0.058f,
                whiteRange = 0.040f..0.110f
            )
            else -> return false
        }
        if (bitmap.width < 2 || bitmap.height < 2) return false
        val x0 = (bitmap.width * profile.left).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = (bitmap.height * profile.top).toInt().coerceIn(0, bitmap.height - 1)
        val x1 = (bitmap.width * profile.right).toInt().coerceIn(x0 + 1, bitmap.width)
        val y1 = (bitmap.height * profile.bottom).toInt().coerceIn(y0 + 1, bitmap.height)
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
        return total > 0 && white.toFloat() / total in profile.whiteRange
    }
}
