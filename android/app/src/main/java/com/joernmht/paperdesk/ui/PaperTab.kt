package com.joernmht.paperdesk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joernmht.paperdesk.AnnotatedFile
import com.joernmht.paperdesk.ApiException
import com.joernmht.paperdesk.AppCtx
import com.joernmht.paperdesk.DraftPaper
import com.joernmht.paperdesk.Job
import com.joernmht.paperdesk.PaperComment
import com.joernmht.paperdesk.PaperFile
import com.joernmht.paperdesk.PaperHead
import com.joernmht.paperdesk.PaperInfo
import com.joernmht.paperdesk.RmEntry
import kotlinx.coroutines.delay
import org.json.JSONObject

/** A destructive or expensive action waiting for confirmation. */
internal data class Confirm(val title: String, val text: String, val go: () -> Unit)

@Composable
internal fun ConfirmDialog(c: Confirm?, onDone: () -> Unit) {
    c ?: return
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(c.title) }, text = { Text(c.text) },
        confirmButton = { TextButton(onClick = { onDone(); c.go() }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

private fun headLine(h: PaperHead?) = h?.let {
    listOf(it.subject.take(60), it.hash.take(7), it.date.replace('T', ' ').take(16)).filter(String::isNotBlank).joinToString(" · ")
} ?: ""

/** Polls a job until it is finished; returns the final record. */
private suspend fun follow(app: AppCtx, job: Job, onUpdate: (Job) -> Unit): Job {
    var j = job
    while (j.active) {
        delay(3000)
        j = try { app.api.job(j.id) } catch (_: Exception) { j }
        onUpdate(j)
    }
    return j
}

// ================================================================== Paper tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTabScreen(app: AppCtx, reloadKey: Int) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val papers = remember { mutableStateListOf<PaperInfo>() }
    val drafts = remember { mutableStateListOf<DraftPaper>() }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey, tick) {
        loading = true
        try {
            val ps = try {
                app.api.papers()
            } catch (e: ApiException) {
                // a server without /api/papers: its reMarkable drafts are the papers
                if (e.code != 404 || !app.info.drafts) throw e
                app.api.rmDrafts().map { PaperInfo(it.pid, it.label, "", "", null, 0, rm = true, legacy = true) }
            }
            papers.clear(); papers.addAll(ps)
            if (app.info.drafts) { val d = app.api.rmDrafts(); drafts.clear(); drafts.addAll(d) }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    val open = papers.firstOrNull { it.id == openId }
    if (open != null) {
        PaperDetail(app, open, drafts.firstOrNull { it.pid == open.id }, onBack = { openId = null }, onChanged = { tick++ })
        return
    }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            error?.let { item { ErrorLine("Cannot reach the server: $it") } }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Papers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (app.info.papersEdit) BOutlinedButton(form = FormKind.Circle, onClick = { adding = true }) { Text("Add paper") }
                }
            }
            if (papers.isEmpty() && !loading && error == null) item {
                Text(if (app.info.papersEdit) "No papers yet. Add the git repo of a paper (Overleaf or GitHub) to edit and build it here."
                    else "No papers on this desk.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(papers, key = { "p:" + it.id }) { p ->
                PaperRow(p) { openId = p.id }
                Hairline(Modifier.padding(start = 16.dp))
            }
            if (app.info.remarkable) tabletSection(app, reloadKey)
        }
    }

    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("Add a paper") },
        text = { AddPaperForm(app, compact = true) { ps -> papers.clear(); papers.addAll(ps); adding = false } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
    )
}

