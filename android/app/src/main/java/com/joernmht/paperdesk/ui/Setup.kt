package com.joernmht.paperdesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.joernmht.paperdesk.AgentSetup
import com.joernmht.paperdesk.Secret
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.joernmht.paperdesk.AppCtx
import com.joernmht.paperdesk.BuildConfig
import com.joernmht.paperdesk.PairClient
import com.joernmht.paperdesk.PairException
import com.joernmht.paperdesk.Pairing
import com.joernmht.paperdesk.Prefs
import com.joernmht.paperdesk.ServerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SETUP_GUIDE = "https://github.com/joernmht/PaperDesk#setup"
const val INSTALL_CMD = "curl -fsSL https://raw.githubusercontent.com/joernmht/PaperDesk/main/install.sh | bash"
private const val TAILSCALE_PKG = "com.tailscale.ipn"
private const val RM_CONNECT_URL = "https://my.remarkable.com/device/browser/connect"

/**
 * The wizard's steps and how to move between them. Steps after CONNECT exist only if the server offers them;
 * the order puts Keys before the steps that need a key (chat agent: LLM_API_KEY; papers: git tokens).
 */
object SetupFlow {
    const val WELCOME = 0
    const val TAILSCALE = 1
    const val MACHINE = 2
    const val INSTALL = 3
    const val CONNECT = 4
    const val KEYS = 5
    const val AGENT = 6
    const val PAPERS = 7
    const val REMARKABLE = 8
    const val TOPICS = 9
    const val DONE = 10
    const val CLOSE = -1

    private fun offered(step: Int, info: ServerInfo) = when (step) {
        KEYS -> info.secrets
        AGENT -> info.agentSetup
        PAPERS -> info.papersEdit
        REMARKABLE -> info.rmConnect
        TOPICS -> info.topics
        else -> true
    }

    /** Steps shown in the indicator. Before pairing nobody knows what the server offers, so all are listed. */
    fun visible(paired: Boolean, info: ServerInfo): List<Int> = (WELCOME..DONE).filter { it <= CONNECT || !paired || offered(it, info) }

    fun next(from: Int, info: ServerInfo, solo: Boolean): Int = when {
        from < CONNECT -> from + 1
        solo -> CLOSE
        from >= DONE -> CLOSE
        else -> ((from + 1)..DONE).first { offered(it, info) }
    }

    /** null = leave the app (system default). A paired app leaves the wizard instead of walking back into steps 0–3. */
    fun prev(from: Int, paired: Boolean, info: ServerInfo, solo: Boolean): Int? = when {
        from == WELCOME -> null
        from < CONNECT -> from - 1
        from == CONNECT -> if (paired) CLOSE else INSTALL
        solo -> CLOSE
        else -> ((from - 1) downTo CONNECT).first { it == CONNECT || offered(it, info) }
    }
}

/**
 * First-run wizard: the app is all a new user is handed, and it walks them from "what is this" to a filled inbox.
 * [goto] moves to a step (CLOSE ends the wizard); [onPaired] is called once a pairing has been saved and confirmed
 * by /api/health; [refresh] makes the app re-read the server's features.
 */
