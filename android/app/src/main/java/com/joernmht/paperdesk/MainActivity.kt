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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.joernmht.paperdesk.ui.ActivityScreen
import com.joernmht.paperdesk.ui.ChatScreen
import com.joernmht.paperdesk.ui.InboxScreen
import com.joernmht.paperdesk.ui.RemarkableScreen
import com.joernmht.paperdesk.ui.SearchScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Shared helpers handed to every screen. */
class AppCtx(val ctx: Context, val api: Api, val agent: AgentApi, val snackbar: SnackbarHostState, val scope: CoroutineScope) {
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

class MainActivity : ComponentActivity() {
    private val tabState = mutableStateOf(Tab.Inbox)

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PollWorker.ensureChannel(this)
        PollWorker.schedulePeriodic(this)
        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent { PaperDeskTheme { App(tabState) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        Tab.entries.firstOrNull { it.key == i?.getStringExtra(PollWorker.EXTRA_TAB) }?.let { tabState.value = it }
    }
}

@Composable
fun PaperDeskTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), content = content) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(tabState: androidx.compose.runtime.MutableState<Tab>) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var settingsVersion by remember { mutableStateOf(0) }   // bump → screens reload with new prefs
    val app = remember(settingsVersion) { AppCtx(ctx.applicationContext, Api(ctx.applicationContext), AgentApi(ctx.applicationContext), snackbar, scope) }
    var showSettings by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf(false) }

    LaunchedEffect(settingsVersion) {
        update = try {
            app.api.health().optInt("apk_version", 0) > BuildConfig.VERSION_CODE
        } catch (_: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PaperDesk · ${tabState.value.label}") },
                actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") } },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tabState.value == t, onClick = { tabState.value = t },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (update) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { app.openUrl(app.api.apkUrl()) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Update available. Tap to download the new APK")
                    }
                }
            }
            when (tabState.value) {
                Tab.Inbox -> InboxScreen(app, settingsVersion)
                Tab.Search -> SearchScreen(app)
                Tab.Remarkable -> RemarkableScreen(app, settingsVersion)
                Tab.Chat -> ChatScreen(app, settingsVersion)
                Tab.Activity -> ActivityScreen(app, settingsVersion)
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false }, onSaved = {
        showSettings = false; settingsVersion++
    })
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Prefs.url(ctx)) }
    var token by remember { mutableStateOf(Prefs.token(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true)
                Text("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Clear a field to use the built-in default.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.save(ctx, url, token); onSaved() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