@Composable
private fun PaperRow(p: PaperInfo, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.titleMedium)
            val sub = headLine(p.head)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.branch.isNotBlank() || p.main.isNotBlank()) Text(listOf(p.branch, p.main).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        if (p.comments > 0) Row(Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Form(FormKind.Circle, 10.dp, label = "comments"); Spacer(Modifier.width(4.dp))
            Text("${p.comments}", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Label, git URL (Overleaf git-bridge or GitHub https) and main file → POST /api/papers add. */
@Composable
fun AddPaperForm(app: AppCtx, compact: Boolean = false, onAdded: (List<PaperInfo>) -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var main by rememberSaveable { mutableStateOf("main.tex") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val urlOk = url.trim().let { it.startsWith("https://git.overleaf.com/") || it.startsWith("https://github.com/") || it.startsWith("https://") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) Text("The access tokens for Overleaf and GitHub are set in the Keys step.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("P1 LP Mining") }, singleLine = true)
        OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Git URL") },
            placeholder = { Text("https://git.overleaf.com/<project id>") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            supportingText = { Text("Overleaf: Menu → Git → the git clone URL. GitHub: the https URL of the repo.") })
        OutlinedTextField(main, { main = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Main file") }, singleLine = true)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorLine(it, Modifier) }
        BFilledButton(form = FormKind.Circle, enabled = !busy && label.isNotBlank() && urlOk && main.isNotBlank(), onClick = {
            busy = true; error = null
            app.run(null, {
                app.api.paperOp(JSONObject().put("op", "add").put("label", label.trim()).put("git_url", url.trim()).put("main", main.trim()))
            }, onErr = { busy = false }) { (ps, job) ->
                busy = false; label = ""; url = ""; main = "main.tex"
                app.jobStarted(job)
                onAdded(ps)
            }
        }) { Text("Add paper") }
    }
}

// ------------------------------------------------------------------ one paper

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PaperDetail(app: AppCtx, p: PaperInfo, draft: DraftPaper?, onBack: () -> Unit, onChanged: () -> Unit) {
    BackHandler(onBack = onBack)
    val files = remember { mutableStateListOf<PaperFile>() }
    val comments = remember { mutableStateListOf<PaperComment>() }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var build by remember { mutableStateOf<Job?>(null) }
    var pdfReady by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var head by remember(p) { mutableStateOf(p.head) }

    LaunchedEffect(p.id, tick) {
        if (p.legacy) return@LaunchedEffect
        loading = true
        try {
            val c = app.api.paperComments(p.id); comments.clear(); comments.addAll(c)
            val f = app.api.paperFiles(p.id)
            files.clear(); files.addAll(f.sortedWith(compareBy({ it.path != p.main }, { it.path })))
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }
    // back from the editor: comments and head may have changed
    val editing by EditorNav.target
    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing != null) wasEditing = true
        else if (wasEditing) { wasEditing = false; tick++ }
    }
    LaunchedEffect(build?.id) {
        val b = build ?: return@LaunchedEffect
        val done = follow(app, b) { build = it }
        build = done
        pdfReady = done.status == "done"
        if (pdfReady) app.toast("PDF ready")
    }

    fun edit(path: String, line: Int = 0, col: Int = 0, comment: Boolean = false) {
        EditorNav.target.value = EditorTarget(p.id, p.label, path, line, col, comment)
    }

    fun rm(op: String, hash: String? = null) = app.run(null, { app.api.rmAction(op, p.id, hash) }) { app.jobStarted(it) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to papers") }
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = headLine(head)
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { ErrorLine(it) }
        }

        if (!p.legacy) item {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BFilledButton(form = FormKind.Circle, onClick = {
                    if (files.size == 1) edit(files[0].path) else showFiles = !showFiles
                }) { Text("Edit") }
                if (app.info.latex) BOutlinedButton(form = FormKind.Triangle, enabled = build?.active != true, onClick = {
                    pdfReady = false
                    app.run(null, { app.api.paperBuild(p.id) }) { j -> app.jobStarted(j); build = j }
                }) { Text(if (build?.active == true) "Building…" else "Build PDF") }
                BOutlinedButton(form = FormKind.Circle, onClick = { app.run(null, { app.api.paperPdf(p.id) }) { app.openPdf(it) } }) {
                    Text(if (pdfReady) "Open the new PDF" else "Open PDF")
                }
                BOutlinedButton(form = FormKind.Triangle, onClick = {
                    app.run("Up to date", { app.api.paperSync(p.id) }) { h ->
                        h?.let { head = PaperHead(it.optString("hash"), it.optString("date"), it.optString("subject")) }
                        tick++; onChanged()
                    }
                }) { Text("Pull now") }
            }
            build?.let { b ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Form(statusForm(b.status), 12.dp, Modifier.padding(top = 4.dp), label = b.status); Spacer(Modifier.width(8.dp))
                    Text(when (b.status) {
                        "done" -> "PDF built" + (b.summary?.let { ": $it" } ?: "")
                        "failed" -> "Build failed: ${b.summary ?: "see Activity"}"
                        else -> "Building the PDF…"
                    }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (showFiles && !p.legacy) {
            item { Header("Files") }
            items(files, key = { "f:" + it.path }) { f ->
                Row(Modifier.fillMaxWidth().clickable { edit(f.path) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(f.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (f.path == p.main) Text("main", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                    Text("%.0f kB".format(f.size / 1000.0), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
                Hairline(Modifier.padding(start = 16.dp))
            }
        }

        if (!p.legacy) {
            item { Header("Comments (${comments.size})") }
            if (comments.isEmpty() && !loading) item {
                Text("No comments. In the editor, select text and tap Comment.", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(comments, key = { "c:" + it.id }) { c ->
                CommentRow(c) { edit(c.path, c.line, c.col, comment = true) }
                Hairline(Modifier.padding(start = 40.dp))
            }
        }

        if (app.info.remarkable && p.rm) {
            item { Header("reMarkable") }
            item {
                val v = draft?.latest
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(if (v == null) "Never sent to the tablet" else "Latest on the tablet: ${v.hash} · sent ${v.sent ?: "?"}" +
                        (if (v.annotated != null) " · annotations pulled" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        BOutlinedButton(form = FormKind.Triangle, onClick = {
                            confirm = Confirm("Send a fresh draft of ${p.label}?", "Builds the paper from its last commit and sends it to the tablet.") { rm("draft") }
                        }) { Text("Send fresh draft") }
                        if (v != null) {
                            BOutlinedButton(form = FormKind.Circle, onClick = { rm("draftpull", v.hash) }) { Text("Pull annotations") }
                            if (app.info.transfer) BOutlinedButton(form = FormKind.Triangle, onClick = {
                                confirm = Confirm("Transfer marks into LaTeX?",
                                    "Reads your handwritten marks on ${p.label} (${v.hash}) and writes them into the source as \\rmnote / \\rmhl / \\rmdel notes. This COMMITS AND PUSHES the paper.") {
                                    rm("transfer", v.hash)
                                }
                            }) { Text("Marks → LaTeX") }
                            v.annotated?.let { name -> BOutlinedButton(onClick = { app.openAnnotated(name) }) { Text("Open annotated") } }
                        }
                    }
                    if ((draft?.versions?.size ?: 0) > 1) Text("${draft!!.versions.size} versions on the tablet (${draft.versions.count { it.older }} in Older)",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (app.info.papersEdit && !p.legacy) item {
            Hairline(Modifier.padding(top = 24.dp))
            BOutlinedButton(form = FormKind.Square, modifier = Modifier.padding(8.dp), onClick = {
                confirm = Confirm("Remove ${p.label} from this desk?",
                    "The server forgets this paper and deletes its local copy. The git repo itself (Overleaf, GitHub) is not touched.") {
                    app.run("Removed", { app.api.paperOp(JSONObject().put("op", "remove").put("id", p.id)) }) { onBack(); onChanged() }
                }
            }) { Text("Remove from this desk") }
        }
        item { Spacer(Modifier.heightIn(min = 32.dp)) }
    }

    ConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun CommentRow(c: PaperComment, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        // same forms as in the editor: ● your comments, ■ marks from the reMarkable
        Form(if (c.fromTablet) FormKind.Square else FormKind.Circle, 12.dp, Modifier.padding(top = 4.dp),
            label = if (c.fromTablet) "from the reMarkable" else "comment")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            c.marked?.takeIf { it.isNotBlank() }?.let {
                Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(c.comment?.takeIf { it.isNotBlank() } ?: when (c.kind) { "rmhl" -> "highlighted"; "rmdel" -> "marked for deletion"; else -> "" },
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(commentWhere(c.path, c.line) + if (c.fromTablet) " · reMarkable" else "", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ the tablet (bottom of the Paper tab)

private fun androidx.compose.foundation.lazy.LazyListScope.tabletSection(app: AppCtx, reloadKey: Int) {
    item { TabletSection(app, reloadKey) }
}

@Composable
private fun TabletSection(app: AppCtx, reloadKey: Int) {
    var tick by remember { mutableStateOf(0) }
    var path by rememberSaveable { mutableStateOf("/Papers") }
    val entries = remember { mutableStateListOf<RmEntry>() }
    val annotated = remember { mutableStateListOf<AnnotatedFile>() }
    var lsLoading by remember { mutableStateOf(false) }
    var lsError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    LaunchedEffect(reloadKey, tick) {
        try { val a = app.api.rmAnnotated(); annotated.clear(); annotated.addAll(a) } catch (_: Exception) {}
    }
    LaunchedEffect(reloadKey, tick, path) {
        lsLoading = true
        try {
            val r = app.api.rmLs(path); entries.clear(); entries.addAll(r.sortedWith(compareBy({ !it.isDir }, { it.name })))
            lsError = null
        } catch (e: Exception) {
            lsError = e.message ?: e.javaClass.simpleName
        }
        lsLoading = false
    }

    fun op(op: String, p: String) = app.run(null, { app.api.rmAction(op, path = p) }) { app.jobStarted(it); tick++ }

    Column {
        Hairline(Modifier.padding(top = 24.dp))
        Header("Tablet")
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path != "/") IconButton(onClick = { path = path.substringBeforeLast('/').ifEmpty { "/" } }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
            }
            Text(path, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        if (lsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        lsError?.let { ErrorLine("Tablet: $it") }
        entries.forEach { e ->
            RmRow(e, onOpen = { path = e.path }, onPull = { op("pull", e.path) },
                onArchive = { confirm = Confirm("Archive?", "Moves “${e.name}” into ${path.trimEnd('/')}/Older.") { op("archive", e.path) } },
                onDelete = { confirm = Confirm("Delete from tablet?", "Moves “${e.name}” to the reMarkable trash.") { op("delete", e.path) } })
        }
        if (entries.isEmpty() && !lsLoading && lsError == null) Text("(empty)", Modifier.padding(16.dp))

        Header("Annotated PDFs on the server")
        annotated.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { app.openAnnotated(f.name) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PictureAsPdf, null)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull("%.1f MB".format(f.size / 1e6), f.mtime).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (annotated.isEmpty()) Text("None pulled yet.", Modifier.padding(16.dp))
    }
    ConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun RmRow(e: RmEntry, onOpen: () -> Unit, onPull: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { if (e.isDir) onOpen() else menu = true }.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (e.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null)
        Spacer(Modifier.width(16.dp))
        Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!e.isDir) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Pull annotated PDF") }, leadingIcon = { Form(FormKind.Circle) }, onClick = { menu = false; onPull() })
                DropdownMenuItem(text = { Text("Archive (→ Older)") }, leadingIcon = { Form(FormKind.Triangle) }, onClick = { menu = false; onArchive() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Form(FormKind.Square) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
