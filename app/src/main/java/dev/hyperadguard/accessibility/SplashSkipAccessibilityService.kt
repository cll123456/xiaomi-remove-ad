package dev.hyperadguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.hyperadguard.system.IPrivilegedService
import dev.hyperadguard.system.PrivilegedService
import rikka.shizuku.Shizuku
import java.util.ArrayDeque
import java.util.concurrent.Executors

class SplashSkipAccessibilityService : AccessibilityService() {
    private var activePackage = ""
    private var packageEnteredAt = 0L
    private var lastAttemptAt = 0L
    private var canvasHandledForEntry = false
    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var privilegedService: IPrivilegedService? = null
    private var shizukuBinding = false
    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, PrivilegedService::class.java.name))
            .daemon(false).processNameSuffix("governance").debuggable(false).version(9)
    }
    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            privilegedService = IPrivilegedService.Stub.asInterface(binder)
            shizukuBinding = false
        }

        override fun onServiceDisconnected(name: ComponentName) {
            privilegedService = null
            shizukuBinding = false
        }
    }
    private val shizukuReceived = Shizuku.OnBinderReceivedListener { bindShizukuIfAvailable() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        getSharedPreferences("splash_skip", MODE_PRIVATE).edit()
            .remove("debug_events")
            .remove("debug_event_package")
            .remove("debug_canvas")
            .apply()
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        bindShizukuIfAvailable()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Canvas/video splash windows can temporarily expose no accessibility
        // root at all. The event package is still available and must be used to
        // schedule screenshot-based checks before falling back to node scanning.
        val root = rootInActiveWindow
        val packageName = event.packageName?.toString() ?: root?.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()
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

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuReceived)
        runCatching { Shizuku.unbindUserService(shizukuArgs, shizukuConnection, false) }
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindShizukuIfAvailable() {
        if (privilegedService != null || shizukuBinding) return
        val ready = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!ready) return
        shizukuBinding = true
        runCatching { Shizuku.bindUserService(shizukuArgs, shizukuConnection) }
            .onFailure { shizukuBinding = false }
    }

    private fun scheduleCanvasSplashChecks(packageName: String) {
        val config = CANVAS_SPLASH_CONFIGS[packageName] ?: return
        handler.postDelayed({
            if (activePackage == packageName && !canvasHandledForEntry &&
                SystemClock.elapsedRealtime() - packageEnteredAt <= CANVAS_WINDOW_MS
            ) {
                inspectCanvasSplash(packageName, config)
            }
        }, CANVAS_TAP_DELAY_MS)
    }

    /**
     * Ctrip and iQIYI draw their splash close button into a video/canvas layer.
     * A Shizuku shell process captures and downsizes the current frame, then
     * returns only a small JPEG for in-memory matching. Nothing is saved or sent.
     */
    private fun inspectCanvasSplash(packageName: String, config: CanvasSplashConfig) {
        val privileged = privilegedService ?: return
        screenshotExecutor.execute {
            val bytes = runCatching { privileged.captureScreenshot() }.getOrDefault(byteArrayOf())
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
            val matched = try { config.matches(bitmap) } finally { bitmap.recycle() }
            if (matched) handler.post {
                if (activePackage == packageName && !canvasHandledForEntry) tapCanvasSkip(packageName, config)
            }
        }
    }

    private fun tapCanvasSkip(packageName: String, config: CanvasSplashConfig) {
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
        if (packageName in VERIFIED_PACKAGES) return Mode.VERIFIED
        if (packageName in FINANCIAL_EXACT_PACKAGES) return Mode.FINANCIAL_EXACT
        if (packageName in SENSITIVE_PACKAGES || SENSITIVE_HINTS.any { packageName.contains(it, ignoreCase = true) }) {
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

    private data class CanvasSplashConfig(
        val tapX: Float,
        val tapY: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val whiteRange: ClosedFloatingPointRange<Float>
    ) {
        fun matches(bitmap: Bitmap): Boolean {
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
                    val max = maxOf(red, green, blue)
                    val min = minOf(red, green, blue)
                    total++
                    if (min > 180 && max - min < 70) white++
                }
            }
            return total > 0 && white.toFloat() / total in whiteRange
        }
    }

    companion object {
        private const val STARTUP_WINDOW_MS = 15_000L
        private const val CANVAS_WINDOW_MS = 3_000L
        private const val CANVAS_TAP_DELAY_MS = 1_050L
        private val CANVAS_SPLASH_CONFIGS = mapOf(
            // Ctrip 8.95.x: top-right “N 跳过广告” pill.
            "ctrip.android.view" to CanvasSplashConfig(
                tapX = 0.848f, tapY = 0.088f,
                left = 0.735f, top = 0.068f, right = 0.970f, bottom = 0.110f,
                whiteRange = 0.025f..0.100f
            ),
            // iQIYI 17.8.x: top-right rounded “关闭” button.
            "com.qiyi.video" to CanvasSplashConfig(
                tapX = 0.850f, tapY = 0.042f,
                left = 0.800f, top = 0.030f, right = 0.900f, bottom = 0.058f,
                whiteRange = 0.040f..0.110f
            )
        )
        private val VERIFIED_PACKAGES = setOf("com.lingan.seeyou", "com.MobileTicket")
        private val FINANCIAL_EXACT_PACKAGES = setOf("cmb.pb", "com.yitong.mbank.psbc")
        private val SENSITIVE_PACKAGES = setOf(
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.mipay.wallet",
            "com.android.icredit"
        )
        private val SENSITIVE_HINTS = listOf(
            "bank", "mbank", "wallet", "alipay", "unionpay", "payment", "finance",
            "securities", "broker", "authenticator", "password", "keychain"
        )
        private val SENSITIVE_LABELS = listOf("银行", "支付", "证券", "钱包", "金融", "保险")
        private val SKIP_PATTERN = Regex("^跳过(?:广告)?(?:\\s*[0-9]+\\s*(?:秒|s)?)?.*$", RegexOption.IGNORE_CASE)
        private val STRICT_SKIP_PATTERN = Regex("^跳过(?:广告)?(?:\\s*[0-9]+\\s*(?:秒|s)?)?$", RegexOption.IGNORE_CASE)
    }
}
