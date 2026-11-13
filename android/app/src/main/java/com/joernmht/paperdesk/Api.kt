package com.joernmht.paperdesk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** [code] = HTTP status (0 = no answer), [body] = the server's JSON error body, if any. */
class ApiException(msg: String, val code: Int = 0, val body: JSONObject? = null) : Exception(msg)

/** Settings: base URL + token. Set by a pairing link (paperdesk://pair?…) or by hand; a private build may bake defaults in. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("paperdesk", Context.MODE_PRIVATE)
    fun url(ctx: Context): String = (sp(ctx).getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_URL).trimEnd('/')
    fun token(ctx: Context): String = sp(ctx).getString("token", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_TOKEN
    fun save(ctx: Context, url: String, token: String) = sp(ctx).edit().putString("url", url.trim()).putString("token", token.trim()).apply()
    /** Without a server URL and a token the app shows the setup screen and stays silent (no polling, no crash reports). */
    fun configured(ctx: Context): Boolean = url(ctx).isNotBlank() && token(ctx).isNotBlank()
    fun name(ctx: Context): String = sp(ctx).getString("name", null).orEmpty()
    /** Chat agent: explicit URL (pairing link / build default); else, on the classic port layout, 8447 → 8448. "" = none. */
    fun agentUrl(ctx: Context): String = (sp(ctx).getString("agent_url", null)?.takeIf { it.isNotBlank() }
        ?: BuildConfig.DEFAULT_AGENT_URL.takeIf { it.isNotBlank() }
        ?: url(ctx).takeIf { ":8447" in it }?.replace(":8447", ":8448")
        ?: "").trimEnd('/')
    fun lastSeq(ctx: Context): Int = sp(ctx).getInt("last_seq", -1)
    fun setLastSeq(ctx: Context, v: Int) = sp(ctx).edit().putInt("last_seq", v).apply()
    fun setAgentUrl(ctx: Context, v: String) = sp(ctx).edit().putString("agent_url", v.trimEnd('/')).apply()
    /** Editor font size in sp, persisted. */
    fun editorFont(ctx: Context): Int = sp(ctx).getInt("editor_font", 15)
    fun setEditorFont(ctx: Context, v: Int) = sp(ctx).edit().putInt("editor_font", v.coerceIn(10, 28)).apply()
    /** Editor view: simple (Markdown-like, LaTeX structure hidden) or the plain LaTeX. Simple by default. */
    fun editorSimple(ctx: Context): Boolean = sp(ctx).getBoolean("editor_simple", true)
    fun setEditorSimple(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("editor_simple", v).apply()
    /** Last /api/health answer, so the tabs match the server's features before the first request returns. */
    fun serverInfo(ctx: Context): ServerInfo =
        sp(ctx).getString("server_info", null)?.let { runCatching { ServerInfo.from(JSONObject(it)) }.getOrNull() } ?: ServerInfo.LEGACY
    fun setServerInfo(ctx: Context, health: JSONObject) = sp(ctx).edit().putString("server_info", health.toString()).apply()

    /** Connect to a (new) server: everything tied to the old one is forgotten. */
    fun pair(ctx: Context, p: Pairing) = sp(ctx).edit()
        .putString("url", p.url).putString("token", p.token)
        .putString("agent_url", p.agent.orEmpty()).putString("name", p.name.orEmpty())
        .remove("server_info").remove("last_seq").apply()

    fun disconnect(ctx: Context) = sp(ctx).edit().remove("url").remove("token").remove("agent_url").remove("name")
        .remove("server_info").remove("last_seq").remove("setup_step").remove("setup_solo").apply()

    private val CONN = listOf("url", "token", "agent_url", "name", "server_info")
    /** The current connection, to put back when a new pairing turns out not to work. */
    fun snapshot(ctx: Context): Map<String, String?> = CONN.associateWith { sp(ctx).getString(it, null) }
    fun restore(ctx: Context, snap: Map<String, String?>) = sp(ctx).edit().apply {
        snap.forEach { (k, v) -> if (v == null) remove(k) else putString(k, v) }
    }.apply()

    /**
     * Setup wizard position (ui/Setup.kt), -1 = not in the wizard. Unpaired apps are always in it (steps 0–4);
     * paired ones only while they finish it (5–7) or after "Run setup again" (4). A build with baked-in
     * defaults is paired from the start and never sees it.
     */
    fun setupStep(ctx: Context): Int {
        val v = sp(ctx).getInt("setup_step", -1)
        return if (!configured(ctx)) v.coerceIn(0, 4) else if (v >= 4) v else -1
    }
    fun setSetupStep(ctx: Context, step: Int, solo: Boolean = false) =
        sp(ctx).edit().putInt("setup_step", step).putBoolean("setup_solo", solo && step >= 0).apply()
    /** True when one wizard step was opened on its own from Settings (reMarkable: connect). */
    fun setupSolo(ctx: Context): Boolean = sp(ctx).getBoolean("setup_solo", false)
}

