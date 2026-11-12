package com.joernmht.paperdesk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.joernmht.paperdesk.ui.ActivityScreen
import com.joernmht.paperdesk.ui.BFilledButton
import com.joernmht.paperdesk.ui.BOutlinedButton
import com.joernmht.paperdesk.ui.ChatScreen
import com.joernmht.paperdesk.ui.Form
import com.joernmht.paperdesk.ui.FormKind
import com.joernmht.paperdesk.ui.FormMark
import com.joernmht.paperdesk.ui.InboxScreen
import com.joernmht.paperdesk.ui.InkRule
import com.joernmht.paperdesk.ui.PaperDeskTheme
import com.joernmht.paperdesk.ui.RemarkableScreen
import com.joernmht.paperdesk.ui.SearchScreen
import com.joernmht.paperdesk.ui.SetupFlow
import com.joernmht.paperdesk.ui.SetupWizard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Shared helpers handed to every screen. */
class AppCtx(val ctx: Context, val api: Api, val agent: AgentApi, val snackbar: SnackbarHostState, val scope: CoroutineScope,
             val info: ServerInfo = ServerInfo.LEGACY) {
    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    /** Runs a server call, shows errors as a snackbar; a started job triggers the quick-poll burst. */
    fun <T> run(label: String? = null, block: suspend () -> T, onErr: () -> Unit = {}, onOk: (T) -> Unit = {}) {
        scope.launch {
            val r = try {
                block()
            } catch (e: Exception) {
                onErr()
                toast("Failed: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            onOk(r)
            label?.let { toast(it) }
        }
    }

    fun jobStarted(job: Job?) {
        if (job != null) {
            PollWorker.burst(ctx)
            toast("Started: ${job.title}")
        }
    }

    fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("No app to open $url")
        }
    }

    fun openPdf(f: File) {
        val uri = FileProvider.getUriForFile(ctx, "com.joernmht.paperdesk.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent.createChooser(i, "Open PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("No PDF viewer installed")
        }
    }

    fun openAnnotated(name: String) = run(null, { api.downloadAnnotated(name) }) { openPdf(it) }
}

enum class Tab(val key: String, val label: String, val icon: ImageVector) {
    Inbox("inbox", "Inbox", Icons.Filled.Inbox),
    Search("search", "Search", Icons.Filled.Search),
    Remarkable("remarkable", "reMarkable", Icons.Filled.Edit),
    Chat("chat", "Chat", Icons.Filled.Forum),
    Activity("activity", "Activity", Icons.AutoMirrored.Filled.List),
}

/** A pairing link that arrived (deep link or pasted) and waits for the user's "Connect". */
object PairNav {
    val pending = mutableStateOf<Pairing?>(null)
}

class MainActivity : ComponentActivity() {
    private val tabState = mutableStateOf(Tab.Inbox)

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PollWorker.ensureChannel(this)
        if (Prefs.setupStep(this) < 0) {   // paired and through the wizard; until then the app stays silent
            PollWorker.schedulePeriodic(this)
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        setContent { PaperDeskTheme { App(tabState) { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) {
            Pairing.parse(i.dataString)?.let { PairNav.pending.value = it }
            i.data = null   // a rotation must not ask again
            return
        }
        Tab.entries.firstOrNull { it.key == i?.getStringExtra(PollWorker.EXTRA_TAB) }?.let { tabState.value = it }
        i?.getStringExtra(ChatNav.EXTRA_THREAD)?.let { ChatNav.open.value = it }
    }
}

@Composable
fun App(tabState: androidx.compose.runtime.MutableState<Tab>, onPaired: () -> Unit = {}) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var settingsVersion by remember { mutableStateOf(0) }   // bump → screens reload with new prefs
    val configured = remember(settingsVersion) { Prefs.configured(ctx) }
    var info by remember(settingsVersion) { mutableStateOf(Prefs.serverInfo(ctx)) }
    val app = remember(settingsVersion, info) {
        AppCtx(ctx.applicationContext, Api(ctx.applicationContext), AgentApi(ctx.applicationContext), snackbar, scope, info)
    }
    var showSettings by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf(false) }
    val pairing by PairNav.pending
    var setupStep by remember(settingsVersion) { mutableStateOf(Prefs.setupStep(ctx)) }
    val solo = remember(settingsVersion, setupStep) { Prefs.setupSolo(ctx) }
    val inSetup = setupStep >= 0

