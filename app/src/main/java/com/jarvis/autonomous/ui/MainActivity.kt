package com.jarvis.autonomous.ui

import android.Manifest
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import kotlinx.coroutines.launch
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
import com.jarvis.autonomous.R
import com.jarvis.autonomous.agent.LocalLlmManager
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.voice.KokoroTtsClient
import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.service.JarvisForegroundService
import com.jarvis.autonomous.service.JarvisWhiteboardService

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
        startJarvisBackgroundService()

        setContent {
            MaterialTheme(colorScheme = JarvisColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    JarvisMainApp(
                        onStartVoice = { sendServiceAction(JarvisForegroundService.ACTION_START_LISTENING) },
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
    onRefreshServiceSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { JarvisPreferences(context) }
    var selectedTab by remember { mutableIntStateOf(0) }

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
    LaunchedEffect(Unit) {
        if (!KokoroTtsClient.isDownloaded(context)) {
            kokoroChecking = true
            val result = KokoroTtsClient.ensureAssetsReady(context) { stage, copied, total ->
                kokoroStage = stage
                kokoroCopiedMb = copied
                kokoroTotalMb = total
            }
            kokoroChecking = false
            if (result.isFailure) {
                kokoroError = result.exceptionOrNull()?.message
                    ?: "Kokoro setup fail ho gaya — dobara try karo."
            } else {
                kokoroError = null
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
            NavigationBar(containerColor = Color(0xFFFFFFFF)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Command") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Security, contentDescription = null) },
                    label = { Text("Permissions") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("AI Brain") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Memory & Logs") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Projects") }
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    icon = { Icon(Icons.Default.Send, contentDescription = null) },
                    label = { Text("Browser") }
                )
                NavigationBarItem(
                    selected = selectedTab == 6,
                    onClick = { selectedTab = 6 },
                    icon = { Icon(Icons.Default.Security, contentDescription = null) },
                    label = { Text("Licence") }
                )
                NavigationBarItem(
                    selected = selectedTab == 7,
                    onClick = { selectedTab = 7 },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    label = { Text("Whiteboard") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> CommandCenterScreen(
                    onStartVoice = onStartVoice,
                    onStopTask = onStopTask,
                    onSendTextCommand = onSendTextCommand,
                    onNavigatePermissions = { selectedTab = 1 },
                    voiceDisabled = micPermissionDenied
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
                                            val result = KokoroTtsClient.ensureAssetsReady(context) { stage, copied, total ->
                                                kokoroStage = stage
                                                kokoroCopiedMb = copied
                                                kokoroTotalMb = total
                                            }
                                            kokoroChecking = false
                                            if (result.isFailure) {
                                                kokoroError = result.exceptionOrNull()?.message
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
    voiceDisabled: Boolean = false
) {
    val agentState by JarvisStateRepository.agentState.collectAsState()
    val currentGoal by JarvisStateRepository.currentGoal.collectAsState()
    val stepInfo by JarvisStateRepository.currentStepInfo.collectAsState()
    val isAccConnected by JarvisStateRepository.isAccessibilityConnected.collectAsState()
    val chatHistory by JarvisStateRepository.chatHistory.collectAsState()

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
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFFF1DC))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chatHistory, key = { it.id }) { item ->
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

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Command Input Bar
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
fun ArcReactorOrb(
    state: AgentRunState,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (state == AgentRunState.IDLE) 1.03f else 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == AgentRunState.IDLE) 1800 else 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val coreColor by animateColorAsState(
        targetValue = when (state) {
            AgentRunState.IDLE -> Color(0xFFB85C38)
            AgentRunState.LISTENING -> Color(0xFF2E7D5B)
            AgentRunState.THINKING -> Color(0xFFD500F9)
            AgentRunState.EXECUTING_STEP -> Color(0xFFFFAB00)
            AgentRunState.WAITING_USER_REPLY -> Color(0xFF2E7D5B)
            AgentRunState.SPEAKING -> Color(0xFF2979FF)
        },
        label = "color"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(128.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        coreColor.copy(alpha = 0.45f),
                        coreColor.copy(alpha = 0.12f),
                        Color.Transparent
                    )
                )
            )
            .border(2.dp, coreColor, CircleShape)
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Color(0xFF071324))
                .border(3.dp, coreColor, CircleShape)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Tap to Speak",
                    tint = coreColor,
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    text = if (state == AgentRunState.IDLE) "TAP TO SPEAK" else "TAP TO STOP",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
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
                                    val result = KokoroTtsClient.ensureAssetsReady(kokoroContext) { stage, copied, total ->
                                        kokoroScope.launch {
                                            kokoroProgress = when (stage) {
                                                "checking_space" -> "Storage check ho raha hai…"
                                                "copying" -> "Copy ho raha hai: $copied / $total MB — pehli baar hi hoga, dobara nahi."
                                                else -> "Ready ✅"
                                            }
                                        }
                                    }
                                    kokoroWorking = false
                                    if (result.isSuccess) {
                                        kokoroReady = true
                                        kokoroProgress = "Kokoro ready ✅ — ab Jarvis Kokoro awaaz me bolega."
                                        kokoroOn = true
                                    } else {
                                        kokoroProgress = result.exceptionOrNull()?.message
                                            ?: "Kokoro setup fail ho gaya."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (kokoroReady) "Use Kokoro Voice" else "Kokoro setup karo (bundled copy, no download)")
                    }
                    // B-C1 reset path: corrupt copy? Delete the
                    // whole model dir and re-copy bundled assets
                    // fresh — this is the Settings retry that
                    // actually fixes a bad copy, not just re-checks it.
                    Button(
                        enabled = !kokoroWorking,
                        onClick = {
                            kokoroWorking = true
                            kokoroProgress = "Reset ho raha hai — purana model delete, dobara copy…"
                            kokoroScope.launch {
                                val result = KokoroTtsClient.resetAndCopy(kokoroContext) { stage, copied, total ->
                                    kokoroScope.launch {
                                        kokoroProgress = when (stage) {
                                            "checking_space" -> "Storage check ho raha hai…"
                                            "copying" -> "Dobara copy ho raha hai: $copied / $total MB."
                                            else -> "Ready ✅"
                                        }
                                    }
                                }
                                kokoroWorking = false
                                if (result.isSuccess) {
                                    kokoroReady = true
                                    kokoroProgress = "Kokoro reset complete ✅ — fresh copy taiyaar."
                                    kokoroOn = true
                                } else {
                                    kokoroReady = false
                                    kokoroProgress = result.exceptionOrNull()?.message
                                        ?: "Kokoro reset fail ho gaya."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                    ) {
                        Text("Kokoro reset — dobara copy karo")
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
                prefs.provider = selectedProvider
                prefs.hfToken = hfToken
                prefs.apiKey = apiKey
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

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = "${context.packageName}/${JarvisAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(expectedComponentName, ignoreCase = true) ||
        JarvisAccessibilityService.instance != null
}
