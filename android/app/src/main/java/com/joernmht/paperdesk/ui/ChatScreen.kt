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
import com.joernmht.paperdesk.ChatMsg
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

@Composable
private fun ThreadView(app: AppCtx, id: String, onBack: () -> Unit) {
    val msgs = remember { mutableStateListOf<ChatMsg>() }
    var title by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    LaunchedEffect(id) {
        try {
            val (t, m) = app.agent.thread(id); title = t.title; model = t.model; msgs.clear(); msgs.addAll(m)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text?.length) {
        if (msgs.isNotEmpty()) list.scrollToItem(msgs.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""; busy = true; error = null
        msgs.add(ChatMsg("user", text))
        var answerIdx = -1
        app.scope.launch {
            try {
                app.agent.chat(id, text) { ev ->
                    when (ev.type) {
                        "token" -> {
                            if (answerIdx < 0) { msgs.add(ChatMsg("assistant", "")); answerIdx = msgs.size - 1 }
                            msgs[answerIdx] = msgs[answerIdx].copy(text = msgs[answerIdx].text + ev.text)
                        }
                        "tool_call", "tool_result" -> { msgs.add(ChatMsg(ev.type, ev.text, ev.name)); answerIdx = -1 }
                        "done" -> {
                            // tokens may not stream for every model: make sure the final answer is shown
                            val last = msgs.lastOrNull()
                            if (ev.text.isNotBlank() && !(last?.role == "assistant" && last.text.trim() == ev.text.trim()))
                                if (answerIdx >= 0) msgs[answerIdx] = msgs[answerIdx].copy(text = ev.text)
                                else msgs.add(ChatMsg("assistant", ev.text))
                        }
                        "error" -> error = ev.text
                        else -> msgs.add(ChatMsg("tool_result", ev.text, ev.type))   // future event types (e.g. approval)
                    }
                }
                if (title == "New chat") title = text.take(60)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            busy = false
        }
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(msgs.size) { i -> Bubble(msgs[i]) }
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
private fun Bubble(m: ChatMsg) {
    when (m.role) {
        "user", "assistant" -> {
            val mine = m.role == "user"
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                SelectionContainer {
                    Text(m.text.ifBlank { "…" },
                        Modifier.widthIn(max = 320.dp)
                            .background(
                                if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        else -> {
            var expanded by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { expanded = !expanded },
                verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Build, null, Modifier.padding(top = 2.dp, end = 6.dp), tint = MaterialTheme.colorScheme.outline)
                Text((if (m.role == "tool_call") "→ " else "← ") + (m.name ?: "tool") + "  " + m.text,
                    maxLines = if (expanded) 40 else 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
