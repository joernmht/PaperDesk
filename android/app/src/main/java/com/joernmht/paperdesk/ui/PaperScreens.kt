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
import com.joernmht.paperdesk.PAPER_TARGETS
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

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded })
                val link = p.url ?: p.pdfUrl
                if (link != null) IconButton(onClick = { app.openUrl(link) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open")
                }
            }
            if (p.byline.isNotBlank()) Text(p.byline, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.status != "new") StatusBadge(p.status)
                if (p.inCorpus) SuggestionChip(onClick = {}, label = { Text(p.bibkey ?: "in corpus") })
                p.targets.forEach { SuggestionChip(onClick = {}, label = { Text(it.uppercase()) }) }
                if (p.source.isNotBlank() && p.source != "scout") SuggestionChip(onClick = {}, label = { Text(p.source) })
            }
            p.why?.takeIf { it.isNotBlank() }?.let {
                AssistChip(onClick = { expanded = !expanded }, label = {
                    Text(it, maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
                })
            }
            p.abstract?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.Center) {
                FilledTonalButton(onClick = { act("literature") }, enabled = !p.inCorpus,
                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Add to literature") }
                FilledTonalButton(onClick = { pickTarget = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("Include in paper")
                }
                OutlinedButton(onClick = { act("remarkable") }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("Send to reMarkable")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Add + to read (reMarkable)") }, enabled = !p.inCorpus,
                            onClick = { menu = false; act("literature", toread = true) })
                        DropdownMenuItem(text = { Text("Later") }, onClick = { menu = false; act("later", label = "Moved to later") })
                        DropdownMenuItem(text = { Text("Dismiss") }, onClick = { menu = false; act("dismiss", label = "Dismissed") })
                        if (p.status == "dismissed" || p.status == "later")
                            DropdownMenuItem(text = { Text("Restore") }, onClick = { menu = false; act("restore", label = "Restored") })
                    }
                }
            }
        }
    }

    if (pickTarget) AlertDialog(
        onDismissRequest = { pickTarget = false },
        title = { Text("Include in which paper?") },
        text = {
            Column {
                Text("Adds it to the literature if needed and queues a cite note for that paper's session.",
                    style = MaterialTheme.typography.bodySmall)
                PAPER_TARGETS.forEach { (pid, label) ->
                    TextButton(onClick = { pickTarget = false; act("include", target = pid) }) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { pickTarget = false }) { Text("Cancel") } },
    )
}

@Composable
fun StatusBadge(status: String) {
    val c = when (status) {
        "pending" -> MaterialTheme.colorScheme.tertiaryContainer
        "added", "included" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    SuggestionChip(onClick = {}, label = { Text(status) },
        colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(containerColor = c))
}

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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !all, onClick = { all = false }, label = { Text("Open") })
            FilterChip(selected = all, onClick = { all = true }, label = { Text("All") })
            Spacer(Modifier.weight(1f))
            Text("${items.size}", modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.labelMedium)
        }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { Text("Cannot reach the box: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                if (error == null && items.isEmpty() && !loading) item {
                    Text("Inbox empty. New scout papers arrive weekly.", Modifier.padding(16.dp))
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
                    shape = SegmentedButtonDefaults.itemShape(i, scopes.size)) { Text(label) }
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
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}
