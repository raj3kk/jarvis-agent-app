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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.service.JarvisForegroundService

private val JarvisColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    secondary = Color(0xFF00E676),
    tertiary = Color(0xFFD500F9),
    background = Color(0xFF050A14),
    surface = Color(0xFF0D1627),
    onPrimary = Color.Black,
    onBackground = Color(0xFFE6F7FF),
    onSurface = Color(0xFFE6F7FF)
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

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

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

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF091120)) {
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
                    onNavigatePermissions = { selectedTab = 1 }
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
            }
        }
    }
}

@Composable
fun CommandCenterScreen(
    onStartVoice: () -> Unit,
    onStopTask: () -> Unit,
    onSendTextCommand: (String) -> Unit,
    onNavigatePermissions: () -> Unit
) {
    val agentState by JarvisStateRepository.agentState.collectAsState()
    val currentGoal by JarvisStateRepository.currentGoal.collectAsState()
    val stepInfo by JarvisStateRepository.currentStepInfo.collectAsState()
    val isAccConnected by JarvisStateRepository.isAccessibilityConnected.collectAsState()
    val chatHistory by JarvisStateRepository.chatHistory.collectAsState()

    var textInput by remember { mutableStateOf("") }

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
                    color = Color(0xFF00E5FF)
                )
                Text(
                    text = "Autonomous Multi-Step Mobile Agent",
                    fontSize = 12.sp,
                    color = Color(0xFF8AB4F8)
                )
            }

            // Accessibility status badge
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isAccConnected) Color(0xFF0B3B24) else Color(0xFF3E1818),
                modifier = Modifier.clickable { onNavigatePermissions() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAccConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccConnected) Color(0xFF00E676) else Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAccConnected) "Screen Control ON" else "Enable Accessibility",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Animated Arc Reactor Core
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            ArcReactorOrb(
                state = agentState,
                onClick = {
                    if (agentState == AgentRunState.IDLE) {
                        onStartVoice()
                    } else {
                        onStopTask()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Live Step Telemetry Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A)),
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
                        color = Color(0xFF00E5FF)
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
                        color = Color(0xFFFFD54F),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stepInfo,
                    fontSize = 13.sp,
                    color = Color(0xFFE0F7FA)
                )
            }
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
                .background(Color(0xFF08111E))
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
                        color = if (isUser) Color(0xFF004D61) else Color(0xFF132238),
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
                                    color = if (isUser) Color(0xFF80DEEA) else Color(0xFF00E676)
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
                                color = Color.White
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
                    .background(Color(0xFF00E5FF))
            ) {
                Icon(
                    imageVector = if (textInput.isNotBlank()) Icons.Default.Send else Icons.Default.Mic,
                    contentDescription = "Send or Speak",
                    tint = Color.Black
                )
            }
        }
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
            AgentRunState.IDLE -> Color(0xFF00E5FF)
            AgentRunState.LISTENING -> Color(0xFF00E676)
            AgentRunState.THINKING -> Color(0xFFD500F9)
            AgentRunState.EXECUTING_STEP -> Color(0xFFFFAB00)
            AgentRunState.WAITING_USER_REPLY -> Color(0xFF00E676)
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
            color = Color(0xFF00E5FF)
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
                    color = if (isGranted) Color(0xFF00E676) else Color(0xFFFFAB00)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = description, fontSize = 12.sp, color = Color(0xFFCFD8DC))
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) Color(0xFF1E3A34) else Color(0xFF00E5FF)
                )
            ) {
                Text(
                    text = if (isGranted) "Re-check / Configure" else buttonText,
                    color = if (isGranted) Color.White else Color.Black,
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
    var languageMode by remember { mutableStateOf(prefs.languageMode) }
    var personaPrompt by remember { mutableStateOf(prefs.personaPrompt) }

    var wakeWordEnabled by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var overlayEnabled by remember { mutableStateOf(prefs.floatingOverlayEnabled) }
    var confirmSensitive by remember { mutableStateOf(prefs.confirmSensitiveActions) }
    var visionMode by remember { mutableStateOf(prefs.visionModeEnabled) }
    var autoAnnounceNotifs by remember { mutableStateOf(prefs.autoAnnounceNotifications) }
    var maxSteps by remember { mutableFloatStateOf(prefs.maxAutonomousSteps.toFloat()) }
    var stepDelay by remember { mutableFloatStateOf(prefs.stepDelayMs.toFloat()) }
    var voiceSpeed by remember { mutableFloatStateOf(prefs.voiceSpeed) }
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
            color = Color(0xFF00E5FF)
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
                    color = Color(0xFF00E5FF),
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
            label = { Text("Model Name (e.g., gemini-2.5-flash, gpt-4o-mini)") },
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A))
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
                    title = "Auto-Announce WhatsApp / SMS",
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
            valueRange = 600f..3500f
        )

        Text(
            text = "Voice Speech Speed: ${"%.2f".format(voiceSpeed)}x",
            fontSize = 13.sp
        )
        Slider(
            value = voiceSpeed,
            onValueChange = { voiceSpeed = it },
            valueRange = 0.7f..1.6f
        )

        Button(
            onClick = {
                prefs.provider = selectedProvider
                prefs.apiKey = apiKey
                prefs.modelName = modelName
                prefs.languageMode = languageMode
                prefs.personaPrompt = personaPrompt
                prefs.wakeWordEnabled = wakeWordEnabled
                prefs.floatingOverlayEnabled = overlayEnabled
                prefs.visionModeEnabled = visionMode
                prefs.autoAnnounceNotifications = autoAnnounceNotifs
                prefs.confirmSensitiveActions = confirmSensitive
                prefs.maxAutonomousSteps = maxSteps.toInt()
                prefs.stepDelayMs = stepDelay.toLong()
                prefs.voiceSpeed = voiceSpeed
                onSettingsUpdated()
                saveBanner = "Settings Saved & Applied Successfully ✅"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
        ) {
            Text("SAVE & APPLY CONFIGURATION", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        if (saveBanner.isNotEmpty()) {
            Text(saveBanner, color = Color(0xFF00E676), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(subtitle, fontSize = 11.sp, color = Color(0xFF90A4AE))
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
            color = Color(0xFF00E5FF)
        )

        // Section 1: Custom Voice Routines (Macros)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Custom Voice Routines (1-Command Multi-Step Macros)", fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                routines.forEach { routine ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF14263B))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Say: \"${routine.triggerPhrase}\"", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF00E5FF))
                            Text(routine.actionPrompt, fontSize = 11.sp, color = Color.LightGray)
                        }
                        IconButton(onClick = { onRunRoutineNow(routine.actionPrompt) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color(0xFF00E676))
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A))
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070E17))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Live Step & Accessibility Telemetry Logs", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF00E5FF))
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