/** A pairing link: paperdesk://pair?url=<server base URL>&token=<bearer token>[&agent=<agent base URL>][&name=<desk name>] */
data class Pairing(val url: String, val token: String, val agent: String?, val name: String?) {
    val host: String get() = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url
    val display: String get() = name?.takeIf { it.isNotBlank() } ?: host

    companion object {
        fun parse(link: String?): Pairing? {
            val u = runCatching { android.net.Uri.parse(link?.trim().orEmpty()) }.getOrNull() ?: return null
            if (u.scheme != "paperdesk" || u.host != "pair") return null
            fun q(k: String) = runCatching { u.getQueryParameter(k) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val url = q("url")?.trimEnd('/') ?: return null
            val token = q("token") ?: return null
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null
            val agent = q("agent")?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            return Pairing(url, token, agent, q("name")?.take(60))
        }
    }
}

class PairException(msg: String) : Exception(msg)

/** Turns what the installer shows (QR code, or machine address + pairing code) into a [Pairing]. */
object PairClient {
    /** XXXX-XXXX, typed in any case, with or without the dash. */
    fun normCode(s: String): String? {
        val c = s.uppercase().filter { it.isLetterOrDigit() }
        return if (c.length == 8) c.substring(0, 4) + "-" + c.substring(4) else null
    }

    /** `myvm.tail1234.ts.net`, `https://myvm…`, with or without a port (default 8447). https only. */
    fun origin(address: String): String? {
        var a = address.trim().trimEnd('/')
        if (a.startsWith("http://", ignoreCase = true)) return null
        if (a.startsWith("https://", ignoreCase = true)) a = a.substring(8)
        a = a.substringBefore('/')
        if (a.isBlank() || a.any { it.isWhitespace() } || !a.contains('.')) return null
        val port = a.substringAfterLast(':', "")
        return "https://" + if (port.isNotEmpty() && port.all(Char::isDigit)) a else "$a:8447"
    }

    /** QR text `https://<host>[:port]/pair/<CODE>` → (origin, code). */
    fun fromQr(text: String): Pair<String, String>? {
        val u = runCatching { android.net.Uri.parse(text.trim()) }.getOrNull() ?: return null
        val seg = u.pathSegments ?: return null
        if (u.scheme != "https" || u.encodedAuthority.isNullOrBlank() || seg.size != 2 || seg[0] != "pair") return null
        val code = normCode(seg[1]) ?: return null
        return "https://${u.encodedAuthority}" to code
    }

    /** GET <origin>/api/pair?code=… (no auth: the short-lived code is the secret). */
    suspend fun fetch(origin: String, code: String): Pairing = withContext(Dispatchers.IO) {
        val c = try {
            URL("$origin/api/pair?code=" + URLEncoder.encode(code, "UTF-8")).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw PairException("That machine address does not look right.")
        }
        try {
            c.connectTimeout = 12_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            when {
                status == 403 -> throw PairException("That code is wrong or has expired. Codes last 30 minutes: run `paperdesk pair` on the machine for a new one.")
                status !in 200..299 -> throw PairException("The machine answered, but not with a pairing (HTTP $status). Is PaperDesk installed and up to date there?")
            }
            val o = runCatching { JSONObject(text) }.getOrNull() ?: throw PairException("The machine sent an answer PaperDesk cannot read.")
            val token = o.optString("token").takeIf { it.isNotBlank() } ?: throw PairException("The machine sent no access token.")
            Pairing(o.optString("url").trimEnd('/').ifBlank { origin }, token,
                o.optString("agent").trimEnd('/').takeIf { it.startsWith("http") }, o.optString("name").takeIf { it.isNotBlank() })
        } catch (e: PairException) {
            throw e
        } catch (e: Exception) {
            throw PairException("Can't reach the machine. Is Tailscale on, on both devices?")
        } finally {
            c.disconnect()
        }
    }
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
                val o = runCatching { JSONObject(text) }.getOrNull()
                val err = o?.optString("error")
                throw ApiException("HTTP $code" + (err?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""), code, o)
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

