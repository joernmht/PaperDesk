package com.joernmht.paperdesk.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableIntStateOf
import com.joernmht.paperdesk.AppCtx
import com.joernmht.paperdesk.ConflictException
import com.joernmht.paperdesk.FileDoc
import com.joernmht.paperdesk.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/** What to open in the editor; [openComment] = open the comment found at line:col (from the Comments list). */
data class EditorTarget(val paperId: String, val paperLabel: String, val path: String, val line: Int = 0, val col: Int = 0,
                        val openComment: Boolean = false)

/** The editor is full screen over the tabs; set [target] to open it. */
object EditorNav {
    val target = mutableStateOf<EditorTarget?>(null)
}

/** A comment the user tapped in the editor (reported by editor.js). */
private data class CommentTap(val kind: String, val from: Int, val marked: String?, val comment: String?, val readOnly: Boolean,
                              val opt: String?, val line: Int) {
    val isMark get() = marked != null
    val title get() = when (kind) {
        "pdmark" -> "Comment"
        "pdnote" -> "Note"
        "rmnote" -> "reMarkable note" + (opt?.let { " ($it)" } ?: "")
        "rmhl" -> "reMarkable highlight"
        "rmdel" -> "reMarkable: delete this?"
        else -> kind
    }
    val form get() = if (readOnly) FormKind.Square else FormKind.Circle

    companion object {
        fun from(j: String) = JSONObject(j).let {
            CommentTap(it.optString("kind"), it.optInt("from"), if (it.isNull("marked")) null else it.optString("marked"),
                if (it.isNull("comment")) null else it.optString("comment"), it.optBoolean("readOnly"),
                if (it.isNull("opt")) null else it.optString("opt"), it.optInt("line"))
        }
    }
}

/** Unsaved text, kept in the app's files until it is saved or discarded (survives the process being killed). */
private class Drafts(ctx: android.content.Context) {
    private val dir = File(ctx.filesDir, "drafts").apply { mkdirs() }
    private fun f(t: EditorTarget) = File(dir, (t.paperId + "__" + t.path).replace(Regex("[^\\w.-]"), "_") + ".json")
    fun load(t: EditorTarget): Pair<String, String>? = runCatching {
        JSONObject(f(t).readText()).let { it.getString("base_sha") to it.getString("text") }
    }.getOrNull()
    fun save(t: EditorTarget, baseSha: String, text: String) = runCatching {
        f(t).writeText(JSONObject().put("base_sha", baseSha).put("text", text).toString())
    }
    fun drop(t: EditorTarget) { f(t).delete() }
}

private fun q(s: String?) = if (s == null) "null" else JSONObject.quote(s)

private suspend fun WebView.js(expr: String): String? = suspendCancellableCoroutine { k ->
    evaluateJavascript(expr) { r -> k.resume(runCatching { JSONTokener(r).nextValue() as? String }.getOrNull()) }
}

/** Calls from editor.js arrive on a binder thread; everything is handed to the main thread. */
private class Bridge(val onReady: () -> Unit, val onChange: (Boolean) -> Unit, val onSelection: (Boolean) -> Unit,
                     val onDraft: (String) -> Unit, val onTap: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun onReady() { main.post(onReady) }
    @JavascriptInterface fun onChange(dirty: Boolean) { main.post { onChange(dirty) } }
    @JavascriptInterface fun onSelection(has: Boolean) { main.post { onSelection(has) } }
    @JavascriptInterface fun onDraft(text: String) { main.post { onDraft(text) } }
    @JavascriptInterface fun onCommentTap(json: String) { main.post { onTap(json) } }
}

