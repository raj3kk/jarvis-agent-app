package com.jarvis.autonomous.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jarvis.autonomous.BuildConfig
import com.jarvis.autonomous.R
import com.jarvis.autonomous.agent.LlmClient
import com.jarvis.autonomous.agent.LocalLlmManager
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisDiagnosticsFile
import com.jarvis.autonomous.voice.DeepgramSttClient
import com.jarvis.autonomous.voice.KokoroTtsClient
import com.jarvis.autonomous.voice.TtsProcessClient
import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.LaunchSession
import com.jarvis.autonomous.data.PreviousBounce
import com.jarvis.autonomous.data.buildBounceReportText
import com.jarvis.autonomous.data.clearStaleBounceReport
import com.jarvis.autonomous.data.detectPreviousBounce
import com.jarvis.autonomous.data.writeBounceReportFile
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.service.JarvisForegroundService
import com.jarvis.autonomous.service.JarvisWhiteboardService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Creamy whitish theme (user order 2026-11-02): warm cream background,
// white cards, dark-brown text, terracotta accent — readable in daylight
// and matching the Jarvis avatar's pink/cream palette.
private val JarvisColorScheme = lightColorScheme(
    primary = Color(0xFFB85C38),
    secondary = Color(0xFF2E7D5B),
    tertiary = Color(0xFFE75480),
    background = Color(0xFFFFF8ED),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onBackground = Color(0xFF3B2F2A),
    onSurface = Color(0xFF3B2F2A)
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.4.4 launch-bounce catcher: FIRST line — a launcher tap
        // writes this before ANY service/greeting/composition work,
        // so a bounce that kills the process mid-startup still
        // leaves a start marker with no LAUNCH_UI after it.
        JarvisStateRepository.addDiagnostic(
            "launch", "LAUNCH_START", "MainActivity created (launcher tap)."
        )
        // 1.4.4 per-launch guard window: reset HOME/BACK blocking
        // for THIS launch (see LaunchSession.onActivityLaunch).
        LaunchSession.onActivityLaunch()
        // 1.4.5 (item 6): seed the Kokoro-OFF card's single source
        // (repository flow) from prefs at launch, and load the
        // PERSISTED diagnostics into the in-memory mirror on a
        // background thread — a fresh post-bounce launch previously
        // showed an empty Diagnostics section (mirror starts empty)
        // until NEW entries arrived, looking evidence-free.
        JarvisStateRepository.setKokoroEnabled(JarvisPreferences(this).kokoroEnabled)
        Thread {
            runCatching { JarvisStateRepository.loadPersistedDiagnostics() }
        }.start()
        // 1.4.4 out-of-app report: detection + file write ALSO run
        // here on a background thread — NOT only from the Command
        // tab card. If the app bounces before any UI is composed,
        // the card can never run, but this thread still writes the
        // previous session's bounce report to
        // Documents/JarvisDiagnostics/last-bounce-report.txt, so
        // the user can copy it from a file manager even when the
        // app never stays open. The next launch re-detects and
        // rewrites it if this thread dies mid-write. Never on the
        // main thread (a slow MediaStore write must not ANR the
        // launch it is trying to diagnose). Fully best-effort:
        // any failure is swallowed, launch continues normally.
        Thread {
            runCatching {
                // 1.4.5 (item 6): ATOMIC claim — exactly one of the
                // three detection threads (Activity, service,
                // Command-tab card) detects + publishes; losers read
                // repository flows. Null detection deletes stale
                // report copies (clean launch leaves no old report).
                if (LaunchSession.claimBounceCheck()) {
                    val found = detectPreviousBounce()
                    if (found != null) {
                        val path = writeBounceReportFile(
                            this, buildBounceReportText(found)
                        )
                        LaunchSession.bounce = found
                        LaunchSession.bounceFilePath = path
                        JarvisStateRepository.setBounceReport(found, path)
                    } else {
                        LaunchSession.bounce = null
                        JarvisStateRepository.setBounceReport(null, "")
                        clearStaleBounceReport(this)
                    }
                }
            }
        }.start()
        startJarvisBackgroundService()
        // 1.4.3: startup greeting — the service speaks it once
        // per service lifetime (ACTION_GREET is a no-op on
        // repeat); spoken from fixed local lines, LLM bypassed.
        sendServiceAction(JarvisForegroundService.ACTION_GREET)
        // 1.4.3 home-redirect gate: lifecycle markers are
        // PERSISTED (addDiagnostic appends to diagnostics.jsonl),
        // so a post-death report still shows whether the activity
        // was paused/destroyed or the process just died.
        JarvisStateRepository.addDiagnostic("nav", "LIFE_CREATE", "MainActivity created.")

        setContent {
            MaterialTheme(colorScheme = JarvisColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    JarvisMainApp(
                        onStartVoice = { sendServiceAction(JarvisForegroundService.ACTION_START_LISTENING) },
                        onStopMic = { sendServiceAction(JarvisForegroundService.ACTION_STOP_LISTENING) },
                        onRetryMic = { sendServiceAction(JarvisForegroundService.ACTION_RETRY_MIC) },
                        onStopTask = { sendServiceAction(JarvisForegroundService.ACTION_STOP_TASK) },
                        onSendTextCommand = { text ->
                            val intent = Intent(this, JarvisForegroundService::class.java).apply {
                                action = JarvisForegroundService.ACTION_TEXT_COMMAND
                                putExtra(JarvisForegroundService.EXTRA_COMMAND_TEXT, text)
                            }
                            startServiceCompat(intent)
                        },
                        onRefreshServiceSettings = {
                            sendServiceAction(JarvisForegroundService.ACTION_REFRESH_OVERLAY)
                        }
                    )
                }
            }
        }
    }

    // 1.4.3 home-redirect gates: lifecycle markers are PERSISTED
    // (addDiagnostic appends to diagnostics.jsonl), so a
    // post-death report still shows whether the activity was
    // paused/destroyed or the process just died.
    override fun onResume() {
        super.onResume()
        JarvisStateRepository.addDiagnostic("nav", "LIFE_RESUME", "MainActivity resumed.")
    }

    override fun onPause() {
        JarvisStateRepository.addDiagnostic("nav", "LIFE_PAUSE", "MainActivity paused.")
        super.onPause()
    }

    override fun onDestroy() {
        JarvisStateRepository.addDiagnostic(
            "nav", "LIFE_DESTROY",
            "MainActivity destroyed (isFinishing=$isFinishing)."
        )
        super.onDestroy()
    }

    private fun startJarvisBackgroundService() {
        val intent = Intent(this, JarvisForegroundService::class.java)
        startServiceCompat(intent)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, JarvisForegroundService::class.java).apply {
            this.action = action
        }
        startServiceCompat(intent)
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun JarvisMainApp(
    onStartVoice: () -> Unit,
    onStopTask: () -> Unit,
    onSendTextCommand: (String) -> Unit,
    onRefreshServiceSettings: () -> Unit,
    onStopMic: () -> Unit = {},
    onRetryMic: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { JarvisPreferences(context) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // 1.4.4 launch-bounce catcher: first composition = first UI.
    // Written ONCE per process (guarded by LaunchSession) — a
    // previous session with LAUNCH_START but no LAUNCH_UI died
    // during startup. This is composition, not a drawn frame;
    // that distinction is documented, not hidden.
    LaunchedEffect(Unit) {
        if (!LaunchSession.uiStable) {
            LaunchSession.markUiStable()
            JarvisStateRepository.addDiagnostic(
                "launch", "LAUNCH_UI", "First UI composition shown."
            )
        }
    }

    // 1.4.0 wiring: ONE shared browser host for Browser + Projects tabs.
    val browserHost = remember { BrowserHostState() }
    browserHost.onShowBrowserTab = { selectedTab = 5 }
    DisposableEffect(browserHost) {
        JarvisBrowserHost.current = browserHost
        onDispose {
            if (JarvisBrowserHost.current === browserHost) JarvisBrowserHost.current = null
        }
    }

    // Kokoro first-run progress (1.4.0, bundled assets — NO download):
    // shown only while the bundled copy is actually running. If the
    // model dir is already ready, no overlay ever appears (no flicker).
    var kokoroStage by remember { mutableStateOf("") }
    var kokoroCopiedMb by remember { mutableStateOf(0L) }
    var kokoroTotalMb by remember { mutableStateOf(0L) }
    var kokoroError by remember { mutableStateOf<String?>(null) }
    var kokoroDismissed by remember { mutableStateOf(false) }
    var kokoroChecking by remember { mutableStateOf(!KokoroTtsClient.isDownloaded(context)) }
    // 1.4.5 (item 1a): launch copy goes through :tts (single
    // writer) — a main-process copy used to collide with the :tts
    // copy on the same *.tmp files (per-process copyMutex).
    // Read-only isDownloaded checks stay main-process (safe).
    LaunchedEffect(Unit) {
        // Seed the Kokoro-OFF card flow from prefs at launch.
        JarvisStateRepository.setKokoroEnabled(prefs.kokoroEnabled)
        if (!KokoroTtsClient.isDownloaded(context)) {
            kokoroChecking = true
            val ok = TtsProcessClient.ensure(context) { stage, copied, total ->
                kokoroStage = stage
                kokoroCopiedMb = copied
                kokoroTotalMb = total
            }
            kokoroChecking = false
            // 1.4.6: an ensure that lost to an in-flight speak
            // (TTS_BUSY) is NOT a setup failure — the speak's own
            // :tts ensure completes the assets (copyMutex in
            // :tts) — so it must not paint a "setup fail"
            // overlay during a greeting speak. Only a real
            // ensure failure shows the error.
            kokoroError = if (ok) {
                null
            } else if (TtsProcessClient.lastFailure == TtsProcessClient.FAIL_BUSY) {
                null
            } else {
                "Kokoro setup fail ho gaya — dobara try karo."
            }
        }
    }
    val showKokoroOverlay = !kokoroDismissed &&
        (kokoroChecking || (kokoroStage == "copying") || kokoroError != null)

    // C-B13: the permission-result map is HANDLED, not ignored.
    // A denied mic permission explains itself in a banner, can be
    // re-requested from that banner, and disables voice UI until
    // granted (re-checked on every resume, so granting it in
    // Settings clears the banner on return).
    var micPermissionDenied by remember { mutableStateOf(false) }
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        micPermissionDenied =
            result[Manifest.permission.RECORD_AUDIO] != true
    }
    val mainLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mainLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micPermissionDenied = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            }
        }
        mainLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { mainLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtimePermissionLauncher.launch(perms.toTypedArray())
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            // 1.4.3: 8 tabs in a stock NavigationBar squeeze every
            // label on small screens ("Memory & Logs" truncated to
            // unreadable). A horizontally scrollable row keeps ALL
            // tabs with their FULL labels at readable size and a
            // clear selected pill — no tab or function removed.
            Surface(color = Color(0xFFFFFFFF)) {
                // 1.4.5 (item 6): LazyRow + scroll-to-selected. The
                // old Row + horizontalScroll never scrolled to the
                // selected tab, so a selected tab could sit
                // off-screen with no visible indicator. Now the bar
                // animates to the selected item, keeps the pill, and
                // adds a 2 dp underline under the selected tab.
                val tabs = listOf(
                    Triple(0, Icons.Default.Home, "Command"),
                    Triple(1, Icons.Default.Security, "Permissions"),
                    Triple(2, Icons.Default.Settings, "AI Brain"),
                    Triple(3, Icons.Default.List, "Memory & Logs"),
                    Triple(4, Icons.Default.Build, "Projects"),
                    Triple(5, Icons.Default.Send, "Browser"),
                    Triple(6, Icons.Default.Security, "Licence"),
                    Triple(7, Icons.Default.Edit, "Whiteboard")
                )
                val tabListState = rememberLazyListState()
                LaunchedEffect(selectedTab) {
                    runCatching { tabListState.animateScrollToItem(selectedTab) }
                }
                LazyRow(
                    state = tabListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(tabs) { (index, icon, label) ->
                        val selected = selectedTab == index
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) Color(0xFFFFF1DC)
                                    else Color.Transparent
                                )
                                .clickable { selectedTab = index }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (selected) Color(0xFFB85C38)
                                else Color(0xFF8A7A70),
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold
                                else FontWeight.Normal,
                                color = if (selected) Color(0xFFB85C38)
                                else Color(0xFF8A7A70),
                                maxLines = 1,
                                softWrap = false
                            )
                            Box(
                                modifier = Modifier
                                    .height(2.dp)
                                    .width(24.dp)
                                    .background(
                                        if (selected) Color(0xFFB85C38)
                                        else Color.Transparent
                                    )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1.4.3 Kokoro-OFF launch warning: speakAndWait
            // returns false silently when the toggle is off, and
            // the engine's chat-only fallback made "bolti hi nahi"
            // look like a bug. Now the OFF state is visible.
            // 1.4.5 (item 6): reads the repository flow, NOT prefs
            // directly — prefs is a plain SharedPreferences delegate
            // (not Compose state), so an AI Brain Save never
            // recomposed this parent header and the card stayed
            // stale until a tab switch forced recomposition.
            val kokoroEnabledFlow by JarvisStateRepository.kokoroEnabled.collectAsState()
            if (!kokoroEnabledFlow) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    )
                ) {
                    Text(
                        "Kokoro OFF hai — Jarvis bolega nahi. Bolna " +
                            "wapas chahiye to AI Brain me Kokoro ON karo.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            when (selectedTab) {
                0 -> CommandCenterScreen(
                    onStartVoice = onStartVoice,
                    onStopTask = onStopTask,
                    onSendTextCommand = onSendTextCommand,
                    onNavigatePermissions = { selectedTab = 1 },
                    voiceDisabled = micPermissionDenied,
                    onStopMic = onStopMic,
                    onRetryMic = onRetryMic
                )

                1 -> PermissionsChecklistScreen()

                2 -> AiBrainSettingsScreen(
                    prefs = prefs,
                    onSettingsUpdated = onRefreshServiceSettings
                )

                3 -> MemoryRoutinesLogsScreen(
                    prefs = prefs,
                    onRunRoutineNow = { prompt ->
                        selectedTab = 0
                        onSendTextCommand(prompt)
                    }
                )

                4 -> ProjectsScreen(browserHost)

                5 -> BrowserScreen(browserHost)

                6 -> LicenceScreen()

                7 -> WhiteboardScreen()
            }
        }
        }
    }
        // C-B13 mic-permission explainer banner (above content,
        // below the Kokoro overlay when that is showing).
        if (micPermissionDenied) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5D6D6))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Microphone permission nahi mili — voice band hai. " +
                            "Bina mic ke Jarvis sirf text commands par kaam karega.",
                        fontSize = 13.sp,
                        color = Color(0xFF3B2F2A)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                runtimePermissionLauncher.launch(
                                    arrayOf(Manifest.permission.RECORD_AUDIO)
                                )
                            }
                        ) { Text("Dobara permission maango") }
                        Button(
                            onClick = { selectedTab = 1 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF8A7A70)
                            )
                        ) { Text("Permissions kholo") }
                    }
                }
            }
        }
        // Kokoro first-run overlay (blocks the chat behind it while
        // copying, so a tap cannot queue a speak that silently fails).
        if (showKokoroOverlay) {
            val kokoroScope = rememberCoroutineScope()
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFFFFF8ED)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Jarvis ki awaaz taiyaar ho rahi hai…",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB85C38)
                        )
                        if (kokoroError != null) {
                            Text(
                                text = kokoroError ?: "",
                                fontSize = 13.sp,
                                color = Color(0xFF3B2F2A)
                            )
                            Text(
                                text = "Space kam ho to kuch files/photos delete karke dobara try karo. Voice ke bina bhi chat kaam karegi.",
                                fontSize = 12.sp,
                                color = Color(0xFF8A7A70)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        kokoroError = null
                                        kokoroChecking = true
                                        kokoroScope.launch {
                                            // 1.4.9 (Step 1c): the old
                                            // direct
                                            // KokoroTtsClient.ensureAssetsReady
                                            // call ran a copy from the MAIN
                                            // process, breaking the 1.4.5
                                            // single-writer rule — a
                                            // per-process mutex cannot
                                            // exclude the :tts process, so
                                            // a main-process delete/recopy
                                            // under a loaded mmap'd engine
                                            // is a use-after-delete hazard.
                                            // Route through :tts instead.
                                            val ok = TtsProcessClient.ensure(context) { stage, copied, total ->
                                                kokoroStage = stage
                                                kokoroCopiedMb = copied
                                                kokoroTotalMb = total
                                            }
                                            kokoroChecking = false
                                            if (!ok) {
                                                kokoroError = TtsProcessClient.lastFailure
                                                    ?: "Kokoro setup fail ho gaya"
                                            }
                                        }
                                    }
                                ) { Text("Dobara try karo") }
                                Button(
                                    onClick = { kokoroDismissed = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                                ) { Text("Baad me") }
                            }
                        } else if (kokoroStage == "copying" && kokoroTotalMb > 0) {
                            Text(
                                text = "Copy ho raha hai: $kokoroCopiedMb / $kokoroTotalMb MB — pehli baar hi hoga, dobara nahi.",
                                fontSize = 13.sp,
                                color = Color(0xFF3B2F2A)
                            )
                            LinearProgressIndicator(
                                progress = { kokoroCopiedMb.toFloat() / kokoroTotalMb.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFB85C38)
                            )
                        } else {
                            Text(
                                text = "Storage check ho raha hai…",
                                fontSize = 13.sp,
                                color = Color(0xFF3B2F2A)
                            )
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFB85C38)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommandCenterScreen(
    onStartVoice: () -> Unit,
    onStopTask: () -> Unit,
    onSendTextCommand: (String) -> Unit,
    onNavigatePermissions: () -> Unit,
    /** C-B13: true while RECORD_AUDIO is denied — voice UI disabled. */
    voiceDisabled: Boolean = false,
    /** 1.4.2: mic-off + Retry for a dead loop (see banner below). */
    onStopMic: () -> Unit = {},
    onRetryMic: () -> Unit = {}
) {
    val agentState by JarvisStateRepository.agentState.collectAsState()
    val currentGoal by JarvisStateRepository.currentGoal.collectAsState()
    val stepInfo by JarvisStateRepository.currentStepInfo.collectAsState()
    val isAccConnected by JarvisStateRepository.isAccessibilityConnected.collectAsState()
    val chatHistory by JarvisStateRepository.chatHistory.collectAsState()
    // 1.4.2: terminal mic death banner state (service-written).
    val micFatal by JarvisStateRepository.micFatal.collectAsState()
    val micLoopEnabled by JarvisStateRepository.micLoopEnabled.collectAsState()
    // 1.4.5 (item 4): reconnecting banner state (service-written
    // while the user-enabled loop auto-revives; null = connected
    // or loop off).
    val micReconnecting by JarvisStateRepository.micReconnecting.collectAsState()

    var textInput by remember { mutableStateOf("") }
    val whiteboardContext = LocalContext.current

    val quickChips = listOf(
        "Aur bhai kya haal hai?",
        "WhatsApp khol ke unread messages check kar",
        "YouTube khol ke Arijit Singh songs play kar",
        "Instagram khol ke Reels scroll kar",
        "Flashlight ON kar de",
        "Volume 80 percent kar de"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 1.4.3: the whole content above the input bar scrolls as
        // ONE column — previously only the chat LazyColumn scrolled
        // (weight 1f) while the fixed header/avatar/cards squeezed
        // it to zero height on small screens and the tab looked
        // unscrollable. The input bar stays pinned below it.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        // 1.4.4 launch-bounce catcher — FIRST card, before greeting
        // and tasks: if the previous session bounced (died during
        // startup, crashed, or was killed after UI), the user gets
        // the report HERE with a Copy button immediately — even if
        // the app bounces again seconds later. Detection runs once
        // per process; the same report is ALSO written to a file
        // the user can copy from a file manager without the app
        // staying open at all (see writeBounceReportFile).
        // 1.4.5 (item 6): the card COLLECTS repository flows
        // (no snapshot-at-composition + no third racer). The
        // claimer — whichever thread wins claimBounceCheck()
        // (Activity, service, or this card) — detects + publishes
        // via setBounceReport, and this card recomposes when it
        // lands. Non-claimers skip detection entirely.
        val bounceInfo by JarvisStateRepository.bounceReport.collectAsState()
        val bounceFilePath by JarvisStateRepository.bounceFilePath.collectAsState()
        var bounceDismissed by remember { mutableStateOf(LaunchSession.bounceDismissed) }
        var bounceCopiedNote by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            if (LaunchSession.claimBounceCheck()) {
                val found = withContext(Dispatchers.IO) { detectPreviousBounce() }
                if (found != null) {
                    val text = buildBounceReportText(found)
                    val path = withContext(Dispatchers.IO) {
                        writeBounceReportFile(whiteboardContext, text)
                    }
                    LaunchSession.bounce = found
                    LaunchSession.bounceFilePath = path
                    JarvisStateRepository.setBounceReport(found, path)
                } else {
                    LaunchSession.bounce = null
                    JarvisStateRepository.setBounceReport(null, "")
                    withContext(Dispatchers.IO) {
                        clearStaleBounceReport(whiteboardContext)
                    }
                }
            }
        }
        // 1.4.5 CIRCUIT BREAKER card (WhopClip-style catcher:
        // app stays OPEN, full problem visible, copyable). Shown
        // ONLY on the launch where greeting speak was skipped
        // because the previous process died during greeting
        // speak (GREET_START without SPK_END). It sits directly
        // ABOVE the 1.4.4 bounce card, so the skip explanation
        // reads first and the FULL bounce report + its Copy
        // button follow right below it. Two proofs in one view:
        // (a) if the app stays open with greeting skipped, that
        // PROVES greeting speak was the trigger; (b) the user
        // can copy the full report without the app closing.
        // Next launch greets normally (the skip writes no
        // GREET_START, so the breaker self-clears).
        val greetSkipped by JarvisStateRepository.greetSkippedAfterSpeakDeath.collectAsState()
        var breakerDismissed by remember { mutableStateOf(false) }
        var breakerCopiedNote by remember { mutableStateOf("") }
        if (greetSkipped && !breakerDismissed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D6))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Pichhli baar greeting bolte waqt app band hua, isliye is baar greeting skip ki gayi taaki app khula rahe",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF7A5A00)
                    )
                    Text(
                        "Agar is baar app khula rehta hai to ye proof hai ki greeting speak hi trigger tha. " +
                            "Agli launch par greeting wapas normal hogi. Poori report neeche wale bounce card me hai — wahan se bhi copy kar sakte ho.",
                        fontSize = 12.sp, color = Color(0xFF3B2F2A)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val cm = whiteboardContext.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                val text = bounceInfo?.let { buildBounceReportText(it) }
                                    ?: "=== Jarvis greeting-speak breaker ===\n" +
                                        "Previous session died during greeting speak " +
                                        "(GREET_START without SPK_END) — greeting speak " +
                                        "skipped once so the app stays open.\n" +
                                        "=== end ==="
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("Jarvis bounce report", text)
                                )
                                breakerCopiedNote = "Copied — ab mujhe paste kar do."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                        ) {
                            Text("Copy poori report", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                JarvisStateRepository.setGreetSkippedAfterSpeakDeath(false)
                                breakerDismissed = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                        ) {
                            Text("Band karo", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (breakerCopiedNote.isNotBlank()) {
                        Text(breakerCopiedNote, fontSize = 12.sp, color = Color(0xFF2E7D5B))
                    }
                }
            }
        }
        if (bounceInfo != null && !bounceDismissed) {
            val bounce = bounceInfo!!
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Pichhli baar app khulte hi band ho gaya tha — ye report mujhe bhej do",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFB3261E)
                    )
                    Text("Wajah: ${bounce.classification}", fontSize = 12.sp, color = Color(0xFF3B2F2A))
                    Text(bounce.detail, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF8A7A70))
                    Text(
                        "File me bhi saved: $bounceFilePath",
                        fontSize = 11.sp, color = Color(0xFF8A7A70)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val cm = whiteboardContext.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Jarvis bounce report", buildBounceReportText(bounce)
                                    )
                                )
                                bounceCopiedNote = "Copied — ab mujhe paste kar do."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                        ) {
                            Text("Copy bounce report", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                LaunchSession.bounceDismissed = true
                                bounceDismissed = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                        ) {
                            Text("Band karo", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (bounceCopiedNote.isNotBlank()) {
                        Text(bounceCopiedNote, fontSize = 12.sp, color = Color(0xFF2E7D5B))
                    }
                }
            }
        }
        // 1.4.4 overlay-visibility fix (user evidence: "overlay
        // permission NAHI mili hai"): the grant flow itself was
        // already correct (Permissions tab intent + ON_RESUME
        // status), but a launch-time overlay failure surfaced
        // ONLY as a notification + log line — easy to miss, and
        // then the HUD pill just never appears with no in-app
        // reason. So the Command tab now says so plainly, with a
        // one-tap grant. Shown only while the HUD toggle is ON
        // (AI Brain me OFF karne se ye band ho jata hai) and
        // re-checked on every resume, so granting in Settings
        // clears it on return. NOTE: this banner is unreachable
        // while every launch bounces (user cannot open the app)
        // — it helps AFTER the bounce is fixed, it is NOT part
        // of the bounce fix path (see LaunchBounceCatcher header).
        val overlayLifecycleOwner = LocalLifecycleOwner.current
        var overlayTick by remember { mutableIntStateOf(0) }
        DisposableEffect(overlayLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) overlayTick++
            }
            overlayLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { overlayLifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val overlayMissing = remember(overlayTick) {
            val hudOn = whiteboardContext
                .getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE)
                .getBoolean("floating_overlay_enabled", true)
            hudOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(whiteboardContext)
        }
        if (overlayMissing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D6))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Overlay permission nahi mili — HUD pill nahi dikhega",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF7A5A00)
                    )
                    Text(
                        "Floating pill ke liye 'Draw over apps' chahiye. Grant dabakar system screen me Jarvis ke liye toggle ON karo. (Nahi chahiye to AI Brain me Floating HUD OFF kar do.) " +
                            "Agar app khulte hi band ho raha ho to in-app Grant nahi milega — tab manual route use karo: Android Settings > Apps > Jarvis > Display over other apps > Allow. Bounce fix ke baad granted-addView path phone par verify hoga.",
                        fontSize = 12.sp, color = Color(0xFF3B2F2A)
                    )
                    Button(
                        onClick = {
                            whiteboardContext.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${whiteboardContext.packageName}")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                    ) {
                        Text("Grant Overlay Permission", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
        // Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "J.A.R.V.I.S.",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFB85C38)
                )
                Text(
                    text = "Autonomous Multi-Step Mobile Agent",
                    fontSize = 12.sp,
                    color = Color(0xFF8A7A70)
                )
            }

            // Accessibility status badge
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isAccConnected) Color(0xFFD9EAD3) else Color(0xFFF5D6D6),
                modifier = Modifier.clickable { onNavigatePermissions() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAccConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccConnected) Color(0xFF2E7D5B) else Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAccConnected) "Screen Control ON" else "Enable Accessibility",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF3B2F2A)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 1.4.2 terminal mic death banner: the old UI showed
        // NOTHING when the recognizer died terminally — the user
        // saw on/off/on flapping and silence. This banner names
        // the reason + raw code, offers Retry (fresh start, not
        // the no-op re-tap), and a Mic Off button (previously
        // buttons only ever sent START).
        if (micFatal != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE8E6))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Mic band ho gaya",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFFB3261E)
                    )
                    Text(
                        text = micFatal!!.reason,
                        fontSize = 12.sp,
                        color = Color(0xFF3B2F2A)
                    )
                    // 1.4.5 (item 4/6): Deepgram-only errors carry
                    // code -1 (no system recognizer codes exist any
                    // more) — showing "-1" would be noise, so the
                    // line is hidden when code < 0.
                    if (micFatal!!.code >= 0) {
                        Text(
                            text = "Recognizer error code: ${micFatal!!.code}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8A7A70)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRetryMic,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                        ) {
                            Text("Retry mic", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onStopMic,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                        ) {
                            Text("Mic off", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        } else if (micReconnecting != null) {
            // 1.4.5 (item 4): reconnecting banner — the loop is
            // ENABLED and auto-reviving (NOT stopped). Shows the
            // reason + Retry (forces a fresh stop + start) + Mic
            // off (the only true off switch besides voice stop).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D6))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Mic dobara connect ho raha hai…",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF7A5A00)
                    )
                    Text(
                        text = micReconnecting ?: "",
                        fontSize = 12.sp,
                        color = Color(0xFF3B2F2A)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRetryMic,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                        ) {
                            Text("Retry mic", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onStopMic,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                        ) {
                            Text("Mic off", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        } else if (micLoopEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onStopMic,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 4.dp
                    ),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mic off", fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Jarvis persona avatar (uploaded image) with a 3D-look:
        // Compact on purpose (was 190x220): on small phones the tall
        // avatar pushed the chips + input bar off-screen, so options
        // below it looked missing. Now it sits at the top, always
        // visible, with chips + chat + input guaranteed below it.
        // deep shadow, glossy state-coloured ring and a live pulse that
        // speeds up while listening/speaking. A single 2D photo cannot
        // become a true 3D mesh on the phone, so this is a depth-styled
        // avatar, not a glTF model — the face, outfit and pose are exactly
        // the uploaded Jarvis image.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            JarvisPersonaAvatar(
                state = agentState,
                onClick = {
                    if (agentState == AgentRunState.IDLE) {
                        // C-B13: voice disabled -> go to Permissions
                        // instead of firing a voice start that can
                        // only fail.
                        if (voiceDisabled) onNavigatePermissions() else onStartVoice()
                    } else {
                        onStopTask()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Live Step Telemetry Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STATUS: ${agentState.name}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB85C38)
                    )
                    if (agentState != AgentRunState.IDLE) {
                        Button(
                            onClick = onStopTask,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 4.dp
                            ),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STOP", fontSize = 11.sp)
                        }
                    }
                }
                if (currentGoal.isNotBlank()) {
                    Text(
                        text = "Goal: $currentGoal",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF8D4A2B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stepInfo,
                    fontSize = 13.sp,
                    color = Color(0xFF3B2F2A)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Whiteboard entry point (1.4.0): overlay permission is
        // NEVER bypassed — without it the system permission screen
        // opens; with it the floating board service starts.
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(whiteboardContext)
                ) {
                    JarvisWhiteboardService.show(whiteboardContext)
                } else {
                    whiteboardContext.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${whiteboardContext.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
        ) {
            Text("Whiteboard kholo (floating board)", color = Color.White)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Action Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickChips.forEach { cmd ->
                FilterChip(
                    selected = false,
                    onClick = { onSendTextCommand(cmd) },
                    label = { Text(cmd, fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Conversation & Action Transcript
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFFF1DC))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chatHistory.forEach { item ->
                val isUser = item.sender == "You"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (isUser) Color(0xFFF5D6C6) else Color(0xFFFFFFFF),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(0.86f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.sender,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isUser) Color(0xFF8D4A2B) else Color(0xFF2E7D5B)
                                )
                                Text(
                                    text = item.timestamp,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.message,
                                fontSize = 13.sp,
                                color = Color(0xFF3B2F2A)
                            )
                        }
                    }
                }
            }
        }

        } // end scrollable content column

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Command Input Bar (pinned below the scroll area)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Type command or talk to Jarvis...", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                enabled = textInput.isNotBlank() || !voiceDisabled,
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendTextCommand(textInput)
                        textInput = ""
                    } else {
                        onStartVoice()
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB85C38))
            ) {
                Icon(
                    imageVector = if (textInput.isNotBlank()) Icons.Default.Send else Icons.Default.Mic,
                    contentDescription = "Send or Speak",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun JarvisPersonaAvatar(
    state: AgentRunState,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "persona_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (state == AgentRunState.IDLE) 1.03f else 1.09f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == AgentRunState.IDLE) 1800 else 650,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "persona_scale"
    )
    val ringColor by animateColorAsState(
        targetValue = when (state) {
            AgentRunState.IDLE -> Color(0xFFFF6EC7)
            AgentRunState.LISTENING -> Color(0xFF2E7D5B)
            AgentRunState.THINKING -> Color(0xFFD500F9)
            AgentRunState.EXECUTING_STEP -> Color(0xFFFFAB00)
            AgentRunState.WAITING_USER_REPLY -> Color(0xFF2E7D5B)
            AgentRunState.SPEAKING -> Color(0xFF2979FF)
        },
        label = "persona_ring"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 150.dp, height = 175.dp)
                .scale(scale)
                .shadow(28.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
                .border(3.dp, ringColor, RoundedCornerShape(28.dp))
                .clickable { onClick() }
        ) {
            Image(
                painter = painterResource(id = R.drawable.jarvis_persona),
                contentDescription = "Jarvis",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (state == AgentRunState.IDLE) "TAP TO SPEAK" else "TAP TO STOP",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3B2F2A)
        )
    }
}

