package com.joernmht.paperdesk.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joernmht.paperdesk.AppCtx
import com.joernmht.paperdesk.Topic
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.RectangleShape
import com.joernmht.paperdesk.Paper

// ---------- shared card ----------

/** One paper with all actions. [onUpdated] receives the server's updated copy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperCard(app: AppCtx, p: Paper, onUpdated: (Paper) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun act(action: String, target: String? = null, toread: Boolean = false, label: String? = null) {
        busy = true
        app.run(label, { app.api.action(p, action, target, toread) }, onErr = { busy = false }) { (item, job) ->
            busy = false
            item?.let(onUpdated)
            app.jobStarted(job)
        }
    }

    val info = app.info
    BCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // the form says where this paper stands: ■ new, ▲ on its way, ● kept
                Form(statusForm(if (p.inCorpus && p.status == "new") "added" else p.status), 14.dp,
                    Modifier.padding(top = 5.dp, end = 8.dp), label = p.status)
                Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded })
                val link = p.url ?: p.pdfUrl
                if (link != null) IconButton(onClick = { app.openUrl(link) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open")
                }
            }
            if (p.byline.isNotBlank()) Text(p.byline, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp)) {
                StatusBadge(p.status)
                if (p.inCorpus) BTag(p.bibkey ?: "in library", FormKind.Circle)
                p.targets.forEach { BTag(it.uppercase(), FormKind.Circle) }
                if (p.source.isNotBlank() && p.source != "scout") BTag(p.source)
            }
            p.why?.takeIf { it.isNotBlank() }?.let {
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Why", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
            p.abstract?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.Center) {
                if (info.targets.isNotEmpty())
                    BFilledButton(form = FormKind.Circle, onClick = { pickTarget = true }) { Text("Include in…") }
                else
                    BFilledButton(form = FormKind.Circle, enabled = !p.inCorpus, onClick = { act("literature") }) {
                        Text(if (p.inCorpus) "In the library" else "Add to literature")
                    }
                if (info.remarkable) BOutlinedButton(form = FormKind.Triangle, onClick = { act("remarkable") }) {
                    Text("Send to reMarkable")
                }
                if (p.status == "dismissed")
                    BOutlinedButton(form = FormKind.Square, onClick = { act("restore", label = "Restored") }) { Text("Restore") }
                else
                    BOutlinedButton(form = FormKind.Square, onClick = { act("dismiss", label = "Dismissed") }) { Text("Dismiss") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (info.remarkable) DropdownMenuItem(text = { Text("Literature base + to read (reMarkable)") }, enabled = !p.inCorpus,
                            leadingIcon = { Form(FormKind.Circle) },
                            onClick = { menu = false; act("literature", toread = true) })
                        DropdownMenuItem(text = { Text("Later") }, leadingIcon = { Form(FormKind.Triangle) },
                            onClick = { menu = false; act("later", label = "Moved to later") })
                        if (p.status == "later")
                            DropdownMenuItem(text = { Text("Restore") }, leadingIcon = { Form(FormKind.Square) },
                                onClick = { menu = false; act("restore", label = "Restored") })
                    }
                }
            }
        }
    }

    if (pickTarget) AlertDialog(
        onDismissRequest = { pickTarget = false },
        title = { Text("Include in…") },
        text = {
            Column {
                Text("Including it in a paper also adds it to the literature base, and queues a cite task for that paper.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pickTarget = false; act("literature") }, enabled = !p.inCorpus) {
                    Text(if (p.inCorpus) "Literature base (already in: ${p.bibkey ?: "yes"})" else "Literature base only")
                }
                info.targets.forEach { (pid, label) ->
                    val done = pid in p.targets
                    TextButton(onClick = { pickTarget = false; act("include", target = pid) }, enabled = !done) {
                        Text(if (done) "$label (already)" else label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { pickTarget = false }) { Text("Cancel") } },
    )
}

/** Status as form + word; never as a colour tint alone. */
@Composable
fun StatusBadge(status: String) = BTag(status, statusForm(status))

private fun <T> MutableList<T>.replaceWhere(pred: (T) -> Boolean, v: T) {
    val i = indexOfFirst(pred); if (i >= 0) set(i, v)
}

