package com.joernmht.paperdesk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Polls /api/events?since=<last seq> and posts one notification per new event.
 * No Firebase: a 15-min periodic poll, plus a short burst of one-off polls after the user starts a job.
 * The very first poll only records `last`, so installing the app does not replay history.
 */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val since = Prefs.lastSeq(ctx)
        val (events, last) = try {
            Api(ctx).events(maxOf(since, 0))
        } catch (e: Exception) {
            return Result.success()   // offline / tailnet down: try again next period
        }
        if (since < 0) {
            Prefs.setLastSeq(ctx, last)
            return Result.success()
        }
        events.filter { it.seq > since }.forEach { notify(ctx, it) }
        Prefs.setLastSeq(ctx, maxOf(last, since))
        return Result.success()
    }

    companion object {
        const val CHANNEL = "updates"
        const val EXTRA_TAB = "tab"
        private const val PERIODIC = "paperdesk-poll"
        private const val BURST = "paperdesk-burst"
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New papers, finished jobs, reMarkable activity"
                }
            )
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** After a job was started: poll at ~1, 3, 6 and 10 minutes so the result arrives quickly. */
        fun burst(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val delays = listOf(1L, 2L, 3L, 4L)   // cumulative 1, 3, 6, 10 min
            var chain = wm.beginUniqueWork(
                BURST, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PollWorker>().setInitialDelay(delays[0], TimeUnit.MINUTES).setConstraints(net).build()
            )
            delays.drop(1).forEach { d ->
                chain = chain.then(
                    OneTimeWorkRequestBuilder<PollWorker>().setInitialDelay(d, TimeUnit.MINUTES).setConstraints(net).build()
                )
            }
            chain.enqueue()
        }

        fun notify(ctx: Context, e: Event) {
            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            ensureChannel(ctx)
            val tab = if (e.kind == "scout") "inbox" else "activity"
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                ctx, e.seq, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(e.title.ifBlank { "PaperDesk" })
                .setContentText(e.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(e.body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("paperdesk")
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(e.seq, n)
            } catch (_: SecurityException) {
            }
        }
    }
}
