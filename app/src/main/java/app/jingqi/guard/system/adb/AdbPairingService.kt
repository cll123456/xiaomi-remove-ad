package app.jingqi.guard.system.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import app.jingqi.guard.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the pairing-code window alive while the user enters its six-digit code
 * through a notification RemoteInput. The code is never persisted or logged.
 */
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

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
            ACTION_SUBMIT_CODE -> {
                startForeground(NOTIFICATION_ID, waitingNotification())
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_PAIRING_CODE)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (!code.matches(Regex("^[0-9]{6}$"))) {
                    EmbeddedAdbRuntime.reportFailure(IllegalArgumentException("配对码必须是 6 位数字"))
                    finishNotification(false, "配对码格式不正确，请重新开始配对")
                } else {
                    pair(code.toCharArray())
                }
            }
            else -> {
                EmbeddedAdbRuntime.markWaitingForCode()
                startForeground(NOTIFICATION_ID, waitingNotification())
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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

    private fun waitingNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("净启：输入无线调试配对码")
        .setContentText("保持系统配对码窗口打开，下拉通知栏并点击“输入配对码”")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                "在开发者选项进入“无线调试”→“使用配对码配对设备”，保持六位码窗口打开，然后下拉通知栏从这里输入。"
            )
        )
        .setContentIntent(developerSettingsIntent())
        .addAction(pairingCodeAction())
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

    private fun pairingCodeAction(): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel("输入 6 位配对码")
            .build()
        val submit = PendingIntent.getService(
            this,
            REQUEST_SUBMIT_CODE,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_SUBMIT_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_shield,
            "输入配对码",
            submit
        ).addRemoteInput(remoteInput).build()
    }

    private fun developerSettingsIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_DEVELOPER_SETTINGS,
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        packageManager.getLaunchIntentForPackage(packageName),
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
        private const val REQUEST_SUBMIT_CODE = 17311
        private const val REQUEST_DEVELOPER_SETTINGS = 17312
        private const val REQUEST_OPEN_APP = 17313
        private const val ACTION_START = "app.jingqi.guard.action.START_ADB_PAIRING"
        private const val ACTION_SUBMIT_CODE = "app.jingqi.guard.action.SUBMIT_ADB_PAIRING_CODE"
        private const val KEY_PAIRING_CODE = "jingqi_pairing_code"

        fun start(context: Context) {
            EmbeddedAdbRuntime.initialize(context)
            EmbeddedAdbRuntime.markWaitingForCode()
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
            )
        }

        fun isNotificationChannelBlocked(context: Context): Boolean {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            return channel?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }
}