@Composable
fun SetupWizard(app: AppCtx, step: Int, solo: Boolean, paired: Boolean, goto: (Int) -> Unit,
                onPaired: (Pairing, ServerInfo) -> Unit, refresh: () -> Unit) {
    val info = app.info
    val prev = SetupFlow.prev(step, paired, info, solo)
    BackHandler(enabled = prev != null) { prev?.let(goto) }
    val next = { goto(SetupFlow.next(step, info, solo)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!solo) StepIndicator(SetupFlow.visible(paired, info), step)
        when (step) {
            SetupFlow.WELCOME -> WelcomeStep(next) { goto(SetupFlow.CONNECT) }
            SetupFlow.TAILSCALE -> TailscaleStep(app, next)
            SetupFlow.MACHINE -> MachineStep(app, next)
            SetupFlow.INSTALL -> InstallStep(app, next)
            SetupFlow.CONNECT -> ConnectStep(app, paired, onPaired)
            SetupFlow.KEYS -> KeysStep(app, solo, next)
            SetupFlow.AGENT -> AgentStep(app, solo, next, refresh)
            SetupFlow.PAPERS -> PapersStep(app, solo, next)
            SetupFlow.REMARKABLE -> RemarkableStep(app, solo, next, refresh)
            SetupFlow.TOPICS -> TopicsStep(app, next)
            else -> DoneStep(next)
        }
        if (prev != null) TextButton(onClick = { goto(prev) }) { Text(if (prev == SetupFlow.CLOSE) "Close setup" else "Back") }
        Text("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** ● done, ▲ you are here, □ not reached. */
@Composable
private fun StepIndicator(steps: List<Int>, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEach { s ->
            when {
                s < current -> Form(FormKind.Circle, 14.dp, label = "done")
                s == current -> Box(Modifier.background(BYellow).padding(4.dp)) {
                    CompositionLocalProvider(LocalContentColor provides BInk) { Form(FormKind.Triangle, 14.dp, label = "current step") }
                }
                else -> Form(FormKind.Square, 14.dp, label = "to do", filled = false)
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("Step ${steps.indexOf(current) + 1} of ${steps.size}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Page(title: String, vararg lines: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        content()
    }
}

/** Status as form + words: ● good, ■ not yet. */
@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(Modifier.fillMaxWidth().background(if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Form(if (ok) FormKind.Circle else FormKind.Square, 16.dp, label = if (ok) "ok" else "not yet")
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

// ------------------------------------------------------------------ 0 welcome

@Composable
private fun WelcomeStep(next: () -> Unit, haveCode: () -> Unit) = Page(
    "Your paper desk",
    "PaperDesk finds papers worth reading, keeps the ones you choose in your own library, and helps you cite them. A reMarkable tablet can join in, but does not have to.",
    "You need three things: this phone, a Linux machine that stays switched on, and a free Tailscale account.",
    "The machine can be small: a cloud server with Ubuntu 24.04, 1 CPU and 1–2 GB of memory costs about 3 € a month. A home server works too.",
) {
    BFilledButton(form = FormKind.Triangle, onClick = next) { Text("Start") }
    TextButton(onClick = haveCode) { Text("I already have a pairing code") }
}

// ------------------------------------------------------------------ 1 Tailscale

/** A VPN is up on this phone. Android cannot tell us whose it is; while setting up PaperDesk it is Tailscale. */
private fun vpnOn(ctx: Context): Boolean = try {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
} catch (_: Exception) {
    false
}

@Composable
private fun TailscaleStep(app: AppCtx, next: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(vpnOn(ctx)) }
    // re-checked all the time the step is on screen, so coming back from the Tailscale app updates it at once
    LaunchedEffect(Unit) { while (true) { on = vpnOn(ctx); delay(1500) } }
    Page(
        "Tailscale on this phone",
        "Tailscale builds a private network between this phone and your machine. Nothing is opened to the internet; only your own devices can reach your desk.",
        "Install the Tailscale app, sign in (a free account is enough) and switch it on.",
    ) {
        StatusLine(on, if (on) "Tailscale is on" else "Not connected yet")
        BFilledButton(form = FormKind.Triangle, onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                app.openUrl("https://play.google.com/store/apps/details?id=$TAILSCALE_PKG")
            }
        }) { Text("Get Tailscale") }
        BOutlinedButton(onClick = next) { Text("Next") }
        if (!on) Text("You can go on without it, but the last step (Connect) only works once Tailscale is on.", style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------ 2 machine

@Composable
private fun MachineStep(app: AppCtx, next: () -> Unit) = Page(
    "Your machine",
    "PaperDesk needs a Linux machine of your own that stays on. Your papers and your library live there, not on someone else's service.",
    "If you do not have one yet: create an Ubuntu 24.04 server with any cloud provider and log in to it with SSH. The guide shows how, step by step.",
) {
    BFilledButton(form = FormKind.Triangle, onClick = next) { Text("I have a machine") }
    TextButton(onClick = { app.openUrl(SETUP_GUIDE) }) { Text("Open the guide") }
}

// ------------------------------------------------------------------ 3 install

@Composable
private fun InstallStep(app: AppCtx, next: () -> Unit) {
    val ctx = LocalContext.current
    Page(
        "Install PaperDesk on the machine",
        "Log in to the machine and run this one command. Typing it from a phone is no fun: share it to your laptop and paste it there.",
    ) {
        SelectionContainer {
            Text(INSTALL_CMD, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BOutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PaperDesk install command", INSTALL_CMD))
                app.toast("Copied")
            }) { Text("Copy") }
            BOutlinedButton(onClick = {
                try {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "PaperDesk install command").putExtra(Intent.EXTRA_TEXT, INSTALL_CMD), "Share the command")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    app.toast("No app to share with")
                }
            }) { Text("Share") }
        }
        Text("What it does:", style = MaterialTheme.typography.titleSmall)
        Text("1. It installs Tailscale on the machine and prints a sign-in link. Open that link and use the SAME Tailscale account as on this phone.",
            style = MaterialTheme.typography.bodyMedium)
        Text("2. It starts PaperDesk and keeps it running.", style = MaterialTheme.typography.bodyMedium)
        Text("3. At the end it shows a QR code and a pairing code. That is what the next step needs.", style = MaterialTheme.typography.bodyMedium)
        BFilledButton(form = FormKind.Triangle, onClick = next) { Text("I see the QR code") }
    }
}

// ------------------------------------------------------------------ 4 connect

private fun scanQr(ctx: Context, onText: (String) -> Unit, onError: (String) -> Unit) {
    try {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { b -> b.rawValue?.takeIf { it.isNotBlank() }?.let(onText) ?: onError("That QR code is empty.") }
            .addOnFailureListener { onError("The scanner is not ready yet (Google Play services loads it on first use). Try again in a moment, or enter the code by hand.") }
    } catch (e: Exception) {
        onError("This phone has no QR scanner. Enter the code by hand.")
    }
}

@Composable
private fun ConnectStep(app: AppCtx, paired: Boolean, onPaired: (Pairing, ServerInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var byHand by rememberSaveable { mutableStateOf(false) }
    var byLink by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }

    /** Save the pairing, prove it with /api/health, and only then go on; a pairing that fails the check is undone. */
    fun connect(resolve: suspend () -> Pairing) {
        busy = true; error = null
        scope.launch {
            try {
                val p = resolve()
                val before = Prefs.snapshot(ctx)
                Prefs.pair(ctx, p)
                val h = try {
                    app.api.health()
                } catch (e: Exception) {
                    Prefs.restore(ctx, before)
                    throw PairException("The pairing was accepted, but the desk did not answer afterwards (${e.message ?: e.javaClass.simpleName}). Is Tailscale on, on both devices?")
                }
                Prefs.setServerInfo(ctx, h)
                busy = false
                onPaired(p, ServerInfo.from(h))
            } catch (e: Exception) {
                error = if (e is PairException) e.message else "Could not connect: ${e.message ?: e.javaClass.simpleName}"
                busy = false
            }
        }
    }

    fun fromText(text: String) {
        val direct = Pairing.parse(text)
        val viaCode = PairClient.fromQr(text)
        when {
            direct != null -> connect { direct }
            viaCode != null -> connect { PairClient.fetch(viaCode.first, viaCode.second) }
            else -> error = "That is not a PaperDesk code. Scan the QR code the installer shows, or run `paperdesk pair` on the machine for a new one."
        }
    }

    Page(
        "Connect",
        "The installer ended with a QR code and a pairing code. Point the camera at the QR code.",
        if (paired) "The phone stays connected to its current desk until a new pairing has worked."
        else "No QR code any more? Run `paperdesk pair` on the machine. Codes last 30 minutes.",
    ) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorLine(it, Modifier) }
        BFilledButton(form = FormKind.Circle, enabled = !busy, onClick = {
            error = null
            scanQr(ctx, { fromText(it) }, { error = it })
        }) { Text("Scan the QR code") }

        TextButton(onClick = { byHand = !byHand }) { Text("Enter the code by hand") }
        if (byHand) {
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Machine address") },
                placeholder = { Text("myvm.tail1234.ts.net") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            OutlinedTextField(code, { code = it.uppercase() }, Modifier.fillMaxWidth(), label = { Text("Pairing code") },
                placeholder = { Text("XXXX-XXXX") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false))
            BOutlinedButton(form = FormKind.Circle, enabled = !busy && address.isNotBlank() && code.isNotBlank(), onClick = {
                val origin = PairClient.origin(address)
                val c = PairClient.normCode(code)
                when {
                    origin == null -> error = "The machine address should look like myvm.tail1234.ts.net (the installer prints it). Only https addresses work."
                    c == null -> error = "The pairing code has 8 characters, like ABCD-2345."
                    else -> connect { PairClient.fetch(origin, c) }
                }
            }) { Text("Connect") }
        }

        TextButton(onClick = { byLink = !byLink }) { Text("Paste pairing link") }
        if (byLink) {
            OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Pairing link") },
                placeholder = { Text("paperdesk://pair?…") }, singleLine = true)
            BOutlinedButton(form = FormKind.Circle, enabled = !busy && link.isNotBlank(), onClick = { fromText(link) }) { Text("Connect") }
        }
    }
}

// ------------------------------------------------------------------ 5 reMarkable (optional)

@Composable
private fun RemarkableStep(app: AppCtx, solo: Boolean, next: () -> Unit, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val connected = app.info.remarkable

    Page(
        "reMarkable (optional)",
        if (connected) "Your tablet is linked. Papers you send from the inbox land in a folder on it, and your handwritten notes come back."
        else "Have a reMarkable tablet? Link it, and one tap sends a paper to it. No tablet: skip this step.",
    ) {
        if (connected) {
            StatusLine(true, "Connected")
            BFilledButton(form = FormKind.Triangle, onClick = next) { Text(if (solo) "Done" else "Next") }
            return@Page
        }
        Text("1. Open my.remarkable.com/device/browser/connect and sign in.", style = MaterialTheme.typography.bodyLarge)
        BOutlinedButton(onClick = { app.openUrl(RM_CONNECT_URL) }) { Text("Open my.remarkable.com") }
        Text("2. Type the 8-character code it shows.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(code, { v -> code = v.lowercase().filter { it.isLetterOrDigit() }.take(8) }, Modifier.fillMaxWidth(),
            label = { Text("One-time code") }, placeholder = { Text("abcdefgh") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorLine(it, Modifier) }
        BFilledButton(form = FormKind.Circle, enabled = !busy && code.length == 8, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    var job = app.api.rmConnect(code) ?: throw IllegalStateException("the server started no job")
                    while (job.active) { delay(2000); job = app.api.job(job.id) }
                    if (job.status != "done") throw IllegalStateException(job.summary ?: "the server could not link the tablet")
                    Prefs.setServerInfo(ctx, app.api.health())
                    refresh()
                    if (!Prefs.serverInfo(ctx).remarkable) error = "The server finished, but does not report a linked tablet. Get a fresh code and try again."
                } catch (e: Exception) {
                    error = "Not linked: ${e.message ?: e.javaClass.simpleName}. The code works once and for a few minutes; get a fresh one and try again."
                }
                busy = false
            }
        }) { Text("Connect") }
        TextButton(onClick = next) { Text(if (solo) "Close" else "Skip") }
    }
}

