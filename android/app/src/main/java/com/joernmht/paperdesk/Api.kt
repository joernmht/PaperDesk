package com.joernmht.paperdesk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(msg: String) : Exception(msg)

/** Settings: base URL + token, defaults baked in at build time (BuildConfig), overridable in-app. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("paperdesk", Context.MODE_PRIVATE)
    fun url(ctx: Context): String = (sp(ctx).getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_URL).trimEnd('/')
    fun token(ctx: Context): String = sp(ctx).getString("token", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_TOKEN
    fun save(ctx: Context, url: String, token: String) = sp(ctx).edit().putString("url", url.trim()).putString("token", token.trim()).apply()
    fun lastSeq(ctx: Context): Int = sp(ctx).getInt("last_seq", -1)
    fun setLastSeq(ctx: Context, v: Int) = sp(ctx).edit().putInt("last_seq", v).apply()
}

class Api(private val ctx: Context) {
    private val base get() = Prefs.url(ctx)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(path: String, method: String = "GET", timeoutMs: Int = 30_000): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer " + Prefs.token(ctx))
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun request(path: String, body: JSONObject? = null, timeoutMs: Int = 30_000): JSONObject {
        val c = open(path, if (body == null) "GET" else "POST", timeoutMs)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val err = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ApiException("HTTP $code" + (err?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private suspend fun get(path: String, timeoutMs: Int = 30_000) = withContext(Dispatchers.IO) { request(path, null, timeoutMs) }
    private suspend fun post(path: String, body: JSONObject) = withContext(Dispatchers.IO) { request(path, body) }

    suspend fun health() = get("/api/health")
    suspend fun inbox(all: Boolean) = get("/api/inbox?status=" + if (all) "all" else "open")
        .optJSONArray("items").mapObjects(Paper::from)

    /** Returns (updated paper, job or null). */
    suspend fun action(p: Paper, action: String, target: String? = null, toread: Boolean = false): Pair<Paper?, Job?> {
        val b = JSONObject().put("id", p.id).put("action", action).put("paper", p.raw)
        if (target != null) b.put("target", target)
        if (toread) b.put("toread", true)
        val r = post("/api/action", b)
        return r.optJSONObject("item")?.let(Paper::from) to r.optJSONObject("job")?.let(Job::from)
    }

    suspend fun search(q: String, scope: String): Pair<List<Paper>, List<Paper>> {
        val r = get("/api/search?q=${enc(q)}&scope=$scope", 90_000)
        return r.optJSONArray("library").mapObjects(Paper::from) to r.optJSONArray("web").mapObjects(Paper::from)
    }

    suspend fun jobs() = get("/api/jobs").optJSONArray("jobs").mapObjects(Job::from)
    suspend fun job(id: String) = Job.from(get("/api/jobs/${enc(id)}"))
    suspend fun events(since: Int): Pair<List<Event>, Int> {
        val r = get("/api/events?since=$since")
        return r.optJSONArray("events").mapObjects(Event::from) to r.optInt("last", since)
    }

    suspend fun rmLs(path: String) = parseRmLs(get("/api/rm/ls?path=${enc(path)}", 60_000))
    suspend fun rmDrafts() = parseDrafts(get("/api/rm/drafts"))
    suspend fun rmAnnotated() = parseAnnotated(get("/api/rm/annotated"))
    suspend fun rmAction(op: String, pid: String? = null, hash: String? = null, path: String? = null): Job? {
        val b = JSONObject().put("op", op)
        pid?.let { b.put("pid", it) }; hash?.let { b.put("hash", it) }; path?.let { b.put("path", it) }
        return post("/api/rm/action", b).optJSONObject("job")?.let(Job::from)
    }

    /** Downloads /files/annotated/<name> into cache/pdf/ and returns the file. */
    suspend fun downloadAnnotated(name: String): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val out = File(dir, name.replace('/', '_'))
        val c = open("/files/annotated/" + enc(name).replace("+", "%20"), timeoutMs = 120_000)
        c.setRequestProperty("Accept", "application/pdf")
        try {
            if (c.responseCode !in 200..299) throw ApiException("HTTP ${c.responseCode}")
            c.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        out
    }

    fun apkUrl() = "$base/download/paperdesk.apk"
}
