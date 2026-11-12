package com.joernmht.paperdesk

import org.json.JSONArray
import org.json.JSONObject

// Types from ~/PaperDesk/API.md. Parsing is lenient: missing fields become null/empty.

private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

data class Paper(
    val id: String,
    val title: String,
    val authors: List<String>,
    val year: String?,
    val venue: String?,
    val abstract: String?,
    val url: String?,
    val pdfUrl: String?,
    val doi: String?,
    val arxiv: String?,
    val why: String?,
    val cat: String?,
    val run: String?,
    val source: String,
    val status: String,
    val inCorpus: Boolean,
    val bibkey: String?,
    val hasPdf: Boolean,
    val targets: List<String>,
    val raw: JSONObject,   // passed back verbatim as `paper` in /api/action
) {
    companion object {
        fun from(o: JSONObject) = Paper(
            id = o.optString("id"),
            title = o.str("title") ?: "(untitled)",
            authors = o.optJSONArray("authors").strings(),
            year = o.str("year"),
            venue = o.str("venue"),
            abstract = o.str("abstract"),
            url = o.str("url"),
            pdfUrl = o.str("pdf_url"),
            doi = o.str("doi"),
            arxiv = o.str("arxiv"),
            why = o.str("why"),
            cat = o.str("cat"),
            run = o.str("run"),
            source = o.str("source") ?: "",
            status = o.str("status") ?: "new",
            inCorpus = o.optBoolean("in_corpus"),
            bibkey = o.str("bibkey"),
            hasPdf = o.optBoolean("has_pdf"),
            targets = o.optJSONArray("targets").strings(),
            raw = o,
        )
    }

    val byline: String
        get() {
            val a = when {
                authors.isEmpty() -> null
                authors.size <= 3 -> authors.joinToString(", ")
                else -> authors.first() + " et al."
            }
            return listOfNotNull(a, year, venue).joinToString(" · ")
        }
}

data class Job(
    val id: String, val kind: String, val title: String, val status: String,
    val created: String?, val started: String?, val finished: String?,
    val summary: String?, val logTail: String?,
) {
    val active get() = status == "queued" || status == "running"

    companion object {
        fun from(o: JSONObject) = Job(
            id = o.optString("id"), kind = o.optString("kind"), title = o.str("title") ?: o.optString("kind"),
            status = o.optString("status"), created = o.str("created"), started = o.str("started"),
            finished = o.str("finished"), summary = o.str("summary"), logTail = o.str("log_tail"),
        )
    }
}

data class Event(val seq: Int, val ts: String, val kind: String, val title: String, val body: String, val ref: String?) {
    companion object {
        fun from(o: JSONObject) = Event(
            seq = o.optInt("seq"), ts = o.optString("ts"), kind = o.optString("kind"),
            title = o.optString("title"), body = o.optString("body"), ref = o.str("ref"),
        )
    }
}

data class Decision(val ts: String, val title: String, val action: String, val target: String?) {
    val label get() = when (action) {
        "include" -> "Included in ${target?.uppercase()}"
        "literature" -> "Added to literature base"
        "dismiss" -> "Dismissed"
        "remarkable" -> "Sent to reMarkable"
        "later" -> "Later"
        "restore" -> "Restored"
        else -> action
    }
    companion object {
        fun from(o: JSONObject) = Decision(o.optString("ts"), o.optString("title"), o.optString("action"), o.str("target"))
    }
}

/** One-line summary of /api/decisions stats. */
fun decisionStats(o: JSONObject?): String {
    if (o == null) return ""
    val inc = o.optJSONObject("include")
    val keys = inc?.keys()?.asSequence()?.toList()?.sorted().orEmpty()
    val perPaper = keys.mapNotNull { pid -> inc?.optInt(pid, 0)?.takeIf { it > 0 }?.let { "${pid.uppercase()} $it" } }
    val incTotal = keys.sumOf { pid -> inc?.optInt(pid, 0) ?: 0 }
    return "Included $incTotal" + (if (perPaper.isNotEmpty()) " (" + perPaper.joinToString(", ") + ")" else "") +
        " · literature ${o.optInt("literature")} · dismissed ${o.optInt("dismissed")} · reMarkable ${o.optInt("remarkable")}"
}

data class RmEntry(val name: String, val type: String, val path: String) {
    val isDir get() = type == "d"
}

data class DraftVersion(val hash: String, val sent: String?, val remote: String?, val older: Boolean, val annotated: String?)
data class DraftPaper(val pid: String, val label: String, val versions: List<DraftVersion>) {
    /** Newest version first (server order is not assumed). */
    val latest get() = versions.maxByOrNull { it.sent ?: "" }
}

data class AnnotatedFile(val name: String, val size: Long, val mtime: String?)

fun parseDrafts(o: JSONObject): List<DraftPaper> = o.optJSONArray("papers").mapObjects { p ->
    DraftPaper(
        pid = p.optString("pid"), label = p.optString("label", p.optString("pid").uppercase()),
        versions = p.optJSONArray("versions").mapObjects { v ->
            DraftVersion(
                hash = v.optString("hash"), sent = v.str("sent"), remote = v.str("remote"),
                older = v.optBoolean("older"), annotated = v.str("annotated"),
            )
        },
    )
}