// ------------------------------------------------------------------ keys

@Composable
private fun KeysStep(app: AppCtx, solo: Boolean, next: () -> Unit) {
    val list = remember { androidx.compose.runtime.mutableStateListOf<Secret>() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { val r = app.api.secrets(); list.clear(); list.addAll(r); error = null }
        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        loading = false
    }
    Page(
        "Keys",
        "Some services need a key or token: the language model for the chat agent, Overleaf or GitHub for your papers, literature search.",
        "A key goes once over your private Tailscale link to your own machine and is stored there, in a file only you can read. The app never shows it again.",
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorLine("Cannot load the list of keys: $it", Modifier) }
        list.forEach { k ->
            SecretRow(app, k) { updated -> list.clear(); list.addAll(updated) }
            Hairline()
        }
        val missing = list.count { !it.set && !it.optional }
        if (missing > 0) Text("$missing required key${if (missing == 1) "" else "s"} not set yet. You can add them later in Settings → Keys.",
            style = MaterialTheme.typography.bodySmall)
        BFilledButton(form = FormKind.Triangle, onClick = next) { Text(if (solo) "Done" else "Next") }
    }
}

@Composable
private fun SecretRow(app: AppCtx, k: Secret, onSaved: (List<Secret>) -> Unit) {
    var value by remember(k.name) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (k.set) { Form(FormKind.Circle, 12.dp, label = "set"); Spacer(Modifier.width(8.dp)) }
            Text(k.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (k.set) "set" else if (k.optional) "optional" else "needed", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (k.help.isNotBlank()) Text(k.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, { value = it.trim() }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(if (k.set) "Replace the stored key" else "Paste the key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            BFilledButton(modifier = Modifier.padding(start = 8.dp), enabled = value.isNotBlank() && !busy, onClick = {
                busy = true
                app.run("${k.label} saved", { app.api.setSecret(k.name, value) }, onErr = { busy = false }) { r -> busy = false; value = ""; onSaved(r) }
            }) { Text("Save") }
        }
        k.url?.let { u -> TextButton(onClick = { app.openUrl(u) }) { Text("Get one") } }
    }
}

