package dev.hyperadguard.work

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
import dev.hyperadguard.MainActivity
import dev.hyperadguard.R
import java.util.concurrent.TimeUnit

object RecheckScheduler {
    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<RecheckWorker>(7, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "hyperos_ad_recheck", ExistingPeriodicWorkPolicy.KEEP, work
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
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel("recheck", "定期体检", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            applicationContext, 10, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            1002,
            NotificationCompat.Builder(applicationContext, "recheck")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("该复查广告设置了")
                .setContentText("系统或应用升级可能重新打开推荐项，点此快速检查。")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        return Result.success()
    }
}