// ---------- Inbox ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(app: AppCtx, reloadKey: Int) {
    var all by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val items = remember { mutableStateListOf<Paper>() }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(all, reloadKey, tick) {
        loading = true
        try {
            val r = app.api.inbox(all); items.clear(); items.addAll(r); error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    var topics by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !all, onClick = { all = false }, label = { Text("Open") })
            FilterChip(selected = all, onClick = { all = true }, label = { Text("All") })
            Spacer(Modifier.weight(1f))
            if (app.info.topics) TextButton(onClick = { topics = true }) { Text("Topics") }
            Text("${items.size}", style = MaterialTheme.typography.labelLarge)
        }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { ErrorLine("Cannot reach the server: $it") } }
                if (error == null && items.isEmpty() && !loading) item {
                    if (app.info.topics) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nothing here yet — add topics to follow")
                        BFilledButton(form = FormKind.Triangle, onClick = { topics = true }) { Text("Topics") }
                    } else Text("Inbox empty. New scout papers arrive weekly.", Modifier.padding(16.dp))
                }
                items(items, key = { it.id }) { p ->
                    PaperCard(app, p) { upd ->
                        if (!all && (upd.status == "dismissed" || upd.status == "added" || upd.status == "included"))
                            items.removeAll { it.id == upd.id }
                        else items.replaceWhere({ it.id == upd.id }, upd)
                    }
                }
            }
        }
    }
    if (topics) TopicsDialog(app) { topics = false; tick++ }
}

/** Topics the server watches: list, add (name + search words), remove, run now. Returns via [onCount] how many exist. */
@Composable
fun TopicsEditor(app: AppCtx, showRun: Boolean = true, onCount: (Int) -> Unit = {}) {
    val list = remember { mutableStateListOf<Topic>() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    fun set(t: List<Topic>) { list.clear(); list.addAll(t); error = null; loading = false; onCount(t.size) }
    LaunchedEffect(Unit) {
        try { set(app.api.topics()) } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; loading = false }
    }

    Column {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let { ErrorLine(it, Modifier.padding(top = 8.dp)) }
        if (!loading && error == null && list.isEmpty()) Text("No topics yet.", Modifier.padding(top = 8.dp))
        list.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Form(FormKind.Circle, 12.dp, Modifier.padding(end = 8.dp), label = "following")
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleSmall)
                    Text(t.query, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t.lastRun?.let { "last run " + it.replace('T', ' ').take(16) } ?: "not run yet",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { app.run(null, { app.api.topicsOp("remove", id = t.id) }) { (ts, _) -> set(ts) } }) {
                    Form(FormKind.Square, 10.dp, Modifier.padding(end = 6.dp)); Text("Remove")
                }
            }
        }
        InkRule(Modifier.padding(top = 12.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Name") },
            placeholder = { Text("Railway rescheduling") }, singleLine = true)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search words") },
            placeholder = { Text("railway rescheduling optimisation") }, singleLine = true)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BOutlinedButton(form = FormKind.Circle, enabled = query.isNotBlank(), onClick = {
                val n = name.trim().ifBlank { query.trim() }
                app.run(null, { app.api.topicsOp("add", name = n, query = query.trim()) }) { (ts, _) -> set(ts); name = ""; query = "" }
            }) { Text("Add topic") }
            if (showRun) BOutlinedButton(form = FormKind.Triangle, enabled = list.isNotEmpty(), onClick = {
                app.run(null, { app.api.topicsOp("run") }) { (ts, job) -> set(ts); app.jobStarted(job) }
            }) { Text("Run now") }
        }
    }
}

@Composable
fun TopicsDialog(app: AppCtx, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Topics") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("The server searches each topic regularly and puts new papers into your inbox.", style = MaterialTheme.typography.bodySmall)
                TopicsEditor(app)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

// ---------- Search ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(app: AppCtx) {
    var q by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("all") }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val library = remember { mutableStateListOf<Paper>() }
    val web = remember { mutableStateListOf<Paper>() }

    fun go() {
        if (q.isBlank()) return
        loading = true
        app.run(null, { app.api.search(q.trim(), scope) }, onErr = { loading = false }) { (l, w) ->
            library.clear(); library.addAll(l); web.clear(); web.addAll(w); searched = true; loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text("Title, author, topic…") }, singleLine = true,
            trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Filled.Search, "Search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
        )
        val scopes = listOf("library" to "Library", "web" to "Web", "all" to "Both")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
            scopes.forEachIndexed { i, (k, label) ->
                SegmentedButton(selected = scope == k, onClick = { scope = k; if (searched) go() },
                    shape = RectangleShape) { Text(label) }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            if (scope != "web" && searched) {
                item { Header("Your library (${library.size})") }
                items(library, key = { "l:" + it.id }) { p -> PaperCard(app, p) { u -> library.replaceWhere({ it.id == u.id }, u) } }
            }
            if (scope != "library" && searched) {
                item { Header("arXiv · OpenAlex · Scopus (${web.size})") }
                items(web, key = { "w:" + it.id }) { p -> PaperCard(app, p) { u -> web.replaceWhere({ it.id == u.id }, u) } }
            }
            if (!searched && !loading) item {
                Text("Searches your corpus (metadata + PDF full text) and the web. Every result has the same buttons as the inbox.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}