// ------------------------------------------------------------------ chat agent

@Composable
private fun AgentStep(app: AppCtx, solo: Boolean, next: () -> Unit, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf<AgentSetup?>(null) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val s = app.api.agentSetup(); setup = s
            if (baseUrl.isBlank()) baseUrl = s.baseUrl
            if (model.isBlank()) model = s.model
            done = s.configured
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    Page(
        "Chat agent",
        "The Chat tab talks to an assistant on your machine that can read your papers and repos. It needs a language model: the address of a provider (any OpenAI-compatible service) and a model name.",
        "Its key is LLM_API_KEY in the Keys step.",
    ) {
        if (done) StatusLine(true, "The chat agent is set up")
        val providers = setup?.providers.orEmpty()
        if (providers.isNotEmpty()) Box {
            BOutlinedButton(onClick = { pick = true }) { Text("Choose a provider") }
            DropdownMenu(pick, { pick = false }) {
                providers.forEach { p ->
                    DropdownMenuItem(text = { Text(p.label) }, onClick = { pick = false; baseUrl = p.baseUrl; model = p.model })
                }
            }
        }
        providers.firstOrNull { it.baseUrl == baseUrl }?.url?.let { u -> TextButton(onClick = { app.openUrl(u) }) { Text("Provider's page (get a key)") } }
        OutlinedTextField(baseUrl, { baseUrl = it.trim() }, Modifier.fillMaxWidth(), label = { Text("LLM base URL") },
            placeholder = { Text("https://…/v1") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        OutlinedTextField(model, { model = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Model name") }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorLine(it, Modifier) }
        BFilledButton(form = FormKind.Circle, enabled = !busy && baseUrl.startsWith("http") && model.isNotBlank(), onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    var job = app.api.startAgentSetup(baseUrl, model) ?: throw IllegalStateException("the server started no job")
                    while (job.active) { delay(2000); job = app.api.job(job.id) }
                    if (job.status != "done") throw IllegalStateException(job.summary ?: "the agent could not be set up")
                    val h = app.api.health()
                    Prefs.setServerInfo(ctx, h)
                    val url = ServerInfo.from(h).agentUrl
                    if (url.isNotBlank()) Prefs.setAgentUrl(ctx, url)
                    done = true
                    refresh()
                } catch (e: Exception) {
                    error = "Not set up: ${e.message ?: e.javaClass.simpleName}"
                }
                busy = false
            }
        }) { Text(if (done) "Set up again" else "Set up the agent") }
        BOutlinedButton(form = FormKind.Triangle, onClick = next) { Text(if (solo) "Done" else if (done) "Next" else "Skip") }
    }
}

// ------------------------------------------------------------------ papers

@Composable
private fun PapersStep(app: AppCtx, solo: Boolean, next: () -> Unit) {
    val added = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    LaunchedEffect(Unit) { runCatching { app.api.papers() }.getOrNull()?.let { ps -> added.clear(); added.addAll(ps.map { it.label }) } }
    Page(
        "Add your paper repos",
        "PaperDesk can keep your LaTeX papers: edit them on the phone, comment, build the PDF. Each paper is a git repo, on Overleaf (Menu → Git) or GitHub.",
    ) {
        added.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) { Form(FormKind.Circle, 12.dp); Spacer(Modifier.width(8.dp)); Text(l) }
        }
        AddPaperForm(app) { ps -> added.clear(); added.addAll(ps.map { it.label }) }
        TextButton(onClick = next) { Text(if (solo) "Done" else if (added.isEmpty()) "Skip" else "Next") }
    }
}

