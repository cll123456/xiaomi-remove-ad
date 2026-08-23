package app.jingqi.guard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.jingqi.guard.rules.BuiltInRuleCatalog
import app.jingqi.guard.rules.NodePolicy
import app.jingqi.guard.rules.VisualSplashRule
import app.jingqi.guard.system.IPrivilegedService
import app.jingqi.guard.system.PrivilegedService
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
            .daemon(false).processNameSuffix("governance").debuggable(false).version(10)
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
    private val shizukuPermissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bindShizukuIfAvailable()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        getSharedPreferences("splash_skip", MODE_PRIVATE).edit()
            .remove("debug_events")
            .remove("debug_event_package")
            .remove("debug_canvas")
            .apply()
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult)
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
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult)
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
     * Ctrip and iQIYI draw their splash close button into a video/canvas layer.
     * The shell process captures and evaluates a fixed, built-in region itself.
     * Only a boolean match result crosses Binder; no screen image is returned,
     * written to storage, or uploaded.
     */
    private fun inspectCanvasSplash(packageName: String, config: VisualSplashRule) {
        val privileged = privilegedService ?: run {
            bindShizukuIfAvailable()
            handler.postDelayed({
                if (activePackage == packageName && !canvasHandledForEntry &&
                    SystemClock.elapsedRealtime() - packageEnteredAt <= config.activeWindowMillis
                ) {
                    inspectCanvasSplash(packageName, config)
                }
            }, CANVAS_BIND_RETRY_MS)
            return
        }
        screenshotExecutor.execute {
            val matched = runCatching {
                privileged.matchesKnownSplashProfile(config.profileId)
            }.getOrDefault(false)
            if (matched) handler.post {
                if (activePackage == packageName && !canvasHandledForEntry) tapCanvasSkip(packageName, config)
            }
        }
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
        private const val CANVAS_BIND_RETRY_MS = 350L
        private val SENSITIVE_HINTS = listOf(
            "bank", "mbank", "wallet", "alipay", "unionpay", "payment", "finance",
            "securities", "broker", "authenticator", "password", "keychain"
        )
        private val SENSITIVE_LABELS = listOf("银行", "支付", "证券", "钱包", "金融", "保险")
        private val SKIP_PATTERN = Regex("^跳过(?:广告)?(?:\\s*[0-9]+\\s*(?:秒|s)?)?.*$", RegexOption.IGNORE_CASE)
        private val STRICT_SKIP_PATTERN = Regex("^跳过(?:广告)?(?:\\s*[0-9]+\\s*(?:秒|s)?)?$", RegexOption.IGNORE_CASE)
    }
}
