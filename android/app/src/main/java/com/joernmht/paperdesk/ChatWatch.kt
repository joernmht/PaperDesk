package com.joernmht.paperdesk

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Which chat is open / should open (notification tap). [visible] = the chat on screen right now, if any. */
object ChatNav {
    val open = mutableStateOf<String?>(null)
    @Volatile var visible: String? = null
    const val EXTRA_THREAD = "thread"
}

/**
 * Agent runs continue on the box when the phone drops the connection (agent 0.5). After Joern sends a message,
 * this worker checks the chat about once a minute (for up to 3 h) and, when the run has finished and the chat is
 * not on screen, posts "Chat answered" with the start of the answer. Tapping it opens that chat.
 */
class ChatWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val tid = inputData.getString("tid") ?: return Result.success()
        val t0 = inputData.getLong("t0", System.currentTimeMillis())
        val r = try { AgentApi(ctx).thread(tid) } catch (e: Exception) {
            if (System.currentTimeMillis() - t0 < MAX_MS) schedule(ctx, tid, t0, 2)
            return Result.success()
        }
        if (r.optBoolean("running")) {
            if (System.currentTimeMillis() - t0 < MAX_MS) schedule(ctx, tid, t0, 1)
            return Result.success()
        }
        if (ChatNav.visible == tid) return Result.success()
        val turns = r.optJSONArray("turns")
        val last = turns?.optJSONObject(turns.length() - 1)
        val pending = (r.optJSONArray("pending_approvals")?.length() ?: 0) > 0
        val answer = last?.takeIf { !it.isNull("answer") }?.optString("answer").orEmpty()
        val body = when {
            pending -> "Waiting for your approval"
            answer.isNotBlank() -> answer.take(400)
            else -> "Finished (no answer text)"
        }
        notify(ctx, tid, r.optString("title").ifBlank { "Chat" }, body)
        return Result.success()
    }

    companion object {
        private const val MAX_MS = 3 * 3600_000L
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Start watching [tid] (call when a run starts). */
        fun watch(ctx: Context, tid: String) = schedule(ctx, tid, System.currentTimeMillis(), 1, ExistingWorkPolicy.REPLACE)

        private fun schedule(ctx: Context, tid: String, t0: Long, minutes: Long,
                             policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
            val req = OneTimeWorkRequestBuilder<ChatWatchWorker>()
                .setInitialDelay(minutes, TimeUnit.MINUTES).setConstraints(net)
                .setInputData(workDataOf("tid" to tid, "t0" to t0)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("chat-$tid", policy, req)
        }

        private fun notify(ctx: Context, tid: String, title: String, body: String) {
            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            PollWorker.ensureChannel(ctx)
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra(PollWorker.EXTRA_TAB, "chat").putExtra(ChatNav.EXTRA_THREAD, tid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, tid.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, PollWorker.CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Chat answered: $title")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(tid.hashCode(), n) } catch (_: SecurityException) {}
        }
    }
}