    fun gotoStep(step: Int, alone: Boolean = solo) {
        val leaving = step < 0 && setupStep >= 0
        Prefs.setSetupStep(ctx, step, alone)
        setupStep = Prefs.setupStep(ctx)
        if (leaving && Prefs.configured(ctx)) {   // the wizard is over: the desk goes live
            PollWorker.schedulePeriodic(ctx)
            onPaired()
            settingsVersion++
        }
    }

    LaunchedEffect(settingsVersion) {
        update = false
        if (!configured) return@LaunchedEffect
        try {
            val h = app.api.health()
            Prefs.setServerInfo(ctx, h)
            info = ServerInfo.from(h)
            update = h.optInt("apk_version", 0) > BuildConfig.VERSION_CODE
        } catch (_: Exception) {
        }
    }

    val tabs = Tab.entries.filter {
        when (it) {
            Tab.Remarkable -> info.remarkable
            Tab.Chat -> info.agent && Prefs.agentUrl(ctx).isNotBlank()
            else -> true
        }
    }
    val tab = tabState.value.takeIf { it in tabs } ?: Tab.Inbox

    Scaffold(
        topBar = { TopBar(if (inSetup) "Setup" else tab.label, if (inSetup) "" else info.name.ifBlank { Prefs.name(ctx) }) { showSettings = true } },
        bottomBar = { if (!inSetup) BottomBar(tabs, tab) { tabState.value = it } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (inSetup) SetupWizard(app, setupStep, solo, configured, goto = { gotoStep(it) },
                onPaired = { p, newInfo ->
                    Prefs.setSetupStep(ctx, SetupFlow.next(SetupFlow.CONNECT, newInfo, false))
                    settingsVersion++
                    scope.launch { snackbar.showSnackbar("Connected to ${p.display}") }
                },
                refresh = { settingsVersion++ })
            else {
                if (update) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer)
                        .clickable { app.openUrl(app.api.apkUrl()) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Form(FormKind.Triangle, 14.dp, label = "update")
                        Spacer(Modifier.width(8.dp))
                        Text("Update available. Tap to download the new APK", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    InkRule()
                }
                when (tab) {
                    Tab.Inbox -> InboxScreen(app, settingsVersion)
                    Tab.Search -> SearchScreen(app)
                    Tab.Remarkable -> RemarkableScreen(app, settingsVersion)
                    Tab.Chat -> ChatScreen(app, settingsVersion)
                    Tab.Activity -> ActivityScreen(app, settingsVersion)
                }
            }
        }
    }

    if (showSettings) SettingsDialog(info, onDismiss = { showSettings = false },
        onSetup = { step, alone -> showSettings = false; gotoStep(step, alone) },
        onSaved = {
            showSettings = false
            if (Prefs.setupStep(ctx) < 0) PollWorker.schedulePeriodic(ctx) else PollWorker.cancelAll(ctx)
            settingsVersion++
        })

