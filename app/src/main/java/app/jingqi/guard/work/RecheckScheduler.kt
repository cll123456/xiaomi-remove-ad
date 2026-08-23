package app.jingqi.guard.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.jingqi.guard.MainActivity
import app.jingqi.guard.R
import java.util.concurrent.TimeUnit

object RecheckScheduler {
    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<RecheckWorker>(7, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "jingqi_hyperos_ad_recheck", ExistingPeriodicWorkPolicy.KEEP, work
        )
    }
}

class RecheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("jingqi_recheck", "净启定期体检", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            applicationContext, 10, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            1002,
            NotificationCompat.Builder(applicationContext, "jingqi_recheck")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("净启提醒你复查广告设置")
                .setContentText("系统或应用更新可能恢复推荐项，点击净启检查。")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        return Result.success()
    }
}
