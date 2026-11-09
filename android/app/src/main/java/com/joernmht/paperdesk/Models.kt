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
    val perPaper = PAPER_TARGETS.mapNotNull { (pid, _) -> inc?.optInt(pid, 0)?.takeIf { it > 0 }?.let { "${pid.uppercase()} $it" } }
    val incTotal = PAPER_TARGETS.sumOf { (pid, _) -> inc?.optInt(pid, 0) ?: 0 }
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

val PAPER_TARGETS = listOf(
    "p0" to "P0 raiLPminer",
    "p1" to "P1 LP Mining",
    "p2" to "P2 raiLParchitect",
    "p3" to "P3 raiLPoperator",
)


data class ChatThread(val id: String, val title: String, val updated: String, val model: String) {
    companion object {
        fun from(o: JSONObject) = ChatThread(o.optString("id"), o.optString("title"), o.optString("updated"), o.optString("model"))
    }
}

/** role: user | assistant | tool_call | tool_result */
data class ChatMsg(val role: String, val text: String, val name: String? = null) {
    companion object {
        fun from(o: JSONObject) = ChatMsg(o.optString("role"), o.optString("text"), o.str("name"))
    }
}

data class ChatEvent(val type: String, val text: String, val name: String?) {
    companion object {
        fun from(o: JSONObject) = ChatEvent(o.optString("type"), o.optString("text"), o.str("name"))
    }
}
