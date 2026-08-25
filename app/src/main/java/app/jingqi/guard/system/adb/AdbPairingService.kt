package app.jingqi.guard.system.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.jingqi.guard.MainActivity
import app.jingqi.guard.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps pairing alive while the user leaves Android's six-digit pairing dialog foreground.
 * The narrowly scoped accessibility helper hands the code over in process; it is never
 * persisted, placed in Intent extras, or logged.
 */
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val pairingInFlight = AtomicBoolean(false)
    private var waitingTimeout: Job? = null

    override fun onCreate() {
        super.onCreate()
        EmbeddedAdbRuntime.initialize(this)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "净启无线调试配对", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "仅在用户主动进行本机无线调试配对时显示"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                startForeground(NOTIFICATION_ID, waitingNotification())
                clearPendingCode()
                EmbeddedAdbRuntime.cancelPairing()
                finishNotification(false, "本次无线调试配对已取消")
            }
            ACTION_SUBMIT_CODE -> {
                startForeground(NOTIFICATION_ID, waitingNotification())
                waitingTimeout?.cancel()
                val codeChars = pendingCode.getAndSet(null)
                when {
                    codeChars == null -> {
                        EmbeddedAdbRuntime.markWaitingForCode()
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            waitingNotification("未读取到有效配对码，请保持系统配对窗口打开")
                        )
                        scheduleWaitingTimeout()
                    }
                    !pairingInFlight.compareAndSet(false, true) -> codeChars.fill('\u0000')
                    else -> pair(codeChars)
                }
            }
            else -> {
                EmbeddedAdbRuntime.markWaitingForCode()
                startForeground(NOTIFICATION_ID, waitingNotification())
                scheduleWaitingTimeout()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        waitingTimeout?.cancel()
        clearPendingCode()
        scope.cancel()
        super.onDestroy()
    }

    private fun pair(codeChars: CharArray) {
        notificationManager.notify(
            NOTIFICATION_ID,
            progressNotification("正在确认本机端口并进行加密配对…")
        )
        scope.launch {
            val result = runCatching {
                EmbeddedAdbRuntime.pairWithThisDevice(String(codeChars))
            }
            codeChars.fill('\u0000')
            pairingInFlight.set(false)
            withContext(Dispatchers.Main) {
                result.onSuccess { connected ->
                    finishNotification(
                        connected,
                        if (connected) "配对完成，净启专家权限已经可用"
                        else "配对成功，但无线调试连接尚未就绪"
                    )
                }.onFailure { error ->
                    EmbeddedAdbRuntime.reportFailure(error)
                    finishNotification(false, error.toPairingMessage())
                }
            }
        }
    }

    private fun scheduleWaitingTimeout() {
        waitingTimeout?.cancel()
        waitingTimeout = scope.launch {
            delay(WAITING_TIMEOUT_MILLIS)
            withContext(Dispatchers.Main) {
                if (!pairingInFlight.get()) {
                    EmbeddedAdbRuntime.cancelPairing("等待配对码已超时，请重新开始配对")
                    finishNotification(false, "等待配对码已超时，请重新开始")
                }
            }
        }
    }

    private fun waitingNotification(detail: String = "请保持系统六位配对码窗口打开，净启会在本机自动确认") =
        NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("净启：等待无线调试配对码")
        .setContentText(detail)
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                "在开发者选项进入“无线调试”→“使用配对码配对设备”，并保持该窗口在前台。已授权的开屏守护只在本次配对期间从系统设置读取六位码并立即本机提交。"
            )
        )
        .setContentIntent(openAppIntent())
        .addAction(cancelAction())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun progressNotification(detail: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("净启正在安全配对")
        .setContentText(detail)
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()

    private fun finishNotification(success: Boolean, detail: String) {
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(if (success) "净启配对完成" else "净启配对未完成")
                .setContentText(detail)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun cancelAction() = NotificationCompat.Action.Builder(
        R.drawable.ic_shield,
        "取消",
        PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    ).build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        packageManager.getLaunchIntentForPackage(packageName)?.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ) ?: Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun Throwable.toPairingMessage(): String = when {
        message.orEmpty().contains("未发现本机无线调试服务") ->
            "没有找到配对窗口，请保持六位配对码窗口打开后重试"
        message.orEmpty().contains("Exchanging") || message.orEmpty().contains("Peer") ->
            "配对码不正确或已经过期，请生成新配对码后重试"
        else -> message ?: "本机无线调试配对失败"
    }

    companion object {
        private const val CHANNEL_ID = "jingqi_adb_pairing"
        private const val NOTIFICATION_ID = 17310
        private const val REQUEST_OPEN_APP = 17313
        private const val REQUEST_CANCEL = 17314
        private const val ACTION_START = "app.jingqi.guard.action.START_ADB_PAIRING"
        private const val ACTION_SUBMIT_CODE = "app.jingqi.guard.action.SUBMIT_ADB_PAIRING_CODE"
        private const val ACTION_CANCEL = "app.jingqi.guard.action.CANCEL_ADB_PAIRING"
        private const val WAITING_TIMEOUT_MILLIS = 5 * 60_000L
        private val pendingCode = AtomicReference<CharArray?>(null)

        fun start(context: Context) {
            EmbeddedAdbRuntime.initialize(context)
            EmbeddedAdbRuntime.markWaitingForCode()
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
            )
        }

        fun submitCode(context: Context, code: String): Boolean {
            val codeChars = PairingCode.toCharsOrNull(code) ?: return false
            pendingCode.getAndSet(codeChars)?.fill('\u0000')
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdbPairingService::class.java).setAction(ACTION_SUBMIT_CODE)
            )
            return true
        }

        fun cancel(context: Context) {
            EmbeddedAdbRuntime.initialize(context)
            clearPendingCode()
            EmbeddedAdbRuntime.cancelPairing()
            context.stopService(Intent(context, AdbPairingService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        private fun clearPendingCode() {
            pendingCode.getAndSet(null)?.fill('\u0000')
        }

        fun isNotificationChannelBlocked(context: Context): Boolean {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            return channel?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }
}
