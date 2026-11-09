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
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var pickModel by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<ChatThread?>(null) }

    LaunchedEffect(reloadKey, tick) {
        loading = true
        try {
            val (ms, _) = app.agent.health(); models.clear(); models.addAll(ms)
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
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(onClick = { pickModel = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New chat") })
            DropdownMenu(expanded = pickModel, onDismissRequest = { pickModel = false }) {
                (models.ifEmpty { listOf("") }).forEach { m ->
                    DropdownMenuItem(text = { Text(if (m.isBlank()) "Default model" else m.substringAfter('/')) }, onClick = {
                        pickModel = false
                        app.run(null, { app.agent.newThread(m.ifBlank { null }) }) { onOpen(it.id) }
                    })
                }
            }
        }
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

/** Folds one event (stream: key "type"; history row: key "role") into the chat list. */
private fun fold(items: MutableList<ChatItem>, o: JSONObject, kind: String, todos: (List<TodoItem>) -> Unit, answer: IntArray) {
    when (kind) {
        "user" -> { items.add(ChatItem.Msg("user", o.optString("text"))); answer[0] = -1 }
        "assistant" -> { items.add(ChatItem.Msg("assistant", o.optString("text"))); answer[0] = -1 }
        "token" -> {
            val i = answer[0]
            if (i < 0 || i >= items.size || items[i] !is ChatItem.Msg) {
                items.add(ChatItem.Msg("assistant", o.optString("text"))); answer[0] = items.size - 1
            } else {
                val m = items[i] as ChatItem.Msg
                items[i] = m.copy(text = m.text + o.optString("text"))
            }
        }
        "done" -> {
            val text = o.optString("text")
            val i = answer[0]
            if (i >= 0 && i < items.size && items[i] is ChatItem.Msg) items[i] = (items[i] as ChatItem.Msg).copy(text = text.ifBlank { (items[i] as ChatItem.Msg).text })
            else if (text.isNotBlank() && (items.lastOrNull() as? ChatItem.Msg)?.let { it.role == "assistant" && it.text.trim() == text.trim() } != true)
                items.add(ChatItem.Msg("assistant", text))
            answer[0] = -1
        }
        "tool_call" -> {
            val st = stepFromCall(o)
            if (st.kind != "todos") items.add(st)
            answer[0] = -1
        }
        "tool_result" -> {
            val id = o.optString("id")
            val i = items.indexOfLast { it is ChatItem.Step && it.id == id }
            if (i >= 0) items[i] = (items[i] as ChatItem.Step).withResult(o)
            else if (o.optString("kind") != "todos")
                items.add(ChatItem.Step(id, o.optString("name"), o.optString("kind", "tool"), o.optString("name"), "").withResult(o))
            answer[0] = -1
        }
        "todos" -> todos(parseTodos(o))
        "approval" -> {
            val ap = approvalFrom(o)
            if (items.none { it is ChatItem.Approval && it.id == ap.id }) items.add(ap)
            answer[0] = -1
        }
    }
}

@Composable
private fun ThreadView(app: AppCtx, id: String, onBack: () -> Unit) {
    val items = remember { mutableStateListOf<ChatItem>() }
    var todos by remember { mutableStateOf<List<TodoItem>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val answer = remember { intArrayOf(-1) }

    LaunchedEffect(id) {
        try {
            val (t, rows, pending) = app.agent.thread(id)
            title = t.title; model = t.model; items.clear(); answer[0] = -1
            rows.forEach { fold(items, it, it.optString("role"), { l -> todos = l }, answer) }
            pending.forEach { fold(items, it, "approval", { l -> todos = l }, answer) }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(items.size, (items.lastOrNull() as? ChatItem.Msg)?.text?.length) {
        if (items.isNotEmpty()) list.scrollToItem(items.size - 1)
    }

    fun run(block: suspend ((JSONObject) -> Unit) -> Unit) {
        busy = true; error = null; answer[0] = -1
        app.scope.launch {
            try {
                block { ev ->
                    val type = ev.optString("type")
                    if (type == "error") error = ev.optString("text") else fold(items, ev, type, { l -> todos = l }, answer)
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            busy = false
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        items.add(ChatItem.Msg("user", text))
        if (title == "New chat") title = text.take(60)
        // a plain message while an approval is pending answers it (server side: "yes" approves, else rejects)
        items.replaceAll { if (it is ChatItem.Approval && it.state == "pending") it.copy(state = "answered in chat") else it }
        run { onEv -> app.agent.chat(id, text, onEv) }
    }

    fun decide(ap: ChatItem.Approval, approve: Boolean, reason: String?) {
        if (busy) return
        val i = items.indexOf(ap)
        if (i >= 0) items[i] = ap.copy(state = if (approve) "approved" else "rejected")
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
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (todos.isNotEmpty()) PlanPanel(todos)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.size) { i ->
                when (val it = items[i]) {
                    is ChatItem.Msg -> Bubble(it)
                    is ChatItem.Step -> StepBox(app, it)
                    is ChatItem.Approval -> ApprovalCard(it, enabled = !busy) { ok, reason -> decide(it, ok, reason) }
                }
            }
            error?.let { item { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) } }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask…") }, maxLines = 6)
            IconButton(onClick = { send() }, enabled = !busy && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatItem.Msg) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(Modifier.widthIn(max = if (mine) 300.dp else 360.dp)
            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (mine) SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
            else SelectionContainer { Markdown(m.text.ifBlank { "…" }) }
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
