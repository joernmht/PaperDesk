package com.joernmht.paperdesk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joernmht.paperdesk.AnnotatedFile
import com.joernmht.paperdesk.AppCtx
import com.joernmht.paperdesk.DraftPaper
import com.joernmht.paperdesk.Event
import com.joernmht.paperdesk.Job
import com.joernmht.paperdesk.RmEntry
import kotlinx.coroutines.delay

/** A destructive or expensive action waiting for confirmation. */
private data class Confirm(val title: String, val text: String, val go: () -> Unit)

// ---------- reMarkable ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemarkableScreen(app: AppCtx, reloadKey: Int) {
    var tick by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateListOf<DraftPaper>() }
    val annotated = remember { mutableStateListOf<AnnotatedFile>() }
    var path by rememberSaveable { mutableStateOf("/Papers") }
    val entries = remember { mutableStateListOf<RmEntry>() }
    var lsLoading by remember { mutableStateOf(false) }
    var lsError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    LaunchedEffect(reloadKey, tick) {
        loading = true
        try {
            val d = app.api.rmDrafts(); drafts.clear(); drafts.addAll(d)
            val a = app.api.rmAnnotated(); annotated.clear(); annotated.addAll(a)
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
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

    fun op(op: String, pid: String? = null, hash: String? = null, p: String? = null) =
        app.run(null, { app.api.rmAction(op, pid, hash, p) }) { app.jobStarted(it) }

    PullToRefreshBox(isRefreshing = loading || lsLoading, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            error?.let { item { Text("Cannot reach the box: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }

            item { Header("My drafts") }
            items(drafts, key = { "d:" + it.pid }) { d ->
                DraftCard(app, d,
                    onSend = { confirm = Confirm("Send fresh ${d.label}?", "Builds the paper from its last commit and sends it to /Papers/My drafts.") { op("draft", pid = d.pid) } },
                    onPull = { op("draftpull", pid = d.pid, hash = d.latest?.hash) },
                    onTransfer = {
                        confirm = Confirm("Transfer marks into LaTeX?",
                            "Reads your handwritten marks on ${d.label} (${d.latest?.hash ?: "latest"}) and writes them into the source as \\rmnote / \\rmhl / \\rmdel notes. This COMMITS AND PUSHES the paper.") {
                            op("transfer", pid = d.pid, hash = d.latest?.hash)
                        }
                    })
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Header("Browse tablet")
                }
            }
            item {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (path != "/") IconButton(onClick = { path = path.substringBeforeLast('/').ifEmpty { "/" } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                    }
                    Text(path, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                }
            }
            lsError?.let { item { Text("ls failed: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            items(entries, key = { "e:" + it.path }) { e ->
                RmRow(e,
                    onOpen = { path = e.path },
                    onPull = { op("pull", p = e.path) },
                    onArchive = { confirm = Confirm("Archive?", "Moves “${e.name}” into ${path.trimEnd('/')}/Older.") { op("archive", p = e.path); tick++ } },
                    onDelete = { confirm = Confirm("Delete from tablet?", "Moves “${e.name}” to the reMarkable trash.") { op("delete", p = e.path); tick++ } })
            }
            if (entries.isEmpty() && !lsLoading && lsError == null) item { Text("(empty)", Modifier.padding(16.dp)) }

            item { Header("Annotated PDFs on the box") }
            items(annotated, key = { "a:" + it.name }) { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    supportingContent = { Text(listOfNotNull("%.1f MB".format(f.size / 1e6), f.mtime).joinToString(" · ")) },
                    leadingContent = { Icon(Icons.Filled.PictureAsPdf, null) },
                    modifier = Modifier.clickable { app.openAnnotated(f.name) },
                )
            }
            if (annotated.isEmpty() && !loading) item { Text("None pulled yet.", Modifier.padding(16.dp)) }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) }, text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { confirm = null; c.go() }) { Text("Go") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(app: AppCtx, d: DraftPaper, onSend: () -> Unit, onPull: () -> Unit, onTransfer: () -> Unit) {
    val v = d.latest
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(d.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (v == null) "Never sent" else "Latest: ${v.hash} · sent ${v.sent ?: "?"}" + (if (v.annotated != null) " · annotations pulled" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilledTonalButton(onClick = onSend, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Send fresh draft") }
                if (v != null) {
                    OutlinedButton(onClick = onPull, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Pull annotations") }
                    OutlinedButton(onClick = onTransfer, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Transfer marks into LaTeX") }
                    v.annotated?.let { name ->
                        OutlinedButton(onClick = { app.openAnnotated(name) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Open annotated") }
                    }
                }
            }
            if (d.versions.size > 1) Text("${d.versions.size} versions on the tablet (${d.versions.count { it.older }} in Older)",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RmRow(e: RmEntry, onOpen: () -> Unit, onPull: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(e.name) },
        leadingContent = { Icon(if (e.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
        trailingContent = {
            if (!e.isDir) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Pull annotated PDF") }, onClick = { menu = false; onPull() })
                    DropdownMenuItem(text = { Text("Archive (→ Older)") }, onClick = { menu = false; onArchive() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier.clickable { if (e.isDir) onOpen() else menu = true },
    )
}

// ---------- Activity ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(app: AppCtx, reloadKey: Int) {
    val jobs = remember { mutableStateListOf<Job>() }
    val events = remember { mutableStateListOf<Event>() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf<Job?>(null) }

    suspend fun load() {
        try {
            val j = app.api.jobs(); jobs.clear(); jobs.addAll(j)
            val (ev, _) = app.api.events(0); events.clear(); events.addAll(ev.sortedByDescending { it.seq })
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    LaunchedEffect(reloadKey, tick) {
        loading = true; load(); loading = false
        // auto-refresh while visible: every 5 s while a job runs, else every 60 s
        while (true) {
            delay(if (jobs.any { it.active }) 5_000 else 60_000)
            load()
        }
    }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            error?.let { item { Text("Cannot reach the box: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            item { Header("Jobs") }
            if (jobs.isEmpty()) item { Text("No jobs yet.", Modifier.padding(16.dp)) }
            items(jobs, key = { "j:" + it.id }) { j ->
                ListItem(
                    headlineContent = { Text(j.title) },
                    supportingContent = { Text(listOfNotNull(j.status, j.finished ?: j.started ?: j.created, j.summary).joinToString(" · "), maxLines = 3) },
                    leadingContent = {
                        Icon(when (j.status) {
                            "done" -> Icons.Filled.CheckCircle
                            "failed" -> Icons.Filled.Error
                            "running" -> Icons.Filled.Sync
                            else -> Icons.Filled.HourglassTop
                        }, j.status, tint = if (j.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        shown = j
                        app.run(null, { app.api.job(j.id) }) { shown = it }
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)); Header("Updates") }
            if (events.isEmpty()) item { Text("No updates yet.", Modifier.padding(16.dp)) }
            items(events, key = { "e:" + it.seq }) { e ->
                ListItem(
                    headlineContent = { Text(e.title) },
                    supportingContent = { Text(e.body, maxLines = 6) },
                    overlineContent = { Text("${e.kind} · ${e.ts}") },
                )
            }
        }
    }

    shown?.let { j ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(j.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${j.status} · ${j.kind}", style = MaterialTheme.typography.labelMedium)
                    j.summary?.let { Text(it, Modifier.padding(vertical = 6.dp)) }
                    Text(j.logTail ?: "(no log)", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Close") } },
        )
    }
}