    suspend fun decisions(): Pair<List<Decision>, String> {
        val r = get("/api/decisions")
        return r.optJSONArray("decisions").mapObjects(Decision::from) to decisionStats(r.optJSONObject("stats"))
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
    suspend fun downloadAnnotated(name: String): File =
        download("/files/annotated/" + enc(name).replace("+", "%20"), name.replace('/', '_'))

    private suspend fun download(path: String, fileName: String): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val out = File(dir, fileName)
        val c = open(path, timeoutMs = 120_000)
        c.setRequestProperty("Accept", "application/pdf")
        try {
            if (c.responseCode !in 200..299) throw ApiException("HTTP ${c.responseCode}", c.responseCode)
            c.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        out
    }

    // ---------------- papers
    private fun pp(id: String) = "/api/papers/" + enc(id)

    suspend fun papers() = get("/api/papers").optJSONArray("papers").mapObjects(PaperInfo::from)
    /** op = add (label, git_url, main) | remove (id). Returns (papers, job or null). */
    suspend fun paperOp(body: JSONObject): Pair<List<PaperInfo>, Job?> {
        val r = post("/api/papers", body)
        return r.optJSONArray("papers").mapObjects(PaperInfo::from) to r.optJSONObject("job")?.let(Job::from)
    }
    suspend fun paperFiles(id: String) = get(pp(id) + "/files").optJSONArray("files").mapObjects { PaperFile(it.optString("path"), it.optLong("size")) }
    suspend fun paperFile(id: String, path: String): FileDoc {
        val o = get(pp(id) + "/file?path=" + enc(path), 60_000)
        return FileDoc(o.optString("path", path), o.optString("text"), o.optString("sha"), o.optString("head"))
    }
    /** Commits (and pushes) [text] if the file on the server is still [baseSha]; else [ConflictException]. */
    suspend fun saveFile(id: String, path: String, text: String, baseSha: String, message: String? = null): SaveResult {
        val b = JSONObject().put("path", path).put("text", text).put("base_sha", baseSha)
        message?.let { b.put("message", it) }
        val r = try {
            withContext(Dispatchers.IO) { request(pp(id) + "/file", b, 120_000) }
        } catch (e: ApiException) {
            if (e.code == 409 && e.body != null) throw ConflictException(e.body.optString("text"), e.body.optString("sha"))
            throw e
        }
        return SaveResult(r.optString("sha"), r.optString("commit"), r.optBoolean("pushed"), r.optString("note").takeIf { it.isNotBlank() })
    }
    suspend fun paperComments(id: String) = get(pp(id) + "/comments").optJSONArray("comments").mapObjects(PaperComment::from)
    suspend fun paperBuild(id: String) = post(pp(id) + "/build", JSONObject()).optJSONObject("job")?.let(Job::from)
    suspend fun paperPdf(id: String): File = download(pp(id) + "/pdf", "paper_" + id.replace(Regex("[^\\w.-]"), "_") + ".pdf")
    suspend fun paperSync(id: String) = post(pp(id) + "/sync", JSONObject()).optJSONObject("head")

    // ---------------- keys + agent setup
    suspend fun secrets() = get("/api/secrets").optJSONArray("secrets").mapObjects(Secret::from)
    suspend fun setSecret(name: String, value: String) =
        post("/api/secrets", JSONObject().put("name", name).put("value", value)).optJSONArray("secrets").mapObjects(Secret::from)
    suspend fun agentSetup() = AgentSetup.from(get("/api/agent-setup"))
    suspend fun startAgentSetup(baseUrl: String, model: String) =
        post("/api/agent-setup", JSONObject().put("base_url", baseUrl).put("model", model)).optJSONObject("job")?.let(Job::from)

    /** One-time code from my.remarkable.com/device/desktop/connect → a job that registers the server with the tablet. */
    suspend fun rmConnect(code: String): Job? =
        post("/api/rm/connect", JSONObject().put("code", code)).optJSONObject("job")?.let(Job::from)

    suspend fun topics() = get("/api/topics").optJSONArray("topics").mapObjects(Topic::from)

    /** op = add (name + query) | remove (id) | run. Returns (topics, job or null). */
    suspend fun topicsOp(op: String, name: String? = null, query: String? = null, id: String? = null): Pair<List<Topic>, Job?> {
        val b = JSONObject().put("op", op)
        name?.let { b.put("name", it) }; query?.let { b.put("query", it) }; id?.let { b.put("id", it) }
        val r = post("/api/topics", b)
        return r.optJSONArray("topics").mapObjects(Topic::from) to r.optJSONObject("job")?.let(Job::from)
    }

    fun apkUrl() = "$base/download/paperdesk.apk"
}


/** Client for the DeepAgent chat service; NDJSON streaming for answers. */
class BusyException : Exception("this chat is still answering — wait a moment")

class AgentApi(private val ctx: Context) {
    private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(Prefs.agentUrl(ctx) + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer " + Prefs.token(ctx))
        return c
    }

