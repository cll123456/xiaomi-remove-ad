package app.jingqi.guard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.jingqi.guard.rules.AppRule
import app.jingqi.guard.rules.BuiltInRuleCatalog
import app.jingqi.guard.rules.NodePolicy
import app.jingqi.guard.rules.VisualSplashRule
import app.jingqi.guard.system.adb.AdbPairingService
import app.jingqi.guard.system.adb.EmbeddedAdbRuntime
import app.jingqi.guard.system.adb.PairingStatus
import app.jingqi.guard.system.adb.WirelessPairingText
import app.jingqi.guard.system.adb.WirelessPairingTextDetector
import java.util.ArrayDeque
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors

class SplashSkipAccessibilityService : AccessibilityService() {
    private var activePackage = ""
    private var activeWindowClass = ""
    private var activeMode = Mode.BLOCKED
    private var activeRule: AppRule? = null
    private var entryGeneration = 0L
    private var packageEnteredAt = 0L
    private var lastAttemptAt = 0L
    private var splashHandledForEntry = false
    private var lastSubmittedPairingCode = ""
    private var lastPairingSubmissionAt = 0L
    private var inputMethodPackage = ""
    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var inspectionErrors = 0L
    private val foregroundWatchdog = object : Runnable {
        override fun run() {
            // A stale window must not permanently stop the watchdog. No UI data is logged.
            runCatching { inspectForegroundRoot() }.onFailure { inspectionErrors++ }
            SplashRuntime.heartbeat()
            handler.postDelayed(this, FOREGROUND_WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SplashRuntime.connected(this)
        inputMethodPackage = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty().substringBefore('/')
        handler.removeCallbacks(foregroundWatchdog)
        handler.post(foregroundWatchdog)
        getSharedPreferences("splash_skip", MODE_PRIVATE).edit()
            .remove("debug_events")
            .remove("debug_event_package")
            .remove("debug_canvas")
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handleAccessibilityEvent(event) }.onFailure { inspectionErrors++ }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!canInspectScreen()) return
        val root = currentApplicationRoot()
        val eventPackage = event.packageName?.toString()?.takeIf(String::isNotBlank)
        val rootPackage = root?.packageName?.toString()?.takeIf(String::isNotBlank)
        val now = SystemClock.elapsedRealtime()
        val pairingPackage = when {
            eventPackage == ANDROID_SETTINGS_PACKAGE -> eventPackage
            rootPackage == ANDROID_SETTINGS_PACKAGE -> rootPackage
            else -> eventPackage ?: rootPackage
        }
        if (pairingPackage != null && maybeSubmitWirelessPairingCode(pairingPackage, root, now)) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Only a real window-state transition may replace the foreground
            // package. HyperOS interleaves SystemUI/IME content events with app
            // launches; treating those as app switches cancels splash retries.
            val packageName = eventPackage ?: rootPackage ?: return
            if (isTransientWindowPackage(packageName)) return
            val eventWindowClass = event.className?.toString().orEmpty()
            val samePackageRestart = packageName == activePackage &&
                eventWindowClass.isNotBlank() &&
                eventWindowClass != activeWindowClass &&
                looksLikeStartupActivity(eventWindowClass) &&
                now - packageEnteredAt >= SAME_PACKAGE_REENTRY_MS
            if (packageName != activePackage || samePackageRestart) {
                beginPackageEntry(packageName, eventWindowClass, now)
            } else if (eventWindowClass.isNotBlank()) {
                activeWindowClass = eventWindowClass
            }
        } else {
            // Content changes can drive another node scan, but may not change
            // application identity. Require either the event or current root to
            // still belong to the state-confirmed foreground package.
            if (activePackage.isBlank() ||
                (eventPackage != activePackage && rootPackage != activePackage)
            ) return
        }

