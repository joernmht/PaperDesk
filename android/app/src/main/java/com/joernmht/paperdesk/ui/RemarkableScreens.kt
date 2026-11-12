package com.joernmht.paperdesk.ui

import com.joernmht.paperdesk.Decision

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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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

// ---------- Activity ----------

/** ● finished well, ■ new papers or something broke, ▲ any other news from the server. */
private fun eventForm(e: Event): FormKind = when {
    e.kind == "crash" || e.title.startsWith("❌") -> FormKind.Square
    e.kind == "scout" || e.kind == "watch" -> FormKind.Square
    e.title.startsWith("✅") -> FormKind.Circle
    else -> FormKind.Triangle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(app: AppCtx, reloadKey: Int) {
    val jobs = remember { mutableStateListOf<Job>() }
    val events = remember { mutableStateListOf<Event>() }
    val decisions = remember { mutableStateListOf<Decision>() }
    var stats by remember { mutableStateOf("") }
    var showAllDecisions by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf<Job?>(null) }

    suspend fun load() {
        try {
            val j = app.api.jobs(); jobs.clear(); jobs.addAll(j)
            val (ev, _) = app.api.events(0); events.clear(); events.addAll(ev.sortedByDescending { it.seq })
            val (ds, st) = app.api.decisions(); decisions.clear(); decisions.addAll(ds); stats = st
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
            error?.let { item { ErrorLine("Cannot reach the server: $it") } }
            item { Header("Jobs") }
            if (jobs.isEmpty()) item { Text("No jobs yet.", Modifier.padding(16.dp)) }
            items(jobs, key = { "j:" + it.id }) { j ->
                ListItem(
                    headlineContent = { Text(j.title) },
                    supportingContent = { Text(listOfNotNull(j.status, j.finished ?: j.started ?: j.created, j.summary).joinToString(" · "), maxLines = 3) },
                    leadingContent = { Form(statusForm(j.status), 16.dp, label = j.status) },
                    modifier = Modifier.clickable {
                        shown = j
                        app.run(null, { app.api.job(j.id) }) { shown = it }
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)); Header("Decisions") }
            if (stats.isNotBlank()) item { Text(stats, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            if (decisions.isEmpty()) item { Text("No decisions yet.", Modifier.padding(16.dp)) }
            items(if (showAllDecisions) decisions else decisions.take(8), key = { "d:" + it.ts + it.title + it.action }) { d ->
                ListItem(
                    leadingContent = { Form(actionForm(d.action), 12.dp, label = d.action) },
                    overlineContent = { Text("${d.label} · ${d.ts.replace('T', ' ').take(16)}") },
                    headlineContent = { Text(d.title, maxLines = 2) },
                )
            }
            if (decisions.size > 8 && !showAllDecisions) item {
                TextButton(onClick = { showAllDecisions = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Show all ${decisions.size}")
                }
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)); Header("Updates") }
            if (events.isEmpty()) item { Text("No updates yet.", Modifier.padding(16.dp)) }
            items(events, key = { "e:" + it.seq }) { e ->
                ListItem(
                    leadingContent = { Form(eventForm(e), 12.dp, label = e.kind) },
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Form(statusForm(j.status), 12.dp, Modifier.padding(end = 8.dp), label = j.status)
                        Text("${j.status} · ${j.kind}", style = MaterialTheme.typography.labelMedium)
                    }
                    j.summary?.let { Text(it, Modifier.padding(vertical = 6.dp)) }
                    Text(j.logTail ?: "(no log)", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Close") } },
        )
    }
}