fun parseAnnotated(o: JSONObject): List<AnnotatedFile> = o.optJSONArray("files").mapObjects {
    AnnotatedFile(it.optString("name"), it.optLong("size"), it.str("mtime"))
}

fun parseRmLs(o: JSONObject): List<RmEntry> = o.optJSONArray("entries").mapObjects {
    RmEntry(it.optString("name"), it.optString("type"), it.optString("path"))
}

/** Targets of servers older than 0.2, which do not list them in /api/health. */
val LEGACY_TARGETS = listOf(
    "p0" to "P0 raiLPminer",
    "p1" to "P1 LP Mining",
    "p2" to "P2 raiLParchitect",
    "p3" to "P3 raiLPoperator",
)

/**
 * What this server offers (GET /api/health). A server that sends no `features` is a pre-0.2 one: everything it
 * always had is on, with the classic targets; topics (new in 0.2) are off.
 */
data class ServerInfo(
    val name: String, val remarkable: Boolean, val drafts: Boolean, val transfer: Boolean, val topics: Boolean,
    val agent: Boolean, val targets: List<Pair<String, String>>,
    /** The server can register itself with a reMarkable from a one-time code (setup wizard step). */
    val rmConnect: Boolean = false,
    /** Paper repos can be added/removed from the app (portable desks). */
    val papersEdit: Boolean = false,
    /** TeX is installed on the server (Build PDF works). */
    val latex: Boolean = true,
    /** The chat agent is installed and can be configured from the app. */
    val agentSetup: Boolean = false,
    /** GET/POST /api/secrets exists (keys step). */
    val secrets: Boolean = false,
    /** The agent's base URL as the server knows it ("" = none / not configured). */
    val agentUrl: String = "",
) {
    companion object {
        val LEGACY = ServerInfo("", remarkable = true, drafts = true, transfer = true, topics = false, agent = true, targets = LEGACY_TARGETS)

        fun from(o: JSONObject): ServerInfo {
            val f = o.optJSONObject("features") ?: return LEGACY.copy(name = o.optString("name"))
            val rm = f.optBoolean("remarkable", true)
            return ServerInfo(
                rmConnect = f.optBoolean("rm_connect", false),
                papersEdit = f.optBoolean("papers_edit", false), latex = f.optBoolean("latex", true),
                agentSetup = f.optBoolean("agent_setup", false),
                // servers that know papers_edit (0.3+) have /api/secrets; an explicit flag wins
                secrets = f.optBoolean("secrets", f.has("papers_edit")),
                agentUrl = o.optString("agent_url").trimEnd('/'),
                name = o.optString("name"), remarkable = rm,
                drafts = rm && f.optBoolean("drafts", true), transfer = rm && f.optBoolean("transfer", true),
                topics = f.optBoolean("topics", false), agent = f.optBoolean("agent", true),
                targets = if (o.has("targets")) o.optJSONArray("targets").mapObjects {
                    it.optString("id") to it.optString("label", it.optString("id").uppercase())
                }.filter { it.first.isNotBlank() } else LEGACY_TARGETS,
            )
        }
    }
}

/** A topic the server watches for new papers (results arrive in the inbox with source "watch"). */
data class Topic(val id: String, val name: String, val query: String, val lastRun: String?) {
    companion object {
        fun from(o: JSONObject) = Topic(o.optString("id"), o.optString("name"), o.optString("query"),
            if (o.isNull("last_run")) null else o.optString("last_run").takeIf { it.isNotBlank() })
    }
}


data class ChatThread(val id: String, val title: String, val updated: String, val model: String, val approvals: String = "auto",
                      val subagents: Boolean = true, val origin: String? = null, val running: Boolean = false, val lastSeq: Int = 0) {
    companion object {
        fun from(o: JSONObject) = ChatThread(o.optString("id"), o.optString("title"), o.optString("updated"), o.optString("model"),
            o.optString("approvals", "auto"), o.optBoolean("subagents", true),
            o.optString("origin").takeIf { !o.isNull("origin") && it.isNotBlank() }, o.optBoolean("running"), o.optInt("last_seq"))
    }
}

/** One row in a chat: a message, an agent step (tool_call + its tool_result, paired by id) or an approval request. */
sealed class ChatItem {
    data class Msg(val role: String, val text: String) : ChatItem()
    data class Step(
        val id: String, val name: String, val kind: String, val title: String, val args: String,
        val result: String? = null, val ok: Boolean? = null, val exitCode: Int? = null, val url: String? = null,
        val autoApproved: Boolean = false,
    ) : ChatItem()
    data class Approval(val id: String, val text: String, val actions: List<ApprovalAction>, val state: String = "pending") : ChatItem()
}

data class RepoInfo(val repo: String, val description: String, val private: Boolean, val writable: Boolean, val onBox: Boolean = false)

/** source = box | github | box+github; masked = secret files blanked in a box mount */
data class WorkspaceRepo(val repo: String, val mode: String, val branch: String?, val source: String = "", val masked: Int = 0) {
    val name get() = repo.substringAfter('/')
}