    // A pairing link from outside never connects by itself: any web page can open one, so the user confirms the server.
    pairing?.let { p ->
        AlertDialog(
            onDismissRequest = { PairNav.pending.value = null },
            title = { Text("Connect to ${p.display}?") },
            text = {
                Column {
                    Text(p.url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    p.agent?.let { Text("Chat agent: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        if (configured) "This replaces the desk PaperDesk is connected to now. Only connect to a machine you set up yourself."
                        else "PaperDesk will send its requests, with the access token from the link, to this machine. Only connect to a machine you set up yourself.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                BFilledButton(form = FormKind.Circle, onClick = {
                    val wasInSetup = inSetup
                    Prefs.pair(ctx, p)
                    PairNav.pending.value = null
                    showSettings = false
                    scope.launch {
                        if (wasInSetup) {
                            // carry on with the wizard's optional steps, as after a scanned code
                            val newInfo = try {
                                app.api.health().also { Prefs.setServerInfo(ctx, it) }.let(ServerInfo::from)
                            } catch (_: Exception) {
                                ServerInfo.LEGACY
                            }
                            Prefs.setSetupStep(ctx, SetupFlow.next(SetupFlow.CONNECT, newInfo, false))
                        } else {
                            PollWorker.schedulePeriodic(ctx)
                            onPaired()
                        }
                        settingsVersion++
                        snackbar.showSnackbar("Connected to ${p.display}")
                    }
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { PairNav.pending.value = null }) { Text("Cancel") } },
        )
    }
}

/** Flat top bar: the mark, the name, the current place; a 2dp ink rule below. */
@Composable
private fun TopBar(place: String, desk: String, onSettings: () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FormMark(14.dp)
            Spacer(Modifier.width(12.dp))
            Text("PaperDesk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(" · $place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Normal, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (desk.isNotBlank()) Text(desk, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp).width(72.dp))
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
        }
        InkRule()
    }
}

/** Flat bottom navigation: ink rule on top, the selected tab is a solid square block. */
@Composable
private fun BottomBar(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        InkRule()
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(64.dp)) {
            tabs.forEach { t ->
                val sel = t == current
                Column(Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Box(Modifier.width(56.dp).height(32.dp)
                        .background(if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center) {
                        Icon(t.icon, null, Modifier.size(22.dp),
                            tint = if (sel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                    }
                    Text(t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun PairField(modifier: Modifier = Modifier) {
    var link by rememberSaveable { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedTextField(link, { link = it; bad = false }, Modifier.fillMaxWidth(), label = { Text("Paste pairing link") },
            placeholder = { Text("paperdesk://pair?…") }, singleLine = true, isError = bad,
            supportingText = if (bad) ({ Text("■ Not a pairing link. It starts with paperdesk://pair? and carries url and token.") }) else null)
        BFilledButton(form = FormKind.Circle, enabled = link.isNotBlank(), modifier = Modifier.padding(top = 8.dp), onClick = {
            val p = Pairing.parse(link)
            if (p == null) bad = true else PairNav.pending.value = p
        }) { Text("Connect") }
    }
}

@Composable
fun SettingsDialog(info: ServerInfo, onDismiss: () -> Unit, onSetup: (step: Int, alone: Boolean) -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Prefs.url(ctx)) }
    var token by remember { mutableStateOf(Prefs.token(ctx)) }
    val paired = Prefs.configured(ctx)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true)
                PairField(Modifier.padding(top = 16.dp))
                if (paired) BOutlinedButton(form = FormKind.Triangle, modifier = Modifier.padding(top = 16.dp),
                    onClick = { onSetup(SetupFlow.CONNECT, false) }) { Text("Run setup again") }
                if (paired && info.rmConnect && !info.remarkable) BOutlinedButton(form = FormKind.Triangle, modifier = Modifier.padding(top = 8.dp),
                    onClick = { onSetup(SetupFlow.REMARKABLE, true) }) { Text("reMarkable: connect") }
                if (paired) BOutlinedButton(form = FormKind.Square, modifier = Modifier.padding(top = 8.dp),
                    onClick = { Prefs.disconnect(ctx); onSaved() }) { Text("Disconnect") }
                Text("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})." +
                    (if (BuildConfig.DEFAULT_URL.isNotBlank()) " Clear a field to use the built-in default." else ""),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.save(ctx, url, token); onSaved() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
