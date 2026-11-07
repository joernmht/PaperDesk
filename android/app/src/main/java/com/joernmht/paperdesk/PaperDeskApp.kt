package com.joernmht.paperdesk

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Crash reporter. Installed in attachBaseContext, which runs BEFORE any ContentProvider (WorkManager,
 * androidx.startup), so even a crash during start-up is caught. The trace is written to a file first,
 * then POSTed to /api/crash; if that fails it is re-sent on the next launch.
 */
class PaperDeskApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = crashFile(base)
                f.writeText(report(t, e))
                val th = Thread { if (send(base, f.readText())) f.delete() }
                th.start(); th.join(4000)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val f = crashFile(this)
        if (f.exists()) Thread { if (send(this, "(re-sent on next launch)\n" + f.readText())) f.delete() }.start()
    }

    companion object {
        fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.txt")

        fun report(t: Thread, e: Throwable): String =
            "app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), thread ${t.name}\n" +
                e.stackTraceToString()

        fun send(ctx: Context, trace: String): Boolean = try {
            val c = URL(Prefs.url(ctx) + "/api/crash").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 3000; c.readTimeout = 3000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + Prefs.token(ctx))
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(JSONObject().put("trace", trace).toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            c.disconnect(); ok
        } catch (_: Throwable) {
            false
        }
    }
}