@Composable
fun PermissionsChecklistScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isAccessibilityGranted = remember(refreshTick) {
        isAccessibilityServiceEnabled(context)
    }
    val isOverlayGranted = remember(refreshTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }
    val isMicGranted = remember(refreshTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val isBatteryUnrestricted = remember(refreshTick) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    val isNotificationListenerGranted = remember(refreshTick) {
        val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        enabledListeners.contains(context.packageName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Permissions & System Setup",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )
        Text(
            text = "Jarvis ko bina ruke har app control karne, tap/scroll karne aur background mein chalne ke liye niche diye gaye permissions ON karein:",
            fontSize = 13.sp,
            color = Color(0xFFB0BEC5)
        )

        PermissionItemCard(
            title = "1. Accessibility Screen Control (CRITICAL)",
            description = "Allows Jarvis to read screen buttons, tap, type messages, scroll, and navigate Back/Home across all apps.",
            isGranted = isAccessibilityGranted,
            buttonText = "Open Accessibility Settings",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        )

        PermissionItemCard(
            title = "2. Floating HUD Overlay (Draw Over Apps)",
            description = "Shows the draggable Jarvis Arc Reactor pill & Emergency Stop button on top of WhatsApp, Instagram, YouTube, etc.",
            isGranted = isOverlayGranted,
            buttonText = "Grant Overlay Permission",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }
            }
        )

        PermissionItemCard(
            title = "3. Unrestricted Battery (Background Survival)",
            description = "Prevents Android from stopping Jarvis when the screen is off or when heavy apps are opened.",
            isGranted = isBatteryUnrestricted,
            buttonText = "Allow Unrestricted Battery",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    }
                }
            }
        )

        PermissionItemCard(
            title = "4. Notification Reader Access",
            description = "Lets Jarvis read incoming WhatsApp, SMS, and system notifications aloud and reply on your command.",
            isGranted = isNotificationListenerGranted,
            buttonText = "Enable Notification Access",
            onClick = {
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        )

        PermissionItemCard(
            title = "5. Microphone & Voice Recognition",
            description = "Required for 'Hey Jarvis' wake word and friendly Hinglish voice conversation.",
            isGranted = isMicGranted,
            buttonText = "Open App Permissions",
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        )
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFF0A261D) else Color(0xFF1A1423)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (isGranted) "GRANTED ✅" else "REQUIRED ⚠️",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isGranted) Color(0xFF2E7D5B) else Color(0xFFFFAB00)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = description, fontSize = 12.sp, color = Color(0xFFCFD8DC))
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) Color(0xFF1E3A34) else Color(0xFFB85C38)
                )
            ) {
                Text(
                    text = if (isGranted) "Re-check / Configure" else buttonText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AiBrainSettingsScreen(
    prefs: JarvisPreferences,
    onSettingsUpdated: () -> Unit
) {
    var selectedProvider by remember { mutableStateOf(prefs.provider) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    // 1.4.3: Deepgram STT key (listening only — speaking stays
    // Kokoro). Saved by the same SAVE button.
    // 1.4.5 FINAL policy: empty = CANNOT LISTEN (system fallback
    // REMOVED entirely — no fallback exists any more). Never
    // displayed, never logged.
    var deepgramKey by remember { mutableStateOf(prefs.deepgramApiKey) }
    var showDeepgramKey by remember { mutableStateOf(false) }
    var modelName by remember { mutableStateOf(prefs.modelName) }
    var hfToken by remember { mutableStateOf(prefs.hfToken) }
    var languageMode by remember { mutableStateOf(prefs.languageMode) }
    var personaPrompt by remember { mutableStateOf(prefs.personaPrompt) }

    var wakeWordEnabled by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var overlayEnabled by remember { mutableStateOf(prefs.floatingOverlayEnabled) }
    var confirmSensitive by remember { mutableStateOf(prefs.confirmSensitiveActions) }
    var speakIntermediate by remember { mutableStateOf(prefs.speakIntermediateSteps) }
    var kokoroOn by remember { mutableStateOf(prefs.kokoroEnabled) }
    var visionMode by remember { mutableStateOf(prefs.visionModeEnabled) }
    var autoAnnounceNotifs by remember { mutableStateOf(prefs.autoAnnounceNotifications) }
    var maxSteps by remember { mutableFloatStateOf(prefs.maxAutonomousSteps.toFloat()) }
    var stepDelay by remember { mutableFloatStateOf(prefs.stepDelayMs.toFloat()) }
    var saveBanner by remember { mutableStateOf("") }
    // Captured at composition level for use inside onClick handlers
    // (CompositionLocals cannot be read inside a non-composable lambda).
    val aiBrainContext = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "AI Brain, Voice & Safety Config",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )

        // Provider Selector
        Text("1. Select AI Provider:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiProvider.entries.forEach { provider ->
                FilterChip(
                    selected = selectedProvider == provider,
                    onClick = {
                        selectedProvider = provider
                        modelName = provider.defaultModel
                    },
                    label = { Text(provider.displayName, fontSize = 12.sp) }
                )
            }
        }

        // FREE on-device model (no key, no card, no server)
        run {
            val localContext = LocalContext.current
            val localScope = rememberCoroutineScope()
            var localDownloaded by remember {
                mutableStateOf(LocalLlmManager.isDownloaded(localContext))
            }
            var localProgress by remember { mutableStateOf("") }
            var localDownloading by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Free Local Model — Gemma 3 270M (Offline Chat, HF Token Needed)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Ye model phone ke andar hi chalta hai (LiteRT-LM, Gemma 3 270M Q8, ~304 MB, ek baar download). " +
                            "Casual baat-cheet isse tez aur bina internet ke hogi. " +
                            "Ye gated model hai — download ke liye owner ka token app me hi hota hai, tumhe koi token daalne ki zaroorat nahi hai. Agar tum apna token daalna chaho to neeche wala field optional hai (tumhara token owner wale se pehle use hoga). Phone ke steps/kaam (WhatsApp/YouTube/action JSON) AI cloud Lite model (Gemini/Groq) se karega, kyunki 270M model multi-step JSON reliably nahi de sakta.",
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (!LocalLlmManager.isDeviceSupported()) {
                            "Is phone ka processor local model ko support nahi karta (sirf arm64/x86_64)."
                        } else if (localDownloaded) {
                            "Status: Downloaded ✅ (${LocalLlmManager.downloadedBytes(localContext) / 1_000_000L} MB) — provider me Local chuno aur baat karo."
                        } else {
                            "Status: Not downloaded — Wi-Fi par download karo, ek baar me ~304 MB lagega."
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (localProgress.isNotBlank()) {
                        Text(localProgress, fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = hfToken,
                        onValueChange = { hfToken = it },
                        label = { Text("Hugging Face Token (optional — khali chhodo to owner ka token use hoga)", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        enabled = LocalLlmManager.isDeviceSupported() && !localDownloading,
                        onClick = {
                            if (localDownloaded) {
                                selectedProvider = AiProvider.LOCAL
                                modelName = AiProvider.LOCAL.defaultModel
                            } else {
                                localDownloading = true
                                localProgress = "Downloading… 0 / ~304 MB"
                                localScope.launch {
                                    val result = LocalLlmManager.download(localContext, hfToken) { mb, total ->
                                        localScope.launch { localProgress = "Downloading… $mb / $total MB" }
                                    }
                                    localDownloading = false
                                    if (result.isSuccess) {
                                        localDownloaded = true
                                        localProgress = "Download complete ✅ — ab provider me Local chuno."
                                        selectedProvider = AiProvider.LOCAL
                                        modelName = AiProvider.LOCAL.defaultModel
                                    } else {
                                        localProgress = "Download fail: ${result.exceptionOrNull()?.message}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (localDownloaded) "Use Local Model" else "Download Free Local Model (~304 MB)")
                    }
                }
            }
        }

        // Kokoro offline voice (1.4.0, Kokoro-only): hf_alpha Hindi
        // female, phone par hi chalti hai — bina key, bina quota,
        // bina internet. Model APK me bundled hai: pehli baar app
        // khulte hi assets se copy hota hai (koi download nahi).
        // Yahan sirf wahi copy dobara chalai ja sakti hai.
        run {
            val kokoroContext = LocalContext.current
            val kokoroScope = rememberCoroutineScope()
            var kokoroReady by remember {
                mutableStateOf(KokoroTtsClient.isDownloaded(kokoroContext))
            }
            var kokoroProgress by remember { mutableStateOf("") }
            var kokoroWorking by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Kokoro Voice — hf_alpha (Offline, Kokoro-only)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Jarvis ki ek hi awaaz: Kokoro hf_alpha Hindi female, phone par hi chalti hai — bina key, bina quota, bina internet. Model APK ke andar bundled hai, isliye koi download nahi hota; pehli baar sirf ~190 MB assets se copy hote hain.",
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (kokoroReady) {
                            "Status: Ready ✅ (${KokoroTtsClient.bundledModelBytes(kokoroContext) / 1_000_000L} MB) — ab Jarvis isi awaaz me bolega (jab Kokoro toggle ON hai)."
                        } else {
                            "Status: Not ready — bundled copy abhi baaki hai. Neeche wala button dabao (koi download nahi, sirf APK assets se copy)."
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (kokoroProgress.isNotBlank()) {
                        Text(kokoroProgress, fontSize = 12.sp)
                    }
                    Button(
                        enabled = !kokoroWorking,
                        onClick = {
                            if (kokoroReady) {
                                kokoroOn = true
                            } else {
                                kokoroWorking = true
                                kokoroProgress = "Checking space…"
                                kokoroScope.launch {
                                    // 1.4.5 (item 1a): setup copy goes
                                    // through :tts (single writer) —
                                    // never a main-process copy.
                                    val ok = TtsProcessClient.ensure(kokoroContext) { stage, copied, total ->
                                        kokoroScope.launch {
                                            kokoroProgress = when (stage) {
                                                "checking_space" -> "Storage check ho raha hai…"
                                                "copying" -> "Copy ho raha hai: $copied / $total MB — pehli baar hi hoga, dobara nahi."
                                                else -> "Ready ✅"
                                            }
                                        }
                                    }
                                    kokoroWorking = false
                                    if (ok) {
                                        kokoroReady = true
                                        kokoroProgress = "Kokoro ready ✅ — ab Jarvis Kokoro awaaz me bolega."
                                        kokoroOn = true
                                        JarvisStateRepository.setKokoroEnabled(true)
                                    } else {
                                        kokoroProgress = "Kokoro setup fail ho gaya."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (kokoroReady) "Use Kokoro Voice" else "Kokoro setup karo (bundled copy, no download)")
                    }
                    // 1.4.5 REDO: reset runs in the :tts process
                    // (TtsProcessClient.reset) — the engine lives
                    // there, so a main-process delete of the shared
                    // model dir under a loaded engine would be a
                    // native use-after-delete. Progress streams
                    // from :tts exactly as before.
                    Button(
                        enabled = !kokoroWorking,
                        onClick = {
                            kokoroWorking = true
                            kokoroProgress = "Reset ho raha hai — purana model delete, dobara copy…"
                            kokoroScope.launch {
                                val ok = TtsProcessClient.reset(kokoroContext) { stage, copied, total ->
                                    kokoroScope.launch {
                                        kokoroProgress = when (stage) {
                                            "checking_space" -> "Storage check ho raha hai…"
                                            "copying" -> "Dobara copy ho raha hai: $copied / $total MB."
                                            else -> "Ready ✅"
                                        }
                                    }
                                }
                                kokoroWorking = false
                                if (ok) {
                                    kokoroReady = true
                                    kokoroProgress = "Kokoro reset complete ✅ — fresh copy taiyaar."
                                    kokoroOn = true
                                    JarvisStateRepository.setKokoroEnabled(true)
                                } else {
                                    kokoroReady = false
                                    kokoroProgress = "Kokoro reset fail ho gaya."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                    ) {
                        Text("Kokoro reset — dobara copy karo")
                    }
                    // 1.4.8 (G8 F4): standalone voice test — before
                    // this, speech was only exercisable via the
                    // greeting or task replies, so on a
                    // breaker-skipped launch there was no way to
                    // test voice alone. This speaks a sample line
                    // through the normal speak path (service
                    // ACTION_SPEAK_TEST). Greyed out unless Kokoro
                    // is ready AND on (OFF would refuse with
                    // KOKORO_OFF, and the OFF card already says so).
                    Button(
                        enabled = kokoroReady && kokoroOn,
                        onClick = {
                            // 1.4.9 (Step 1a): Test Voice had two
                            // fully-silent stages — a greyed button
                            // writes nothing, and a
                            // startForegroundService throw writes
                            // nothing (no try/catch, no marker).
                            // Now the tap itself is marked, the
                            // service-start result is marked, and
                            // a throw is caught + marked — a Test
                            // Voice report can always name the
                            // stage it reached.
                            JarvisStateRepository.addDiagnostic(
                                "tts", "TEST_VOICE_START",
                                "Test Voice tapped in AI Brain Kokoro card."
                            )
                            val testIntent = Intent(
                                aiBrainContext,
                                JarvisForegroundService::class.java
                            ).apply { action = JarvisForegroundService.ACTION_SPEAK_TEST }
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    aiBrainContext.startForegroundService(testIntent)
                                } else {
                                    aiBrainContext.startService(testIntent)
                                }
                            }.onSuccess {
                                JarvisStateRepository.addDiagnostic(
                                    "tts", "TEST_VOICE_SENT",
                                    "Test Voice service start requested (ACTION_SPEAK_TEST)."
                                )
                            }.onFailure {
                                JarvisStateRepository.addDiagnostic(
                                    "tts", "TEST_VOICE_START_FAILED",
                                    "Test Voice service start threw: ${it.javaClass.simpleName} — tap never reached the service."
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test Voice — sample line suno")
                    }
                    if (!kokoroOn) {
                        Text("Test ke liye Kokoro ON karo.", fontSize = 12.sp)
                    }
                }
            }
        }

        // API Key Input
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("${selectedProvider.name} API Key") },
            placeholder = { Text("Paste your API key here...") },
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    text = if (showApiKey) "HIDE" else "SHOW",
                    fontSize = 11.sp,
                    color = Color(0xFFB85C38),
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .clickable { showApiKey = !showApiKey }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // 1.4.3: Deepgram STT key — SUNNE ke liye (transcription),
        // bolne ke liye NAHI (bolna hamesha Kokoro).
        // 1.4.5 FINAL policy: key khaali ho to mic bilkul nahi
        // chalega (system fallback REMOVED — koi fallback nahi).
        OutlinedTextField(
            value = deepgramKey,
            onValueChange = { deepgramKey = it },
            label = { Text("Deepgram API Key (sunne ke liye, STT only)") },
            placeholder = { Text("Paste Deepgram key here — bina iske mic nahi chalega (koi fallback nahi)") },
            visualTransformation = if (showDeepgramKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    text = if (showDeepgramKey) "HIDE" else "SHOW",
                    fontSize = 11.sp,
                    color = Color(0xFFB85C38),
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .clickable { showDeepgramKey = !showDeepgramKey }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Model Input
        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            label = { Text("Model Name (e.g., gemini-3.8-flash, gpt-4o-mini)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Language Mode Chips
        Text("2. Conversation Language:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Hinglish", "Hindi", "English").forEach { lang ->
                FilterChip(
                    selected = languageMode == lang,
                    onClick = { languageMode = lang },
                    label = { Text(lang) }
                )
            }
        }

        // Friend Persona Editor
        OutlinedTextField(
            value = personaPrompt,
            onValueChange = { personaPrompt = it },
            label = { Text("Friend Personality & Tone Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        // Toggles Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingToggleRow(
                    title = "Wake Word ('Hey Jarvis')",
                    subtitle = "Continuously listen in background for 'Jarvis'",
                    checked = wakeWordEnabled,
                    onCheckedChange = { wakeWordEnabled = it }
                )
                SettingToggleRow(
                    title = "Floating HUD Overlay Pill",
                    subtitle = "Show live step indicator & mic bubble over all apps",
                    checked = overlayEnabled,
                    onCheckedChange = { overlayEnabled = it }
                )
                SettingToggleRow(
                    title = "Multimodal Screen Vision (Screenshot Sight)",
                    subtitle = "Let Jarvis visually see images, memes, Reels & custom canvas UIs",
                    checked = visionMode,
                    onCheckedChange = { visionMode = it }
                )
                SettingToggleRow(
                    title = "Auto-Announce WhatsApp / SMS / Calls",
                    subtitle = "Speak incoming messages aloud when idle & allow direct voice reply",
                    checked = autoAnnounceNotifs,
                    onCheckedChange = { autoAnnounceNotifs = it }
                )
                SettingToggleRow(
                    title = "Safe Mode (Payment & Delete Guard)",
                    subtitle = "Ask voice confirmation before clicking Pay Now / UPI PIN",
                    checked = confirmSensitive,
                    onCheckedChange = { confirmSensitive = it }
                )
                SettingToggleRow(
                    title = "Kokoro Voice — hf_alpha (offline, Hindi)",
                    subtitle = "Jarvis ki ek hi awaaz, phone par hi (bundled copy ke baad, bina key/quota)",
                    checked = kokoroOn,
                    onCheckedChange = { kokoroOn = it }
                )
                SettingToggleRow(
                    title = "Speak Every Step (slower)",
                    subtitle = "OFF = fast: only final answers & questions are spoken, steps show in chat",
                    checked = speakIntermediate,
                    onCheckedChange = { speakIntermediate = it }
                )
            }
        }

        // Sliders for Autonomous Loop
        Text(
            text = "Max Autonomous Steps per Command: ${maxSteps.toInt()} steps",
            fontSize = 13.sp
        )
        Slider(
            value = maxSteps,
            onValueChange = { maxSteps = it },
            valueRange = 5f..80f
        )

        Text(
            text = "Step Wait Delay (UI Load Time): ${stepDelay.toInt()} ms",
            fontSize = 13.sp
        )
        Slider(
            value = stepDelay,
            onValueChange = { stepDelay = it },
            valueRange = 300f..2500f
        )

        // B-L12: the old Voice Speech Speed slider is REMOVED —
        // it only ever tuned system TTS, which no longer exists
        // (Kokoro-only build, fixed speed 1.0f). A slider that
        // saves a dead pref is worse than no slider.
        Text(
            text = "Voice speed: Kokoro fixed speed par bolta hai " +
                "(speed slider hataya gaya — wo sirf purani system " +
                "voice ko control karta tha, Kokoro ko nahi).",
            fontSize = 13.sp
        )

        Button(
            onClick = {
                // 1.4.5 (item 4): capture the OLD Deepgram key
                // BEFORE writing — a key change while the loop runs
                // must restart the loop with the new key (the old
                // code only sent ACTION_REFRESH_OVERLAY, which
                // refreshes the overlay, NOT the loop — so the new
                // key had no effect until a restart).
                val oldDeepgramKey = prefs.deepgramApiKey
                prefs.provider = selectedProvider
                prefs.hfToken = hfToken
                prefs.apiKey = apiKey
                prefs.deepgramApiKey = deepgramKey
                prefs.modelName = modelName
                prefs.languageMode = languageMode
                prefs.personaPrompt = personaPrompt
                prefs.wakeWordEnabled = wakeWordEnabled
                prefs.floatingOverlayEnabled = overlayEnabled
                prefs.visionModeEnabled = visionMode
                prefs.autoAnnounceNotifications = autoAnnounceNotifs
                prefs.confirmSensitiveActions = confirmSensitive
                prefs.speakIntermediateSteps = speakIntermediate
                prefs.kokoroEnabled = kokoroOn
                prefs.maxAutonomousSteps = maxSteps.toInt()
                prefs.stepDelayMs = stepDelay.toLong()
                onSettingsUpdated()
                // 1.4.5 (item 6): the OFF card reads this flow —
                // Save must publish it, or the card stays stale.
                JarvisStateRepository.setKokoroEnabled(kokoroOn)
                // 1.4.5 (item 4): key-change restart — only when the
                // key actually changed AND the loop is live (the
                // repository flag is the live-loop signal). RETRY
                // always stops first, then starts, so it restarts
                // with the new key even when enabled. Loop not live
                // -> nothing (next start uses the new key). Blank
                // new key while live -> RETRY restarts into the
                // no-key fatal banner path (visible, not silent).
                if (deepgramKey.trim() != oldDeepgramKey.trim() &&
                    JarvisStateRepository.micLoopEnabled.value
                ) {
                    JarvisStateRepository.addDiagnostic(
                        "stt", "KEY_CHANGED_RESTART",
                        "Deepgram key changed while loop live — restarting loop with the new key."
                    )
                    val retryIntent = Intent(
                        aiBrainContext,
                        JarvisForegroundService::class.java
                    ).apply { action = JarvisForegroundService.ACTION_RETRY_MIC }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        aiBrainContext.startForegroundService(retryIntent)
                    } else {
                        aiBrainContext.startService(retryIntent)
                    }
                }
                saveBanner = "Settings Saved & Applied Successfully ✅"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
        ) {
            Text("SAVE & APPLY CONFIGURATION", color = Color.White, fontWeight = FontWeight.Bold)
        }

        if (saveBanner.isNotEmpty()) {
            Text(saveBanner, color = Color(0xFF2E7D5B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        // 1.4.2: "Gemini lagane ke baad bhi kaam nahi karta" had
        // three indistinguishable causes — Save never pressed
        // (fields are remember state until Save), provider still
        // LOCAL Gemma, or a restricted/disabled key (400/403).
        // This card names the SAVED state (not the edited fields)
        // and tests the saved key with one cheap call, showing the
        // HTTP result VERBATIM. The key itself is never shown.
        run {
            val testContext = LocalContext.current
            val testScope = rememberCoroutineScope()
            var testResult by remember { mutableStateOf("") }
            var testBusy by remember { mutableStateOf(false) }
            val lastLlmError by JarvisStateRepository.lastLlmError.collectAsState()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saved AI Brain state (yehi chalta hai, edited fields nahi)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        text = "Provider: ${prefs.provider.displayName} · Model: ${prefs.modelName} · " +
                            "Key saved: ${if (prefs.apiKey.isNotBlank()) "yes" else "NO — key khaali hai"}",
                        fontSize = 12.sp
                    )
                    if (lastLlmError != null) {
                        Text(
                            text = "Last LLM error: $lastLlmError",
                            fontSize = 12.sp,
                            color = Color(0xFFB3261E)
                        )
                    } else {
                        Text(
                            text = "Last LLM error: none recorded",
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D5B)
                        )
                    }
                    Button(
                        enabled = !testBusy,
                        onClick = {
                            testBusy = true
                            testResult = "Testing…"
                            testScope.launch(Dispatchers.IO) {
                                val result = LlmClient(testContext, prefs).testApiKey()
                                testResult = result
                                testBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                    ) {
                        Text("Test key", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (testResult.isNotBlank()) {
                        Text(testResult, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = "Note: fields upar edit karne ke baad SAVE dabana zaroori hai — " +
                            "bina Save ke test purani saved key par chalega.",
                        fontSize = 11.sp,
                        color = Color(0xFF8A7A70)
                    )
                }
            }
        }

        // 1.4.3: Deepgram key test — Gemini Test-key pattern par:
        // ek cheap WS handshake, result VERBATIM (HTTP code +
        // message). Key kabhi dikhayi nahi jaati. Bina Save ke
        // test purani saved key par chalega (upar wala note).
        run {
            val testContext = LocalContext.current
            val testScope = rememberCoroutineScope()
            var dgResult by remember { mutableStateOf("") }
            var dgBusy by remember { mutableStateOf(false) }
            val sttPath by JarvisStateRepository.sttPath.collectAsState()
            val sttReason by JarvisStateRepository.sttPathReason.collectAsState()
            val lastScript by JarvisStateRepository.lastTranscriptScript.collectAsState()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Deepgram STT (sunna) — saved state", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        text = "STT path: $sttPath · $sttReason · Last transcript script: $lastScript · " +
                            "Key saved: ${if (prefs.deepgramApiKey.isNotBlank()) "yes" else "NO — mic nahi chalega, key daalo aur SAVE dabao"}",
                        fontSize = 12.sp
                    )
                    Button(
                        enabled = !dgBusy,
                        onClick = {
                            dgBusy = true
                            dgResult = "Testing…"
                            testScope.launch(Dispatchers.IO) {
                                val key = prefs.deepgramApiKey
                                dgResult = if (key.isBlank()) {
                                    "Deepgram key saved nahi hai — pehle key daalo aur SAVE dabao."
                                } else {
                                    DeepgramSttClient(key).testAuth()
                                }
                                dgBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                    ) {
                        Text("Test Deepgram key", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (dgResult.isNotBlank()) {
                        Text(dgResult, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3B2F2A))
            Text(subtitle, fontSize = 11.sp, color = Color(0xFF8A7A70))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun MemoryRoutinesLogsScreen(
    prefs: JarvisPreferences,
    onRunRoutineNow: (String) -> Unit
) {
    var memories by remember { mutableStateOf(prefs.getMemories()) }
    var newMemoryText by remember { mutableStateOf("") }

    var routines by remember { mutableStateOf(prefs.getRoutines()) }
    var newTrigger by remember { mutableStateOf("") }
    var newRoutinePrompt by remember { mutableStateOf("") }

    val logs by JarvisStateRepository.executionLogs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Friend Memory, Voice Macros & Logs",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )

        // Diagnostics FIRST (1.4.3): moved to the TOP of this tab —
        // it was Section 4 and users never scrolled far enough to
        // see it. INSIDE this
        // tab on purpose, NOT a new bottom-bar tab (8 already crowd
        // small screens). Nothing here auto-uploads or phones home:
        // the user copies the report manually and pastes it back.
        run {
            val diagContext = LocalContext.current
            val diagnostics by JarvisStateRepository.diagnostics.collectAsState()
            val micFatalState by JarvisStateRepository.micFatal.collectAsState()
            val micLoopOn by JarvisStateRepository.micLoopEnabled.collectAsState()
            val micLive by JarvisStateRepository.micListening.collectAsState()
            // 1.4.8 (G8 F2): live wake state for the report's mode.
            val wakeLive by JarvisStateRepository.wakeListening.collectAsState()
            var copiedNote by remember { mutableStateOf("") }
            var lastCrash by remember {
                mutableStateOf(JarvisDiagnosticsFile.readLastCrash())
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Diagnostics / Bug report", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFB85C38))
                    Text(
                        text = "Kuch gadbad ho to COPY dabao aur report mujhe bhej do. " +
                            "Isme kabhi key, token, poori licence key, contact, message " +
                            "ya photo nahi aata — sirf states, codes aur last lines.",
                        fontSize = 12.sp,
                        color = Color(0xFF8A7A70)
                    )
                    Text(
                        text = "Mic: loop=${if (micLoopOn) "ON" else "OFF"} · listening=${if (micLive) "YES" else "NO"} · " +
                            "last error code=${run { val f = micFatalState; if (f == null || f.code < 0) "none" else f.code.toString() }}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Overlay now: ${if (diagContext.let {
                            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                                android.provider.Settings.canDrawOverlays(it)
                        }) "GRANTED" else "NOT GRANTED"}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Diagnostics lines: ${diagnostics.size} · Last crash: ${lastCrash?.take(160) ?: "none"}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val report = buildDiagnosticsReport(
                                    diagContext, prefs, diagnostics,
                                    micFatalState, micLoopOn, micLive, wakeLive, lastCrash
                                )
                                val cm = diagContext.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                cm.setPrimaryClip(
                                    ClipData.newPlainText("Jarvis diagnostics", report)
                                )
                                copiedNote = "Copied — ab mujhe paste kar do."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                        ) {
                            Text("Copy bug report", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                JarvisStateRepository.clearDiagnostics()
                                lastCrash = null
                                copiedNote = "Diagnostics cleared."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                        ) {
                            Text("Clear", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (copiedNote.isNotBlank()) {
                        Text(copiedNote, fontSize = 12.sp, color = Color(0xFF2E7D5B))
                    }
                    diagnostics.takeLast(20).reversed().forEach { entry ->
                        Text(
                            text = "[${entry.time}] ${entry.area}/${entry.code}: ${entry.message}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8A7A70)
                        )
                    }
                }
            }
        }

        // Section 1: Custom Voice Routines (Macros)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Custom Voice Routines (1-Command Multi-Step Macros)", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D5B))
                routines.forEach { routine ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFF1DC))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Say: \"${routine.triggerPhrase}\"", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB85C38))
                            Text(routine.actionPrompt, fontSize = 11.sp, color = Color.LightGray)
                        }
                        IconButton(onClick = { onRunRoutineNow(routine.actionPrompt) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color(0xFF2E7D5B))
                        }
                        IconButton(onClick = {
                            prefs.removeRoutine(routine.id)
                            routines = prefs.getRoutines()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                        }
                    }
                }

                OutlinedTextField(
                    value = newTrigger,
                    onValueChange = { newTrigger = it },
                    label = { Text("Voice Trigger (e.g. gym mode)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newRoutinePrompt,
                    onValueChange = { newRoutinePrompt = it },
                    label = { Text("Multi-Step Instruction for Jarvis") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (newTrigger.isNotBlank() && newRoutinePrompt.isNotBlank()) {
                            prefs.addRoutine(newTrigger, newRoutinePrompt)
                            routines = prefs.getRoutines()
                            newTrigger = ""
                            newRoutinePrompt = ""
                        }
                    }
                ) {
                    Text("+ Add Voice Routine")
                }
            }
        }

        // Section 2: Long-Term Friend Memory
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Long-Term Friend Memory (Facts Jarvis Remembers)", fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F))
                memories.forEach { note ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("• $note", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            prefs.removeMemory(note)
                            memories = prefs.getMemories()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newMemoryText,
                        onValueChange = { newMemoryText = it },
                        placeholder = { Text("Add fact (e.g. Mom's contact is Maa)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (newMemoryText.isNotBlank()) {
                            prefs.addMemory(newMemoryText)
                            memories = prefs.getMemories()
                            newMemoryText = ""
                        }
                    }) {
                        Text("Add")
                    }
                }
            }
        }

        // Section 3: Live Execution Console Logs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Live Step & Accessibility Telemetry Logs", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB85C38))
                    Text(
                        text = "CLEAR",
                        fontSize = 11.sp,
                        color = Color(0xFFFF5252),
                        modifier = Modifier.clickable { JarvisStateRepository.clearLogs() }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (logs.isEmpty()) {
                    Text("No logs yet.", fontSize = 11.sp, color = Color.Gray)
                } else {
                    logs.takeLast(35).reversed().forEach { line ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF80CBC4),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }


    }
}

/**
 * 1.4.2 bug-report text. ALLOWED: app version/versionCode, Android
 * SDK + device model, timestamp, mic state, live overlay state +
 * last overlay failure, licence ACTIVE/UNBOUND only (key masked to
 * first/last 4 chars AT MOST — blank when none saved), provider +
 * model name, last ~50 diagnostics lines, last crash if any.
 * FORBIDDEN and never built here: API keys, HF token, full licence
 * key, passwords, contact names/numbers, message/notification text,
 * URLs containing keys, screen-tree content, image data.
 */
private fun buildDiagnosticsReport(
    context: Context,
    prefs: JarvisPreferences,
    diagnostics: List<com.jarvis.autonomous.data.DiagnosticEntry>,
    micFatal: com.jarvis.autonomous.data.MicFatalState?,
    micLoopOn: Boolean,
    micLive: Boolean,
    wakeLive: Boolean,
    lastCrash: String?
): String {
    val sb = StringBuilder()
    sb.appendLine("=== Jarvis bug report ===")
    sb.appendLine("App: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
    sb.appendLine("Android SDK: ${Build.VERSION.SDK_INT} · Model: ${Build.MODEL}")
    sb.appendLine(
        "Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    )
    sb.appendLine(
        // 1.4.8: mode disambiguates the loop-OFF + listening-YES
        // confusion (22:07:33 report): "loop" = command loop live,
        // "wake" = loop off AND a wake session is actually
        // running (speech without "Jarvis" is ignored by design
        // — tap mic for full commands), "off" = neither.
        // 1.4.8 (G8 F2): "wake" now reads the LIVE wake flow,
        // not the wake-word preference (a dead/never-started
        // session must never report as "wake"). The listening
        // flag itself is race-guarded at its post site (see
        // AlwaysOnMicManager onOpen).
        "Mic: loop=${if (micLoopOn) "ON" else "OFF"} · listening=${if (micLive) "YES" else "NO"} · " +
            "mode=${if (micLoopOn) "loop" else if (wakeLive) "wake" else "off"} · " +
            "last error code=${if (micFatal == null || micFatal.code < 0) "none" else micFatal.code.toString()} · " +
            "last reason=${micFatal?.reason ?: "none"}"
    )
    val overlayNow = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        android.provider.Settings.canDrawOverlays(context)
    val lastOverlayFailure = diagnostics.lastOrNull { it.area == "overlay" }
    sb.appendLine(
        "Overlay: now=${if (overlayNow) "GRANTED" else "NOT GRANTED"} · " +
            "last failure=${lastOverlayFailure?.let { "${it.code}: ${it.message}" } ?: "none"}"
    )
    val licencePrefs = context.getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE)
    val rawKey = licencePrefs.getString("licence_key", "").orEmpty()
    val activated = licencePrefs.getBoolean("licence_activated", false)
    val maskedKey = when {
        rawKey.isBlank() -> "none saved"
        rawKey.length <= 8 -> "****"
        else -> rawKey.take(4) + "…" + rawKey.takeLast(4)
    }
    sb.appendLine(
        "Licence: activated=$activated · key=$maskedKey " +
            "(full key NEVER included)"
    )
    sb.appendLine("Provider: ${prefs.provider.displayName} · Model: ${prefs.modelName} · Key saved: ${prefs.apiKey.isNotBlank()}")
    // 1.4.3 gates: STT path + transcript script + last lifecycle
    // markers (decide crash vs agent-HOME vs system kill), then
    // the PREVIOUS SESSION's persisted file lines — the in-memory
    // list above dies with the process, but diagnostics.jsonl
    // survives it, so a post-death report still holds the trail.
    val sttPathNow = com.jarvis.autonomous.data.JarvisStateRepository.sttPath.value
    val sttReasonNow = com.jarvis.autonomous.data.JarvisStateRepository.sttPathReason.value
    val scriptNow = com.jarvis.autonomous.data.JarvisStateRepository.lastTranscriptScript.value
    sb.appendLine("STT: path=$sttPathNow · reason=$sttReasonNow · last transcript script=$scriptNow")
    val lastLife = diagnostics.lastOrNull { it.code.startsWith("LIFE_") }
    val lastSpk = diagnostics.lastOrNull { it.code.startsWith("SPK_") }
    val lastNav = diagnostics.lastOrNull { it.code.startsWith("NAV_") }
    sb.appendLine(
        "Last lifecycle: ${lastLife?.let { "${it.code} @ ${it.time} (${it.message})" } ?: "none"} · " +
            "Last speak: ${lastSpk?.let { "${it.code} @ ${it.time} (${it.message})" } ?: "none"} · " +
            "Last nav: ${lastNav?.let { "${it.code} @ ${it.time} (${it.message})" } ?: "none"}"
    )
    sb.appendLine("--- last diagnostics (max 50) ---")
    diagnostics.takeLast(50).forEach { entry ->
        sb.appendLine("[${entry.time}] ${entry.area}/${entry.code}: ${entry.message}")
    }
    sb.appendLine("--- previous session (persisted file, survives process death, last 30) ---")
    com.jarvis.autonomous.data.JarvisDiagnosticsFile.readLast(30).forEach { line ->
        sb.appendLine(line)
    }
    sb.appendLine("--- last crash ---")
    sb.appendLine(lastCrash ?: "none")
    sb.appendLine("=== end report (no keys, no contacts, no messages) ===")
    return sb.toString()
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = "${context.packageName}/${JarvisAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(expectedComponentName, ignoreCase = true) ||
        JarvisAccessibilityService.instance != null
}