/** Symbol bar: label → (inserted before the selection, after it). */
private val SYMBOLS = listOf("\\" to ("\\" to ""), "{ }" to ("{" to "}"), "$ $" to ("$" to "$"), "%" to ("%" to ""),
    "&" to ("&" to ""), "_" to ("_" to ""), "^" to ("^" to ""), "~" to ("~" to ""),
    "\\cite{}" to ("\\cite{" to "}"), "\\ref{}" to ("\\ref{" to "}"))

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(app: AppCtx, target: EditorTarget, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts = remember { Drafts(ctx) }
    val dark = isSystemInDarkTheme()

    var doc by remember { mutableStateOf<FileDoc?>(null) }
    var baseSha by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }       // text handed to the editor
    var dirty by remember { mutableStateOf(false) }
    var hasSel by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showSource by remember { mutableStateOf(false) }
    var font by remember { mutableIntStateOf(Prefs.editorFont(ctx)) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var tap by remember { mutableStateOf<CommentTap?>(null) }
    var askComment by remember { mutableStateOf<CommentTap?>(null) }   // null kind = new comment
    var newComment by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<ConflictException?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmDeleteText by remember { mutableStateOf<CommentTap?>(null) }
    var staleDraft by remember { mutableStateOf<Pair<String, String>?>(null) }

    val web = remember {
        WebView(ctx).apply {
            // Without explicit layout params AndroidView leaves the WebView on WRAP_CONTENT, which makes Chromium size the
            // page viewport to its content: html/body/#ed { height: 100% } collapse to 0 and CodeMirror draws no lines.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {   // the editor never navigates anywhere
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            addJavascriptInterface(Bridge(
                onReady = { ready = true },
                onChange = { dirty = it; if (it) status = null },
                onSelection = { hasSel = it },
                onDraft = { t -> if (baseSha.isNotEmpty()) drafts.save(target, baseSha, t) },
                onTap = { j -> tap = CommentTap.from(j) },
            ), "Android")
            loadUrl("file:///android_asset/editor/index.html")
        }
    }
    DisposableEffect(Unit) { onDispose { web.destroy() } }

    fun show(text: String, clean: String, line: Int = target.line, col: Int = target.col, openComment: Boolean = target.openComment) {
        web.evaluateJavascript("pd.setDark($dark); pd.setFontSize($font); pd.setShowSource($showSource);" +
            "pd.load(${q(text)}, ${q(clean)}, $line, $col);" + (if (openComment) "pd.openCommentAt($line, $col);" else ""), null)
        shown = true
    }

    // load the file; a draft for the same version is restored silently, one for an older version is offered
    LaunchedEffect(target) {
        try {
            val d = app.api.paperFile(target.paperId, target.path)
            doc = d; baseSha = d.sha; loadError = null
            val dr = drafts.load(target)
            if (dr != null && dr.second != d.text && dr.first != d.sha) staleDraft = dr
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(ready, doc, staleDraft) {
        val d = doc ?: return@LaunchedEffect
        if (!ready || shown || staleDraft != null) return@LaunchedEffect
        val dr = drafts.load(target)
        if (dr != null && dr.first == d.sha && dr.second != d.text) {
            show(dr.second, d.text); app.toast("Restored your unsaved changes")
        } else show(d.text, d.text)
    }
    LaunchedEffect(dark, ready) { if (ready) web.evaluateJavascript("pd.setDark($dark)", null) }

    suspend fun text() = web.js("pd.getText()") ?: ""

    fun save(base: String = baseSha) {
        val d = doc ?: return
        saving = true; status = null
        web.evaluateJavascript("pd.setReadOnly(true)", null)
        scope.launch {
            try {
                val r = app.api.saveFile(target.paperId, target.path, text(), base)
                baseSha = r.sha
                drafts.drop(target)
                web.evaluateJavascript("pd.markClean()", null)
                status = "● Saved · commit ${r.commit.take(7)}" + (if (r.pushed) " · pushed" else " · not pushed yet") +
                    (r.note?.let { " · $it" } ?: "")
                doc = d.copy(sha = r.sha)
            } catch (c: ConflictException) {
                conflict = c
            } catch (e: Exception) {
                status = "■ Not saved: ${e.message ?: e.javaClass.simpleName}"
            }
            saving = false
            web.evaluateJavascript("pd.setReadOnly(false)", null)
        }
    }

    fun close() { drafts.drop(target); onClose() }

    BackHandler {
        when {
            tap != null -> tap = null
            searching -> { searching = false; web.evaluateJavascript("pd.find('', false)", null) }
            dirty -> confirmClose = true
            else -> close()
        }
    }

    fun runJs(expr: String, ok: () -> Unit = {}) = scope.launch {
        val err = web.js(expr)
        if (!err.isNullOrBlank()) problem = err else ok()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (dirty) confirmClose = true else close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(target.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dirty) { Form(FormKind.Triangle, 10.dp, label = "unsaved"); Spacer(Modifier.width(4.dp)) }
                    Text(if (dirty) "Unsaved changes" else target.paperLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { searching = !searching }) { Icon(Icons.Filled.Search, "Search") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Show source") }, onClick = {
                        showSource = !showSource; web.evaluateJavascript("pd.setShowSource($showSource)", null)
                    }, trailingIcon = { Checkbox(showSource, null) })
                    DropdownMenuItem(text = { Text("Larger text") }, onClick = {
                        font = (font + 1).coerceAtMost(28); Prefs.setEditorFont(ctx, font); web.evaluateJavascript("pd.setFontSize($font)", null)
                    })
                    DropdownMenuItem(text = { Text("Smaller text") }, onClick = {
                        font = (font - 1).coerceAtLeast(10); Prefs.setEditorFont(ctx, font); web.evaluateJavascript("pd.setFontSize($font)", null)
                    })
                    DropdownMenuItem(text = { Text("Undo") }, onClick = { menu = false; web.evaluateJavascript("pd.undo()", null) })
                }
            }
            BFilledButton(onClick = { save() }, enabled = dirty && !saving && doc != null) { Text("Save") }
        }
        if (searching) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; web.evaluateJavascript("pd.find(${q(it)}, false)", null) }, Modifier.weight(1f),
                placeholder = { Text("Find in file") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { web.evaluateJavascript("pd.find(${q(query)}, false)", null) }))
            IconButton(onClick = { web.evaluateJavascript("pd.find(${q(query)}, true)", null) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { web.evaluateJavascript("pd.find(${q(query)}, false)", null) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
            IconButton(onClick = { searching = false; query = ""; web.evaluateJavascript("pd.find('', false)", null) }) { Icon(Icons.Filled.Close, "Close search") }
        }
        if (saving || (doc == null && loadError == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
        Hairline()

        // ---- the editor
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView({ web }, Modifier.fillMaxSize())
            loadError?.let {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    ErrorLine("Cannot open ${target.path}: $it", Modifier)
                    BOutlinedButton(onClick = onClose) { Text("Back") }
                }
            }
        }

        // ---- comment button + symbol bar (sits on top of the keyboard)
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            BOutlinedButton(form = FormKind.Circle, enabled = shown, onClick = {
                scope.launch {
                    val why = web.js("pd.checkComment()")
                    if (!why.isNullOrBlank()) problem = why else newComment = true
                }
            }) { Text(if (hasSel) "Comment" else "Note") }
            Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                SYMBOLS.forEach { (label, ins) ->
                    val (before, after) = ins
                    Box(Modifier.heightIn(min = 48.dp).clickable(enabled = shown) {
                        web.evaluateJavascript("pd.snippet(${q(before)}, ${q(after)})", null)
                    }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    // ---- comment popup: one-handed bottom sheet
    tap?.let { c ->
        ModalBottomSheet(onDismissRequest = { tap = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = androidx.compose.ui.graphics.RectangleShape, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Form(c.form, 14.dp); Spacer(Modifier.width(8.dp))
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text("line ${c.line}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                c.marked?.takeIf { it.isNotBlank() }?.let {
                    Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 4,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.background(BYellow.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                c.comment?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (c.readOnly) Text("Written on the reMarkable. It can be resolved, not edited.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Hairline()
                if (!c.readOnly && c.comment != null) BOutlinedButton(form = FormKind.Triangle, modifier = Modifier.fillMaxWidth(), onClick = {
                    askComment = c; tap = null
                }) { Text("Edit comment", Modifier.weight(1f)) }
                BFilledButton(form = FormKind.Circle, modifier = Modifier.fillMaxWidth(), onClick = {
                    tap = null; runJs("pd.resolve(${c.from}, ${q(c.kind)})")
                }) { Text(if (c.isMark) "Resolve (keep the text)" else "Resolve (remove the note)", Modifier.weight(1f)) }
                if (c.isMark) BOutlinedButton(form = FormKind.Square, modifier = Modifier.fillMaxWidth(), onClick = {
                    confirmDeleteText = c; tap = null
                }) { Text(if (c.kind == "rmdel") "Delete the text (as marked)" else "Delete comment and text", Modifier.weight(1f)) }
                TextButton(onClick = { tap = null }, Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }

    // ---- new / edited comment text
    if (newComment || askComment != null) {
        val editing = askComment
        var t by remember(editing) { mutableStateOf(editing?.comment ?: "") }
        AlertDialog(
            onDismissRequest = { newComment = false; askComment = null },
            title = { Text(if (editing != null) "Edit comment" else if (hasSel) "Comment on the selection" else "Note at the cursor") },
            text = {
                OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), placeholder = { Text("Your comment") }, minLines = 3)
            },
            confirmButton = {
                BFilledButton(form = FormKind.Circle, enabled = t.isNotBlank(), onClick = {
                    val e = editing
                    newComment = false; askComment = null
                    runJs(if (e != null) "pd.editComment(${e.from}, ${q(e.kind)}, ${q(t)})" else "pd.addComment(${q(t)})")
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { newComment = false; askComment = null }) { Text("Cancel") } },
        )
    }

    problem?.let { p ->
        AlertDialog(onDismissRequest = { problem = null }, title = { Text("Not possible here") },
            text = { ErrorLine(p, Modifier) }, confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } })
    }

    confirmDeleteText?.let { c ->
        AlertDialog(onDismissRequest = { confirmDeleteText = null }, title = { Text("Delete the marked text?") },
            text = { Text("Removes “${c.marked?.take(200)}” from the paper together with the comment. Undo is in the ⋮ menu.") },
            confirmButton = { TextButton(onClick = { confirmDeleteText = null; runJs("pd.deleteMarked(${c.from}, ${q(c.kind)})") }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteText = null }) { Text("Cancel") } })
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Discard changes?") },
        text = { Text("Your edits to ${target.path} are not saved.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; close() }) { Text("Discard") } },
        dismissButton = { BFilledButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
    )

    conflict?.let { c ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Changed on the server since you opened it") },
            text = { Text("Someone (or a job on the server) changed ${target.path} after you opened it. Load their version and lose your edits, or save yours over theirs.") },
            confirmButton = {
                BFilledButton(onClick = { conflict = null; save(c.theirSha) }) { Text("Keep mine") }
            },
            dismissButton = {
                TextButton(onClick = {
                    conflict = null
                    baseSha = c.theirSha; doc = doc?.copy(text = c.theirText, sha = c.theirSha); drafts.drop(target)
                    show(c.theirText, c.theirText, 0, 0, false)
                }) { Text("Reload theirs") }
            },
        )
    }

    staleDraft?.let { (oldSha, text) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Unsaved changes from earlier") },
            text = { Text("You edited an older version of ${target.path} and did not save. Keep those edits (saving will ask how to combine them with the newer version), or throw them away?") },
            confirmButton = {
                BFilledButton(onClick = {
                    staleDraft = null; baseSha = oldSha
                    show(text, doc?.text ?: text)
                }) { Text("Keep my edits") }
            },
            dismissButton = { TextButton(onClick = { staleDraft = null; drafts.drop(target) }) { Text("Discard") } },
        )
    }
}

/** "file.tex:12" for list rows. */
fun commentWhere(path: String, line: Int) = "${path.substringAfterLast('/')}:$line"
