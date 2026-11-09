package com.joernmht.paperdesk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joernmht.paperdesk.AppCtx
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.joernmht.paperdesk.ChatItem
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import com.joernmht.paperdesk.RepoInfo
import com.joernmht.paperdesk.WorkspaceRepo
import com.joernmht.paperdesk.parseWorkspace
import com.joernmht.paperdesk.mapObjects
import com.joernmht.paperdesk.TodoItem
import com.joernmht.paperdesk.approvalFrom
import com.joernmht.paperdesk.parseTodos
import com.joernmht.paperdesk.stepFromCall
import com.joernmht.paperdesk.withResult
import com.mikepenz.markdown.m3.Markdown
import org.json.JSONObject
import com.joernmht.paperdesk.ChatThread
import kotlinx.coroutines.launch

/** Chat with the box's DeepAgent service (~/PaperDesk/agent, owned by the admin session). */
@Composable
fun ChatScreen(app: AppCtx, reloadKey: Int) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val o = open
    if (o == null) ThreadList(app, reloadKey) { open = it }
    else {
        BackHandler { open = null }
        ThreadView(app, o) { open = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadList(app: AppCtx, reloadKey: Int, onOpen: (String) -> Unit) {
    val threads = remember { mutableStateListOf<ChatThread>() }
    val models = remember { mutableStateListOf<String>() }
    var defaultModel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var setup by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<ChatThread?>(null) }

    LaunchedEffect(reloadKey, tick) {
        loading = true
        try {
            val (ms, def) = app.agent.health(); models.clear(); models.addAll(ms); defaultModel = def
            val t = app.agent.threads(); threads.clear(); threads.addAll(t); error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = loading, onRefresh = { tick++ }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                error?.let { item { Text("Cannot reach the agent: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                if (error == null && threads.isEmpty() && !loading) item { Text("No chats yet. Start one below.", Modifier.padding(16.dp)) }
                items(threads, key = { it.id }) { t ->
                    ListItem(
                        headlineContent = { Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${t.model.substringAfter('/')} · ${t.updated.replace('T', ' ').take(16)}") },
                        trailingContent = { IconButton(onClick = { delete = t }) { Icon(Icons.Filled.Delete, "Delete") } },
                        modifier = Modifier.clickable { onOpen(t.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { setup = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New chat") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }

    if (setup) NewChatDialog(app, models, defaultModel, onDismiss = { setup = false }) { model, repos ->
        setup = false
        app.run(null, { app.agent.newThread(model, repos) }) { onOpen(it.id) }
    }

    delete?.let { t ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Delete chat?") },
            text = { Text(t.title) },
            confirmButton = {
                TextButton(onClick = {
                    delete = null
                    app.run("Deleted", { app.agent.deleteThread(t.id) }) { threads.removeAll { it.id == t.id } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } },
        )
    }
}

/** Repo list with a per-repo "write" switch. [single]: pick exactly one (attach mid-chat). */
@Composable
private fun RepoPicker(app: AppCtx, chosen: MutableMap<String, String>, exclude: Set<String> = emptySet(), single: Boolean = false) {
    val repos = remember { mutableStateListOf<RepoInfo>() }
    var filter by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { repos.clear(); repos.addAll(app.agent.repos()) } catch (e: Exception) { error = e.message }
    }
    Column {
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("Filter repos") }, singleLine = true)
        error?.let { Text("Cannot load repos: $it", color = MaterialTheme.colorScheme.error) }
        val shown = repos.filter { it.repo !in exclude && (filter.isBlank() || it.repo.contains(filter, true) || it.description.contains(filter, true)) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(shown, key = { it.repo }) { r ->
                val on = r.repo in chosen
                Column(Modifier.fillMaxWidth().clickable {
                    if (on) chosen.remove(r.repo) else { if (single) chosen.clear(); chosen[r.repo] = "read" }
                }.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, onCheckedChange = { c -> if (!c) chosen.remove(r.repo) else { if (single) chosen.clear(); chosen[r.repo] = "read" } })
                        Column(Modifier.weight(1f)) {
                            Text(r.repo.substringAfter('/'), style = MaterialTheme.typography.bodyMedium)
                            if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (on) Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(chosen[r.repo] == "write", onCheckedChange = { w -> chosen[r.repo] = if (w) "write" else "read" }, enabled = r.writable)
                        Spacer(Modifier.width(8.dp))
                        Text(when {
                            chosen[r.repo] == "write" -> "Writable: the agent may change it and open a PR"
                            r.writable -> "Read-only (switch on to allow changes)"
                            else -> "Read-only (write not allowed for this repo)"
                        }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatDialog(app: AppCtx, models: List<String>, defaultModel: String, onDismiss: () -> Unit, onStart: (String?, List<Pair<String, String>>) -> Unit) {
    var model by remember { mutableStateOf(defaultModel) }
    var modelMenu by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            Column {
                Box {
                    OutlinedButton(onClick = { modelMenu = true }) { Text("Model: " + model.ifBlank { "default" }.substringAfter('/')) }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { m -> DropdownMenuItem(text = { Text(m.substringAfter('/')) }, onClick = { model = m; modelMenu = false }) }
                    }
                }
                Text("Repos (optional; read-only unless you switch on write)", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                RepoPicker(app, chosen)
            }
        },
        confirmButton = { TextButton(onClick = { onStart(model.ifBlank { null }, chosen.map { it.key to it.value }) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One user turn: the question, its steps (one dropdown), approvals and the final answer. */
data class Turn(
    val user: String,
    val steps: List<ChatItem> = emptyList(),          // Step boxes + Msg("narration")
    val approvals: List<ChatItem.Approval> = emptyList(),
    val answer: String = "",
    val live: Map<Int, String> = emptyMap(),           // streaming text per seg, not yet known to be narration
    val segAt: Map<Int, Int> = emptyMap(),             // seg -> steps index when it began (narration goes there)
    val done: Boolean = true,
)

private fun Turn.addStepEvent(o: JSONObject, kind: String): Turn = when (kind) {
    "tool_call" -> { val st = stepFromCall(o); if (st.kind == "todos") this else copy(steps = steps + st) }
    "tool_result" -> {
        val id = o.optString("id")
        val i = steps.indexOfLast { it is ChatItem.Step && it.id == id }
        when {
            i >= 0 -> copy(steps = steps.toMutableList().also { it[i] = (it[i] as ChatItem.Step).withResult(o) })
            o.optString("kind") == "todos" -> this
            else -> copy(steps = steps + ChatItem.Step(id, o.optString("name"), o.optString("kind", "tool"), o.optString("name"), "").withResult(o))
        }
    }
    "narration" -> copy(steps = steps + ChatItem.Msg("narration", o.optString("text")))
    else -> this
}

private fun turnsFromHistory(r: JSONObject, todos: (List<TodoItem>) -> Unit): List<Turn> {
    val out = mutableListOf<Turn>()
    r.optJSONArray("turns").mapObjects { it }.forEach { t ->
        var turn = Turn(user = t.optString("user"), answer = if (t.isNull("answer")) "" else t.optString("answer"))
        t.optJSONArray("steps").mapObjects { it }.forEach { row ->
            val role = row.optString("role")
            if (role == "todos") todos(parseTodos(row)) else turn = turn.addStepEvent(row, role)
        }
        out += turn
    }
    val pending = r.optJSONArray("pending_approvals").mapObjects { approvalFrom(it) }
    if (pending.isNotEmpty() && out.isNotEmpty()) out[out.size - 1] = out.last().let { it.copy(approvals = it.approvals + pending) }
    return out
}

@Composable
private fun ThreadView(app: AppCtx, id: String, onBack: () -> Unit) {
    val turns = remember { mutableStateListOf<Turn>() }
    val workspace = remember { mutableStateListOf<WorkspaceRepo>() }
    var todos by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attach by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    suspend fun reload() {
        val r = app.agent.thread(id)
        val t = ChatThread.from(r); title = t.title; model = t.model
        turns.clear(); turns.addAll(turnsFromHistory(r) { l -> todos = l })
        workspace.clear(); workspace.addAll(parseWorkspace(r))
    }
    LaunchedEffect(id) { try { reload() } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName } }
    LaunchedEffect(turns.size, turns.lastOrNull()?.let { it.answer.length + it.live.values.sumOf { s -> s.length } + it.steps.size }) {
        if (turns.isNotEmpty()) list.scrollToItem(turns.size - 1, Int.MAX_VALUE / 2)
    }

    fun onEvent(ev: JSONObject) {
        if (turns.isEmpty()) return
        val i = turns.size - 1
        val t = turns[i]
        turns[i] = when (val type = ev.optString("type")) {
            "token" -> {
                val seg = ev.optInt("seg", 0)
                t.copy(live = t.live + (seg to (t.live[seg].orEmpty() + ev.optString("text"))),
                    segAt = if (seg in t.segAt) t.segAt else t.segAt + (seg to t.steps.size))
            }
            "segment" -> if (ev.optBoolean("final", true)) t else {
                val seg = ev.optInt("seg", 0)
                val text = t.live[seg].orEmpty()
                val at = (t.segAt[seg] ?: t.steps.size).coerceIn(0, t.steps.size)
                if (text.isBlank()) t.copy(live = t.live - seg)
                else t.copy(steps = t.steps.toMutableList().also { it.add(at, ChatItem.Msg("narration", text)) }, live = t.live - seg)
            }
            "tool_call", "tool_result" -> t.addStepEvent(ev, type)
            "todos" -> { todos = parseTodos(ev); t }
            "approval" -> { val ap = approvalFrom(ev); if (t.approvals.any { it.id == ap.id }) t else t.copy(approvals = t.approvals + ap) }
            "done" -> t.copy(answer = ev.optString("text").ifBlank { t.live.toSortedMap().values.lastOrNull().orEmpty() }, live = emptyMap(), done = true)
            "error" -> { error = ev.optString("text"); t }
            else -> t
        }
    }

    fun run(block: suspend ((JSONObject) -> Unit) -> Unit) {
        busy = true; error = null
        app.scope.launch {
            try { block { onEvent(it) } } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            if (turns.isNotEmpty()) turns[turns.size - 1] = turns.last().let { t ->
                if (t.done) t else t.copy(done = true, answer = t.answer.ifBlank { t.live.toSortedMap().values.lastOrNull().orEmpty() }, live = emptyMap())
            }
            busy = false
            try { workspace.clear(); workspace.addAll(parseWorkspace(app.agent.thread(id))) } catch (_: Exception) {}
        }
    }

    fun markApprovals(state: String) {
        turns.replaceAll { t -> t.copy(approvals = t.approvals.map { if (it.state == "pending") it.copy(state = state) else it }) }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        markApprovals("answered in chat")
        turns.add(Turn(user = text, done = false))
        if (title == "New chat") title = text.take(60)
        run { onEv -> app.agent.chat(id, text, onEv) }
    }

    fun decide(approve: Boolean, reason: String?) {
        if (busy) return
        markApprovals(if (approve) "approved" else "rejected")
        turns.add(Turn(user = if (approve) "✓ Approved" else "✗ Rejected" + (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""), done = false))
        run { onEv -> app.agent.resume(id, approve, reason, onEv) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "Chat" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (model.isNotBlank()) Text(model.substringAfter('/'), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { attach = true }) { Icon(Icons.Filled.Source, "Attach repo") }
        }
        if (workspace.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            workspace.forEach { w ->
                AssistChip(onClick = {}, label = { Text(w.repo.substringAfter('/') + if (w.mode == "write") " · write" else " · read") },
                    leadingIcon = { Icon(if (w.mode == "write") Icons.Filled.Edit else Icons.Filled.Lock, null, Modifier.size(16.dp)) })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (todos.isNotEmpty()) PlanPanel(todos)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(turns.size) { i -> TurnView(app, turns[i], enabled = !busy) { ok, reason -> decide(ok, reason) } }
            error?.let { item { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) } }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask…") }, maxLines = 6)
            IconButton(onClick = { send() }, enabled = !busy && input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }

    if (attach) {
        val chosen = remember { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = { attach = false },
            title = { Text("Attach a repo") },
            text = { RepoPicker(app, chosen, exclude = workspace.map { it.repo }.toSet(), single = true) },
            confirmButton = {
                TextButton(enabled = chosen.isNotEmpty(), onClick = {
                    val (repo, mode) = chosen.entries.first().toPair()
                    attach = false
                    app.run("Attached ${repo.substringAfter('/')} ($mode)", { app.agent.attachRepo(id, repo, mode) }) { r ->
                        workspace.clear(); workspace.addAll(parseWorkspace(r))
                    }
                }) { Text("Attach") }
            },
            dismissButton = { TextButton(onClick = { attach = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TurnView(app: AppCtx, t: Turn, enabled: Boolean, onDecide: (Boolean, String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bubble(ChatItem.Msg("user", t.user))
        val liveText = t.live.toSortedMap().values.lastOrNull().orEmpty()
        if (t.steps.isNotEmpty() || (!t.done && liveText.isBlank())) StepsDropdown(app, t.steps, running = !t.done)
        t.approvals.forEach { ApprovalCard(it, enabled = enabled && it.state == "pending", onDecide = onDecide) }
        val shown = if (t.done) t.answer else liveText
        if (shown.isNotBlank()) Bubble(ChatItem.Msg("assistant", shown))
    }
}

/** All steps of one turn in a single collapsible box; header = the newest running step while the agent works. */
@Composable
private fun StepsDropdown(app: AppCtx, steps: List<ChatItem>, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val boxes = steps.filterIsInstance<ChatItem.Step>()
    val current = boxes.lastOrNull { it.result == null }
    val failed = boxes.count { it.ok == false }
    val header = when {
        running && current != null -> current.title.ifBlank { current.name }
        running -> "Thinking…"
        else -> "${boxes.size} step${if (boxes.size == 1) "" else "s"}" + (if (failed > 0) " · $failed failed" else "")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(kindIcon(current?.kind ?: "tool"), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(header, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                fontFamily = if (running && current?.kind == "shell") FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Collapse" else "Expand")
        }
        if (expanded) Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { s ->
                when (s) {
                    is ChatItem.Step -> StepBox(app, s)
                    is ChatItem.Msg -> Box(Modifier.padding(horizontal = 14.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { Markdown(s.text) }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatItem.Msg) {
    val mine = m.role == "user"
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(Modifier.widthIn(max = if (mine) 300.dp else 360.dp).background(bg, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            CompositionLocalProvider(LocalContentColor provides fg) {
                if (mine) SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium, color = fg) }
                else SelectionContainer { Markdown(m.text.ifBlank { "…" }) }
            }
        }
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "shell" -> Icons.Filled.Terminal
    "file" -> Icons.Filled.Description
    "git" -> Icons.Filled.AccountTree
    "github" -> Icons.Filled.Code
    "subagent" -> Icons.Filled.SmartToy
    "todos" -> Icons.Filled.Checklist
    else -> Icons.Filled.Build
}

@Composable
private fun StepBox(app: AppCtx, s: ChatItem.Step) {
    var expanded by remember { mutableStateOf(false) }
    val failed = s.ok == false
    val border = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .border(1.dp, border, RoundedCornerShape(10.dp))
        .background(if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
        .clickable { expanded = !expanded }
        .padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(s.kind), s.kind, Modifier.size(18.dp), tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title.ifBlank { s.name }, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (s.kind == "shell") FontFamily.Monospace else FontFamily.Default,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(listOfNotNull(s.kind, s.name.takeIf { it != s.kind }, s.exitCode?.let { "exit $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                s.result == null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                failed -> Icon(Icons.Filled.Error, "failed", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Filled.CheckCircle, "ok", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        s.url?.let { u -> TextButton(onClick = { app.openUrl(u) }, contentPadding = PaddingValues(0.dp)) { Text("Open ${if ("/pull/" in u) "pull request" else "link"}") } }
        if (expanded) {
            if (s.args.isNotBlank()) Mono("input", s.args)
            s.result?.takeIf { it.isNotBlank() }?.let { Mono("output", it) }
        }
    }
}

@Composable
private fun Mono(label: String, text: String) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    SelectionContainer {
        Text(text.take(6000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(6.dp))
    }
}

@Composable
private fun PlanPanel(todos: List<TodoItem>) {
    var expanded by remember { mutableStateOf(true) }
    val done = todos.count { it.status == "completed" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
        .clickable { expanded = !expanded }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Checklist, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Plan · $done/${todos.size} done", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (expanded) todos.forEach { t ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(when (t.status) {
                    "completed" -> Icons.Filled.CheckCircle
                    "in_progress" -> Icons.Filled.PlayCircle
                    else -> Icons.Filled.RadioButtonUnchecked
                }, t.status, Modifier.size(16.dp).padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.content, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (t.status == "in_progress") FontWeight.SemiBold else FontWeight.Normal,
                    textDecoration = if (t.status == "completed") TextDecoration.LineThrough else TextDecoration.None)
            }
        }
    }
}

@Composable
private fun ApprovalCard(a: ChatItem.Approval, enabled: Boolean, onDecide: (Boolean, String?) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var rejecting by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Gavel, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Approval needed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (a.text.isNotBlank()) Markdown(a.text)
            a.actions.forEach { act ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(act.title.ifBlank { act.name }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                act.args.forEach { (k, v) ->
                    Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    if (k == "body" || k == "text" || k == "comment") Markdown(v)
                    else SelectionContainer { Text(v, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (a.state == "pending") {
                if (rejecting) OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("Why? (optional, the agent sees this)") }, maxLines = 4)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDecide(true, null) }, enabled = enabled && !rejecting) { Text("Approve") }
                    OutlinedButton(onClick = { if (rejecting) onDecide(false, reason) else rejecting = true }, enabled = enabled) {
                        Text(if (rejecting) "Send rejection" else "Reject")
                    }
                    if (rejecting) TextButton(onClick = { rejecting = false; reason = "" }) { Text("Cancel") }
                }
            } else Text(a.state.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