// ------------------------------------------------------------------ 6 topics

@Composable
private fun TopicsStep(app: AppCtx, next: () -> Unit) {
    var count by remember { mutableStateOf(0) }
    Page(
        "What to follow",
        "Name one to three topics. Your desk searches them regularly and puts new papers into your inbox.",
        "Example: name \"Railway rescheduling\", search words \"railway rescheduling optimisation\".",
    ) {
        TopicsEditor(app, showRun = false) { count = it }
        BFilledButton(form = FormKind.Triangle, enabled = count > 0, onClick = {
            // first search right away, so the inbox is not empty on arrival; the wizard does not wait for it
            app.run(null, { app.api.topicsOp("run") }) { (_, job) -> app.jobStarted(job) }
            next()
        }) { Text("Next") }
        TextButton(onClick = next) { Text("Skip") }
    }
}

// ------------------------------------------------------------------ 7 done

@Composable
private fun DoneStep(finish: () -> Unit) = Page(
    "Your desk is ready.",
    "New papers arrive in Inbox.",
) {
    FormMark(40.dp)
    Row(verticalAlignment = Alignment.CenterVertically) { Form(FormKind.Square, 14.dp); Spacer(Modifier.width(8.dp)); Text("a square is new, or stops something") }
    Row(verticalAlignment = Alignment.CenterVertically) { Form(FormKind.Triangle, 14.dp); Spacer(Modifier.width(8.dp)); Text("a triangle is on its way") }
    Row(verticalAlignment = Alignment.CenterVertically) { Form(FormKind.Circle, 14.dp); Spacer(Modifier.width(8.dp)); Text("a circle is kept") }
    BFilledButton(form = FormKind.Circle, onClick = finish) { Text("Open my desk") }
}