    private fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val c = open(path, method, 30_000)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val err = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw ApiException("HTTP $code" + (err?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    suspend fun health(): Pair<List<String>, String> = withContext(Dispatchers.IO) {
        val r = call("/api/agent/health")
        val arr = r.optJSONArray("models")
        (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) } to r.optString("model")
    }

    suspend fun threads() = withContext(Dispatchers.IO) { call("/api/agent/threads").optJSONArray("threads").mapObjects(ChatThread::from) }

    suspend fun healthRaw() = withContext(Dispatchers.IO) { call("/api/agent/health") }

    suspend fun setApprovals(id: String, mode: String) = withContext(Dispatchers.IO) {
        ChatThread.from(call("/api/agent/threads/$id", "PATCH", JSONObject().put("approvals", mode)))
    }

    suspend fun detachRepo(id: String, name: String) = withContext(Dispatchers.IO) { call("/api/agent/threads/$id/repos/$name", "DELETE") }

    suspend fun setSubagents(id: String, on: Boolean) = withContext(Dispatchers.IO) {
        ChatThread.from(call("/api/agent/threads/$id", "PATCH", JSONObject().put("subagents", on)))
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) { call("/api/agent/threads/$id/cancel", "POST") }

    suspend fun newThread(model: String?, repos: List<Pair<String, String>> = emptyList(), approvals: String? = null,
                          subagents: Boolean = true) = withContext(Dispatchers.IO) {
        val b = JSONObject().apply {
            put("subagents", subagents)
            model?.let { put("model", it) }
            approvals?.let { put("approvals", it) }
            if (repos.isNotEmpty()) put("repos", org.json.JSONArray(repos.map { (r, m) -> JSONObject().put("repo", r).put("mode", m) }))
        }
        ChatThread.from(call("/api/agent/threads", "POST", b))
    }

    suspend fun repos() = withContext(Dispatchers.IO) {
        call("/api/agent/repos").optJSONArray("repos").mapObjects {
            RepoInfo(it.optString("repo"), it.optString("description"), it.optBoolean("private"), it.optBoolean("writable"), it.optBoolean("on_box"))
        }
    }

    suspend fun attachRepo(id: String, repo: String, mode: String) = withContext(Dispatchers.IO) {
        call("/api/agent/threads/$id/repos", "POST", JSONObject().put("repo", repo).put("mode", mode))
    }

    suspend fun deleteThread(id: String) = withContext(Dispatchers.IO) { call("/api/agent/threads/$id", "DELETE") }

    /** Raw thread: messages, turns [{user, steps, answer}], workspace, pending_approvals. */
    suspend fun thread(id: String): JSONObject = withContext(Dispatchers.IO) { call("/api/agent/threads/$id") }

    suspend fun chat(id: String, text: String, onEvent: (JSONObject) -> Unit) =
        stream("/api/agent/threads/$id/chat", JSONObject().put("text", text), onEvent)

    suspend fun resume(id: String, approve: Boolean, message: String?, onEvent: (JSONObject) -> Unit) =
        stream("/api/agent/threads/$id/resume",
            JSONObject().put("decision", if (approve) "approve" else "reject").apply { message?.takeIf { it.isNotBlank() }?.let { put("message", it) } },
            onEvent)

    /** Replay events with seq > [since] (null = the latest run from its start), then follow the live run to done/error. */
    suspend fun events(id: String, since: Int?, onEvent: (JSONObject) -> Unit) =
        stream("/api/agent/threads/$id/events" + (since?.let { "?since=$it" } ?: ""), null, onEvent)

    /** Streams NDJSON; [onEvent] runs on the main thread for every line. The server pings every 15 s, so a
     *  90 s silence means the connection is dead (the run itself keeps going on the box). */
    private suspend fun stream(path: String, body: JSONObject?, onEvent: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val c = open(path, if (body == null) "GET" else "POST", 90_000)
        try {
            c.setRequestProperty("Accept", "application/x-ndjson")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code == 409) throw BusyException()
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }?.let { runCatching { JSONObject(it).optString("detail") }.getOrNull() }
                throw ApiException("HTTP $code" + (err?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    withContext(Dispatchers.Main) { onEvent(ev) }
                }
            }
        } finally {
            c.disconnect()
        }
    }
}