        val packageName = activePackage
        if (!isCurrentEntry(packageName, entryGeneration) ||
            splashHandledForEntry || activeMode == Mode.BLOCKED || now - lastAttemptAt < 250L
        ) return
        root ?: return
        attemptNodeSkip(packageName, entryGeneration, activeMode, activeRule, root, now)
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
        SplashRuntime.disconnected()
        handler.removeCallbacksAndMessages(null)
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SplashRuntime.disconnected()
        activePackage = ""
        entryGeneration++
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        val state = SplashRuntime.state.value
        writer.println("jingqiSplash version=${app.jingqi.guard.BuildConfig.VERSION_NAME}")
        writer.println("connected=${state.connected} heartbeatAgeMs=${SystemClock.elapsedRealtime() - state.heartbeatAt}")
        writer.println("submittedActions=${state.submittedActions} lastActionAt=${state.lastActionAt}")
        // Only catalogued test targets, never window text or arbitrary application history.
        writer.println("lastActionPackage=${state.lastActionPackage.takeIf { BuiltInRuleCatalog.find(it) != null } ?: "other"}")
        writer.println("activeRule=${activeRule?.id ?: "general-or-excluded"} handled=$splashHandledForEntry inspectionErrors=$inspectionErrors")
    }

    /**
     * Some HyperOS splash activities expose a root but emit no accessibility
     * event until the advertisement has already ended. Poll root ownership and
     * scan nodes only inside the same bounded startup window, while unlocked.
     */
    private fun inspectForegroundRoot() {
        if (!canInspectScreen()) {
            activePackage = ""
            entryGeneration++
            return
        }
        val root = currentApplicationRoot() ?: return
        val packageName = root.packageName?.toString()?.takeIf(String::isNotBlank) ?: return
        if (isTransientWindowPackage(packageName)) return
        val now = SystemClock.elapsedRealtime()
        if (packageName != activePackage) {
            beginPackageEntry(packageName, "", now)
        }
        if (!isCurrentEntry(packageName, entryGeneration) ||
            splashHandledForEntry || activeMode == Mode.BLOCKED || now - lastAttemptAt < 250L
        ) return
        attemptNodeSkip(packageName, entryGeneration, activeMode, activeRule, root, now)
    }

    private fun beginPackageEntry(packageName: String, windowClass: String, now: Long) {
        activePackage = packageName
        activeWindowClass = windowClass
        packageEnteredAt = now
        lastAttemptAt = 0L
        splashHandledForEntry = false
        entryGeneration++
        activeRule = BuiltInRuleCatalog.find(packageName)
        activeMode = modeFor(packageName, activeRule)
        if (activeMode == Mode.BLOCKED) return
        scheduleNodeSplashChecks(packageName, entryGeneration, activeMode, activeRule)
        activeRule?.visualSplash?.let { config ->
            scheduleCanvasSplashChecks(packageName, entryGeneration, config)
        }
    }

    private fun scheduleNodeSplashChecks(
        packageName: String,
        generation: Long,
        mode: Mode,
        rule: AppRule?
    ) {
        NODE_RETRY_DELAYS_MS.forEach { delayMillis ->
            handler.postDelayed({
                if (!isCurrentEntry(packageName, generation) || splashHandledForEntry) return@postDelayed
                val root = rootForPackage(packageName) ?: return@postDelayed
                attemptNodeSkip(
                    packageName = packageName,
                    generation = generation,
                    mode = mode,
                    rule = rule,
                    root = root,
                    now = SystemClock.elapsedRealtime()
                )
            }, delayMillis)
        }
    }

    private fun scheduleCanvasSplashChecks(
        packageName: String,
        generation: Long,
        config: VisualSplashRule
    ) {
        repeat(config.maxAttempts) { attempt ->
            val delayMillis = config.startupDelayMillis + attempt * config.retryIntervalMillis
            handler.postDelayed({
                if (!isVisualEntry(packageName, generation, config) || splashHandledForEntry ||
                    !config.supportsActivity(activeWindowClass)
                ) return@postDelayed
                inspectCanvasSplash(packageName, generation, config)
            }, delayMillis)
        }
    }

    private fun isCurrentEntry(packageName: String, generation: Long): Boolean =
        SplashRuntime.state.value.connected && activePackage == packageName &&
            entryGeneration == generation &&
            SystemClock.elapsedRealtime() - packageEnteredAt <= STARTUP_WINDOW_MS

    private fun isVisualEntry(
        packageName: String,
        generation: Long,
        config: VisualSplashRule
    ): Boolean = isCurrentEntry(packageName, generation) &&
        SystemClock.elapsedRealtime() - packageEnteredAt <= config.activeWindowMillis

    private fun looksLikeStartupActivity(className: String): Boolean {
        val normalized = className.lowercase()
        return normalized.contains("splash") ||
            normalized.contains("welcome") ||
            normalized.contains("startup") ||
            normalized.endsWith(".launchactivity")
    }

    private fun isTransientWindowPackage(packageName: String): Boolean =
        packageName == SYSTEM_UI_PACKAGE || packageName == inputMethodPackage

    private fun currentApplicationRoot(): AccessibilityNodeInfo? {
        if (!canInspectScreen()) return null
        val primary = rootInActiveWindow
        val primaryPackage = primary?.packageName?.toString().orEmpty()
        // Never click an underlying application through the notification shade or keyboard.
        if (isTransientWindowPackage(primaryPackage)) return null
        if (primary != null && primaryPackage.isNotBlank() &&
            !isTransientWindowPackage(primaryPackage)
        ) return primary

        return applicationWindows()
            .filter { it.isActive && it.isFocused }
            .sortedByDescending { it.layer }
            .asSequence()
            .mapNotNull { it.root }
            .firstOrNull { root ->
                val packageName = root.packageName?.toString().orEmpty()
                packageName.isNotBlank() && !isTransientWindowPackage(packageName)
            }
    }

    private fun rootForPackage(packageName: String): AccessibilityNodeInfo? {
        return currentApplicationRoot()?.takeIf { it.packageName?.toString() == packageName }
    }

    private fun canInspectScreen(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun applicationWindows(): List<AccessibilityWindowInfo> =
        runCatching { windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } }
            .getOrDefault(emptyList())

    /**
     * Ctrip, iQIYI and Xiaomi Music can draw the close control into a video/canvas layer. Android's
     * accessibility screenshot API evaluates a fixed region here in memory; the
     * image is never written, returned to the app UI, or uploaded.
     */
    private fun inspectCanvasSplash(
        packageName: String,
        generation: Long,
        config: VisualSplashRule
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (rootForPackage(packageName) == null) return
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
                    val screenshotWidth = bitmap.width
                    val screenshotHeight = bitmap.height
                    val matched = try {
                        SplashImageMatcher.matches(config.profileId, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                    if (matched) handler.post {
                        if (isVisualEntry(packageName, generation, config) &&
                            !splashHandledForEntry && config.supportsActivity(activeWindowClass) &&
                            rootForPackage(packageName) != null
                        ) {
                            tapCanvasSkip(
                                packageName = packageName,
                                generation = generation,
                                config = config,
                                screenshotWidth = screenshotWidth,
                                screenshotHeight = screenshotHeight
                            )
                        }
                    }
                }

                override fun onFailure(errorCode: Int) = Unit
            }
        )
    }

    private fun tapCanvasSkip(
        packageName: String,
        generation: Long,
        config: VisualSplashRule,
        screenshotWidth: Int,
        screenshotHeight: Int
    ) {
        splashHandledForEntry = true
        // The screenshot covers the exact display coordinate space consumed by
        // dispatchGesture. App displayMetrics can exclude HyperOS system-bar
        // insets and therefore must not be mixed with screenshot ratios.
        val path = Path().apply {
            moveTo(screenshotWidth * config.tapX, screenshotHeight * config.tapY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isCurrentEntry(packageName, generation)) recordSkip(packageName)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (isCurrentEntry(packageName, generation)) splashHandledForEntry = false
            }
        }, null)
        if (!accepted) {
            splashHandledForEntry = false
        }
    }

    private fun modeFor(packageName: String, builtInRule: AppRule? = BuiltInRuleCatalog.find(packageName)): Mode {
        if (packageName == applicationContext.packageName) return Mode.BLOCKED
        builtInRule?.let { rule ->
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

    private fun attemptNodeSkip(
        packageName: String,
        generation: Long,
        mode: Mode,
        rule: AppRule?,
        root: AccessibilityNodeInfo,
        now: Long
    ) {
        if (!isCurrentEntry(packageName, generation) || splashHandledForEntry ||
            root.packageName?.toString() != packageName || now - lastAttemptAt < 250L
        ) return
        val skip = findSkipNode(root, mode, rule) ?: return
        lastAttemptAt = now
        if (clickNodeOrParent(skip)) {
            splashHandledForEntry = true
            recordSkip(packageName)
        } else if (mode == Mode.VERIFIED && tapNode(skip, packageName, generation)) {
            splashHandledForEntry = true
        }
    }

    private fun findSkipNode(
        root: AccessibilityNodeInfo,
        mode: Mode,
        rule: AppRule?
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName.orEmpty()
            val labels = listOf(node.text, node.contentDescription)
            val skipText = labels.any(SplashSkipTextMatcher::isGeneral)
            val strictSkipText = labels.any(SplashSkipTextMatcher::isStrict)
            val skipId = viewId.contains("skip", ignoreCase = true)
            val splashId = viewId.contains("splash", ignoreCase = true)
            val verifiedViewId = viewId in rule?.verifiedSkipViewIds.orEmpty()
            val matches = when (mode) {
                Mode.VERIFIED -> SplashSkipTextMatcher.matchesVerifiedNode(
                    skipText = skipText,
                    skipId = skipId,
                    verifiedViewId = verifiedViewId,
                    requiresVerifiedViewId = !rule?.verifiedSkipViewIds.isNullOrEmpty()
                )
                Mode.GENERAL -> skipText && (skipId || hasClickableParent(node))
                Mode.FINANCIAL_EXACT -> strictSkipText && (skipId || splashId) && hasClickableParent(node)
                Mode.BLOCKED -> false
            }
            if (matches && node.isVisibleToUser && node.isEnabled) return node
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

    private fun tapNode(
        node: AccessibilityNodeInfo,
        packageName: String,
        generation: Long
    ): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isCurrentEntry(packageName, generation)) recordSkip(packageName)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (isCurrentEntry(packageName, generation)) splashHandledForEntry = false
            }
        }, null)
    }

    private fun recordSkip(packageName: String) {
        SplashRuntime.recordAction(this, packageName)
    }

    private enum class Mode { VERIFIED, GENERAL, FINANCIAL_EXACT, BLOCKED }

    companion object {
        private const val STARTUP_WINDOW_MS = 15_000L
        private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val FOREGROUND_WATCHDOG_INTERVAL_MS = 400L
        private const val MAX_PAIRING_NODES = 240
        private const val PAIRING_DEDUP_MS = 15_000L
        private const val SAME_PACKAGE_REENTRY_MS = 1_000L
        private val NODE_RETRY_DELAYS_MS = longArrayOf(
            180L, 420L, 750L, 1_100L, 1_500L, 2_100L, 2_800L, 3_800L, 5_200L
        )
        private val SENSITIVE_HINTS = listOf(
            "bank", "mbank", "wallet", "alipay", "unionpay", "payment", "finance",
            "securities", "broker", "authenticator", "password", "keychain"
        )
        private val SENSITIVE_LABELS = listOf("银行", "支付", "证券", "钱包", "金融", "保险")
    }
}

private object SplashImageMatcher {
    fun matches(profileId: Int, bitmap: Bitmap): Boolean {
        val profile = SplashPixelProfileCatalog.find(profileId) ?: return false
        if (bitmap.width < 2 || bitmap.height < 2) return false
        val x0 = (bitmap.width * profile.left).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = (bitmap.height * profile.top).toInt().coerceIn(0, bitmap.height - 1)
        val x1 = (bitmap.width * profile.right).toInt().coerceIn(x0 + 1, bitmap.width)
        val y1 = (bitmap.height * profile.bottom).toInt().coerceIn(y0 + 1, bitmap.height)
        var total = 0
        var white = 0
        var dark = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val color = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(color)
                val green = android.graphics.Color.green(color)
                val blue = android.graphics.Color.blue(color)
                val maximum = maxOf(red, green, blue)
                val minimum = minOf(red, green, blue)
                val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
                total++
                if (minimum > 180 && maximum - minimum < 70) white++
                if (luminance < 105f) dark++
            }
        }
        return total > 0 && SplashPixelProfileCatalog.matches(
            profileId = profileId,
            whiteRatio = white.toFloat() / total,
            darkRatio = dark.toFloat() / total
        )
    }
}