fun parseWorkspace(o: JSONObject): List<WorkspaceRepo> =
    o.optJSONArray("workspace").mapObjects {
        WorkspaceRepo(it.optString("repo"), it.optString("mode", "read"), it.str("branch"), it.optString("source"),
            it.optJSONArray("masked")?.length() ?: 0)
    }

data class ApprovalAction(val name: String, val kind: String, val title: String, val args: List<Pair<String, String>>)

data class TodoItem(val content: String, val status: String)

private fun argsText(o: Any?): String = when (o) {
    is JSONObject -> o.toString(2)
    null -> ""
    else -> o.toString()
}

private fun argPairs(o: JSONObject?): List<Pair<String, String>> =
    o?.keys()?.asSequence()?.map { k -> k to (o.opt(k)?.let { if (it is JSONObject || it is org.json.JSONArray) it.toString() else it.toString() } ?: "") }?.toList() ?: emptyList()

fun parseTodos(o: JSONObject): List<TodoItem> =
    o.optJSONArray("items").mapObjects { TodoItem(it.optString("content"), it.optString("status")) }

fun stepFromCall(o: JSONObject) = ChatItem.Step(
    id = o.optString("id"), name = o.optString("name"), kind = o.optString("kind", "tool"),
    title = o.optString("title").ifBlank { o.optString("text").take(120) },
    args = argsText(o.opt("args")).ifBlank { o.optString("text") },
    autoApproved = o.optBoolean("auto_approved", false),
)

fun ChatItem.Step.withResult(o: JSONObject) = copy(
    result = o.optString("text"), ok = if (o.has("ok")) o.optBoolean("ok") else true,
    exitCode = if (o.has("exit_code") && !o.isNull("exit_code")) o.optInt("exit_code") else null,
    url = o.str("url"),
)

fun approvalFrom(o: JSONObject) = ChatItem.Approval(
    id = o.optString("id"), text = o.optString("text"),
    actions = o.optJSONArray("actions").mapObjects {
        ApprovalAction(it.optString("name"), it.optString("kind"), it.optString("title"), argPairs(it.optJSONObject("args")))
    },
)


// ------------------------------------------------------------------ papers (Paper tab, editor)

data class PaperHead(val hash: String, val date: String, val subject: String)

/** A paper repo on the server. [legacy] = built from the reMarkable drafts list of a server without /api/papers. */
data class PaperInfo(val id: String, val label: String, val branch: String, val main: String, val head: PaperHead?,
                     val comments: Int, val rm: Boolean, val legacy: Boolean = false) {
    companion object {
        fun from(o: JSONObject) = PaperInfo(
            o.optString("id"), o.optString("label", o.optString("id")), o.optString("branch"), o.optString("main"),
            o.optJSONObject("head")?.let { PaperHead(it.optString("hash"), it.optString("date"), it.optString("subject")) },
            o.optInt("comments"), o.optBoolean("rm"),
        )
    }
}

data class PaperFile(val path: String, val size: Long)

/** A file as loaded for editing; [sha] = git blob sha of exactly this text (the base for saving). */
data class FileDoc(val path: String, val text: String, val sha: String, val head: String)

data class SaveResult(val sha: String, val commit: String, val pushed: Boolean, val note: String?)

/** 409 on save: someone changed the file on the server since it was opened. */
class ConflictException(val theirText: String, val theirSha: String) : Exception("changed")

/** A comment in a paper (line/col 1-based). kind = mark | note | rmnote | rmhl | rmdel */
data class PaperComment(val id: String, val kind: String, val path: String, val line: Int, val col: Int,
                        val marked: String?, val comment: String?) {
    val fromTablet get() = kind.startsWith("rm")
    companion object {
        fun from(o: JSONObject) = PaperComment(o.optString("id"), o.optString("kind"), o.optString("path"),
            o.optInt("line", 1), o.optInt("col", 1),
            if (o.isNull("marked")) null else o.optString("marked"), if (o.isNull("comment")) null else o.optString("comment"))
    }
}

// ------------------------------------------------------------------ keys + agent setup

data class Secret(val name: String, val label: String, val help: String, val url: String?, val set: Boolean, val optional: Boolean) {
    companion object {
        fun from(o: JSONObject) = Secret(o.optString("name"), o.optString("label", o.optString("name")), o.optString("help"),
            if (o.isNull("url")) null else o.optString("url").takeIf { it.isNotBlank() }, o.optBoolean("set"), o.optBoolean("optional"))
    }
}

data class LlmProvider(val label: String, val baseUrl: String, val model: String, val url: String?)

data class AgentSetup(val baseUrl: String, val model: String, val configured: Boolean, val providers: List<LlmProvider>) {
    companion object {
        fun from(o: JSONObject) = AgentSetup(o.optString("base_url"), o.optString("model"), o.optBoolean("configured"),
            o.optJSONArray("providers").mapObjects {
                LlmProvider(it.optString("label"), it.optString("base_url"), it.optString("model"),
                    if (it.isNull("url")) null else it.optString("url").takeIf { u -> u.isNotBlank() })
            })
    }
}
