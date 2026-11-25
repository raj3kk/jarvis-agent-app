package com.jarvis.autonomous.shizuku

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.jarvis.autonomous.BuildConfig
import com.jarvis.autonomous.service.JarvisAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * 1.25.0 (Shizuku): the bridge between Jarvis and the Shizuku app.
 *
 * WHAT SHIZUKU IS (honest version): Shizuku is a SEPARATE app with
 * its own privileged server process (started via wireless debugging
 * or root). It CANNOT be bundled or embedded inside Jarvis's APK.
 * What Jarvis CAN do is bind to the Shizuku app the user already
 * installed, get one-time authorization, and then run shell commands
 * as the ADB shell identity (UID 2000) — enough to enable Jarvis's
 * own Accessibility service, whitelist Jarvis from Doze/App Standby,
 * and grant Jarvis's runtime permissions, all with ONE tap.
 *
 * WHAT IT CANNOT DO: it cannot stop the user (or the system, on a
 * manual force-stop) from killing Jarvis; it cannot keep the Shizuku
 * server itself alive across a reboot (the server must be restarted
 * via wireless debugging after every reboot — on Android 13+ Shizuku
 * can auto-start on a trusted Wi-Fi); and the shell identity is NOT
 * root (no access to other apps' private files).
 *
 * Design (verified against the Shizuku-API README + the
 * RikkaApps/Shizuku-API demo + an independent source-verified
 * client research note, all on API lib 13.1.5 / server 13.6.0):
 *  - State machine, never cached uid/version outside Ready.
 *  - Listeners registered ONCE in [install] (from
 *    JarvisApplication.onCreate, main process only).
 *  - One-shot shell commands go through a bound UserService
 *    ([ShizukuShellService]) — Shizuku.newProcess() is deprecated
 *    and private; UserService is the documented replacement.
 *  - The service is single-flight (Mutex) and daemon(false): it is
 *    unbound (remove=true) after each protection run, so no
 *    shell-UID process lingers.
 */
object ShizukuManager {

    /** The Shizuku manager app. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Play Store page for the Shizuku app. */
    const val SHIZUKU_PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"

    private const val PERMISSION_REQUEST_CODE = 0x5A1C
    private const val BIND_TIMEOUT_MS = 35_000L
    private const val EXEC_TIMEOUT_MS = 15_000
    private const val BOOT_REAPPLY_TIMEOUT_MS = 25_000L

    private const val JARVIS_PACKAGE = BuildConfig.APPLICATION_ID
    private val ACCESSIBILITY_COMPONENT =
        "$JARVIS_PACKAGE/${JarvisAccessibilityService::class.java.canonicalName}"

    sealed interface ShizukuState {
        /** Shizuku app is not installed at all. */
        data object NotInstalled : ShizukuState

        /** Installed, but its server is not running (reboot / stopped). */
        data object ServerNotRunning : ShizukuState

        /** Server older than v11 — UserService is unsupported. */
        data class Unsupported(val version: Int) : ShizukuState

        /** Binder alive, Jarvis not authorized yet. */
        data class NeedsPermission(val permanentlyDenied: Boolean) : ShizukuState

        /** Binder alive and Jarvis authorized. uid 0 = root, 2000 = adb shell. */
        data class Ready(
            val uid: Int,
            val version: Int,
            val patch: Int,
            val serviceBound: Boolean
        ) : ShizukuState
    }

    /** One protection step of the one-tap run, with its result. */
    data class ProtectionStep(val title: String, val ok: Boolean, val detail: String)

    class ShellException(val exitCode: Int, val stdout: String, val stderr: String) :
        RuntimeException("exit $exitCode: ${stderr.ifBlank { stdout }.trim()}")

    /**
     * 1.26.0 (M0 probe P3): the answer from the shell-uid service
     * about [android.media.AudioManager.isPstnCallAudioInterceptable].
     * [supported] false means the hidden API could not be called at
     * all on this build — the probe reports UNKNOWN, never a pass
     * and never a fail. [interceptable] true is the M0 audio gate
     * together with P2.
     */
    data class CallAudioInterceptableResult(
        val supported: Boolean,
        val interceptable: Boolean,
        val error: String
    )

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.ServerNotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    @Volatile
    private var service: IShizukuShellService? = null

    private var pendingBind: CompletableDeferred<IShizukuShellService>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val execMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
    )
        .daemon(false) // die with the app; default is true (lingers)
        .processNameSuffix("shizuku-shell") // REQUIRED (NPE without it)
        .debuggable(BuildConfig.DEBUG)
        .tag("jarvis-shizuku-shell") // stable across R8 renames
        .version(BuildConfig.VERSION_CODE) // bump => server restarts the service

    private val connection = object : ServiceConnection {
        // Both callbacks are posted to the main looper by
        // ShizukuServiceConnection.
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                pendingBind?.completeExceptionally(
                    IllegalStateException("invalid binder for $name")
                )
                pendingBind = null
                return
            }
            val svc = IShizukuShellService.Stub.asInterface(binder)
            service = svc
            pendingBind?.complete(svc)
            pendingBind = null
            _state.update { s ->
                if (s is ShizukuState.Ready) s.copy(serviceBound = true) else s
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.update { s ->
                if (s is ShizukuState.Ready) s.copy(serviceBound = false) else s
            }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        pendingBind?.completeExceptionally(IllegalStateException("Shizuku binder died"))
        pendingBind = null
        pendingPermission?.complete(false)
        pendingPermission = null
        _state.value = ShizukuState.ServerNotRunning
    }

    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            pendingPermission?.complete(granted)
            pendingPermission = null
            refreshState()
        }

    /**
     * Call once, from JarvisApplication.onCreate (main process only).
     * Registers the three Shizuku listeners; the sticky binder
     * listener fires immediately if the binder is already there.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshState()
    }

    fun refreshState() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState = runCatching {
        val ctx = appContext ?: return ShizukuState.ServerNotRunning
        if (!isShizukuInstalled(ctx)) return ShizukuState.NotInstalled
        if (!Shizuku.pingBinder()) return ShizukuState.ServerNotRunning
        if (Shizuku.isPreV11()) return ShizukuState.Unsupported(-1)
        val version = Shizuku.getVersion()
        if (version < 11) return ShizukuState.Unsupported(version)
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return ShizukuState.NeedsPermission(
                permanentlyDenied = Shizuku.shouldShowRequestPermissionRationale()
            )
        }
        ShizukuState.Ready(
            uid = Shizuku.getUid(),
            version = version,
            patch = Shizuku.getServerPatchVersion(),
            serviceBound = service?.asBinder()?.pingBinder() == true
        )
    }.getOrElse {
        // IllegalStateException("binder haven't been received") etc.
        ShizukuState.ServerNotRunning
    }

    private fun isShizukuInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Shows the Shizuku permission dialog (shown by the Shizuku app).
     * Returns true when granted. Returns false immediately if the
     * binder is absent or the user chose "don't ask again" (in that
     * case the user must allow Jarvis inside the Shizuku app).
     */
    suspend fun requestPermission(): Boolean {
        when (val s = computeState()) {
            is ShizukuState.Ready -> return true
            is ShizukuState.NeedsPermission -> if (s.permanentlyDenied) return false
            else -> return false
        }
        pendingPermission?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            deferred.await()
        }.getOrElse {
            pendingPermission = null
            false
        }
    }

    private suspend fun bindService(): IShizukuShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        pendingBind?.let { return it.await() }
        val st = computeState()
        check(st is ShizukuState.Ready) { "Shizuku not ready: $st" }
        check(st.version >= 10) { "UserService requires Shizuku API 10, got ${st.version}" }
        val deferred = CompletableDeferred<IShizukuShellService>()
        pendingBind = deferred
        return try {
            // bindUserService must run on the main thread.
            withContext(Dispatchers.Main) { Shizuku.bindUserService(userServiceArgs, connection) }
            // The server drops the record after 30 s if the service
            // never starts — never wait forever (and never call the
            // ServiceConnection, so a timeout must fail loudly here).
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            pendingBind = null
            throw t
        }
    }

    /**
     * Runs `sh -c <cmd>` as the shell identity (UID 2000, or 0 under
     * root). Returns the stdout on exit 0; throws [ShellException]
     * (with exit code + stdout + stderr) otherwise.
     */
    suspend fun exec(cmd: String, timeoutMs: Int = EXEC_TIMEOUT_MS): Result<String> =
        runCatching {
            val svc = bindService()
            val bundle = execMutex.withLock {
                withContext(Dispatchers.IO) { svc.exec(cmd, timeoutMs) }
            }
            val code = bundle.getInt("code", -1)
            val out = bundle.getString("out").orEmpty()
            val err = bundle.getString("err").orEmpty()
            if (code != 0) throw ShellException(code, out, err)
            out
        }

    /**
     * 1.26.0 (M0 probe P3): asks the shell-uid service whether the
     * system says a PSTN call-audio tap is possible. Runs over the
     * same serialised exec path as [exec] (same bind, same mutex,
     * same IO dispatcher) — safe to call from any suspend scope.
     * The service answers from the Bundle: supported=false means
     * the hidden API could not be called on this build (reported
     * UNKNOWN by the caller — never a pass, never a fail).
     */
    suspend fun isCallAudioInterceptable(): Result<CallAudioInterceptableResult> =
        runCatching {
            val svc = bindService()
            val bundle = execMutex.withLock {
                withContext(Dispatchers.IO) { svc.isCallAudioInterceptable() }
            }
            CallAudioInterceptableResult(
                supported = bundle.getBoolean("supported", false),
                interceptable = bundle.getBoolean("interceptable", false),
                error = bundle.getString("error").orEmpty()
            )
        }

    /** Stops and kills the shell process (server sends transaction 16777115). */
    fun unbindService() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        _state.update { s ->
            if (s is ShizukuState.Ready) s.copy(serviceBound = false) else s
        }
    }

    // ---- The one-tap protection run ------------------------------------

    /**
     * Runs every shell-level protection, in order, and returns one
     * [ProtectionStep] per group with its real result (read back from
     * the system, not assumed). Binds the UserService once for the
     * whole run and unbinds it at the end, so no shell-UID process
     * lingers. Safe to call when not Ready — every step then fails
     * fast with a clear reason and nothing is half-applied silently.
     */
    suspend fun applyProtections(context: Context): List<ProtectionStep> {
        val steps = mutableListOf<ProtectionStep>()
        try {
            steps += enableAccessibilityStep()
            steps += dozeWhitelistStep()
            steps += standbyBucketStep()
            steps += appOpsStep()
            steps += runtimeGrantsStep(context)
        } finally {
            unbindService()
        }
        refreshState()
        return steps
    }

    /**
     * Boot re-apply: silent, best-effort, never blocks boot and never
     * shows anything. Only runs when Shizuku is actually usable right
     * now; if the server is not running, do nothing — the settings
     * persist across reboots anyway, and the next app open shows the
     * real state. The [pendingResult] is always finished (capped at
     * [BOOT_REAPPLY_TIMEOUT_MS]).
     */
    fun reapplyOnBootAsync(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(BOOT_REAPPLY_TIMEOUT_MS) {
                    if (computeState() is ShizukuState.Ready) {
                        applyProtections(context.applicationContext)
                    }
                }
            } catch (e: Exception) {
                // Silent by design: boot must never be blocked.
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    // ---- Individual steps (each verifies by reading back) --------------

    private suspend fun enableAccessibilityStep(): ProtectionStep {
        val title = "Accessibility ON"
        // Never overwrite the list: other accessibility services
        // (e.g. TalkBack) live in the same colon-separated value.
        // If the READ fails, stop here — writing with only Jarvis
        // would silently wipe every other service from the list.
        val currentResult = exec("settings get secure enabled_accessibility_services")
        if (currentResult.isFailure) {
            return ProtectionStep(
                title, false,
                "settings get failed: ${currentResult.exceptionOrNull()?.message}"
            )
        }
        val current = currentResult.getOrNull()?.trim().orEmpty()
        if (!current.contains(ACCESSIBILITY_COMPONENT, ignoreCase = true)) {
            val updated = if (current.isBlank() || current == "null") {
                ACCESSIBILITY_COMPONENT
            } else {
                "$current:$ACCESSIBILITY_COMPONENT"
            }
            val put = exec("settings put secure enabled_accessibility_services $updated")
            if (put.isFailure) {
                return ProtectionStep(
                    title, false,
                    "settings put failed: ${put.exceptionOrNull()?.message}"
                )
            }
        }
        val enable = exec("settings put secure accessibility_enabled 1")
        if (enable.isFailure) {
            return ProtectionStep(
                title, false,
                "accessibility_enabled failed: ${enable.exceptionOrNull()?.message}"
            )
        }
        val verify = exec("settings get secure enabled_accessibility_services")
            .getOrNull().orEmpty()
        val verifyEnabled = exec("settings get secure accessibility_enabled")
            .getOrNull()?.trim().orEmpty()
        return if (verify.contains(ACCESSIBILITY_COMPONENT, ignoreCase = true) &&
            verifyEnabled == "1"
        ) {
            ProtectionStep(title, true, "JarvisAccessibilityService enabled")
        } else {
            ProtectionStep(
                title, false,
                "verify failed: services=[$verify] enabled=[$verifyEnabled]"
            )
        }
    }

    private suspend fun dozeWhitelistStep(): ProtectionStep {
        val title = "Doze whitelist"
        val add = exec("cmd deviceidle whitelist +$JARVIS_PACKAGE")
        val addResult = if (add.isFailure) {
            // Older spelling of the same command.
            exec("dumpsys deviceidle whitelist +$JARVIS_PACKAGE")
        } else {
            add
        }
        if (addResult.isFailure) {
            return ProtectionStep(
                title, false,
                "whitelist add failed: ${addResult.exceptionOrNull()?.message}"
            )
        }
        val verify = exec("dumpsys deviceidle whitelist").getOrNull().orEmpty()
        return if (verify.contains(JARVIS_PACKAGE)) {
            ProtectionStep(title, true, "Doze + App Standby se exempt")
        } else {
            ProtectionStep(title, false, "verify failed: whitelist me package nahi mila")
        }
    }

    private suspend fun standbyBucketStep(): ProtectionStep {
        val title = "Standby bucket ACTIVE"
        val bucket = exec("am set-standby-bucket $JARVIS_PACKAGE ACTIVE")
        if (bucket.isFailure) {
            return ProtectionStep(
                title, false,
                "set-standby-bucket failed: ${bucket.exceptionOrNull()?.message}"
            )
        }
        val inactive = exec("am set-inactive $JARVIS_PACKAGE false")
        if (inactive.isFailure) {
            return ProtectionStep(
                title, false,
                "set-inactive failed: ${inactive.exceptionOrNull()?.message}"
            )
        }
        // `am get-standby-bucket` prints the bucket value: 10 = ACTIVE.
        // Some builds print the name instead — accept either.
        val verify = exec("am get-standby-bucket $JARVIS_PACKAGE")
            .getOrNull()?.trim().orEmpty()
        return if (verify == "10" || verify.equals("ACTIVE", ignoreCase = true)) {
            ProtectionStep(title, true, "bucket=$verify")
        } else {
            ProtectionStep(
                title, false,
                "verify: bucket=[$verify] (10/ACTIVE chahiye)"
            )
        }
    }

    private suspend fun appOpsStep(): ProtectionStep {
        val title = "Background appops"
        val ops = listOf(
            "RUN_ANY_IN_BACKGROUND",
            "ACCESS_RESTRICTED_SETTINGS",
            "SYSTEM_ALERT_WINDOW"
        )
        for (op in ops) {
            val r = exec("cmd appops set $JARVIS_PACKAGE $op allow")
            if (r.isFailure) {
                return ProtectionStep(
                    title, false,
                    "$op: ${r.exceptionOrNull()?.message}"
                )
            }
        }
        for (op in ops) {
            val out = exec("cmd appops get $JARVIS_PACKAGE $op")
                .getOrNull().orEmpty()
            if (!out.contains("allow", ignoreCase = true)) {
                return ProtectionStep(title, false, "$op verify: [$out]")
            }
        }
        return ProtectionStep(
            title, true,
            "RUN_ANY_IN_BACKGROUND + ACCESS_RESTRICTED_SETTINGS + SYSTEM_ALERT_WINDOW = allow"
        )
    }

    private suspend fun runtimeGrantsStep(context: Context): ProtectionStep {
        val title = "Runtime permissions"
        // Only permissions the app actually declares in its manifest
        // can be granted with `pm grant`. (CAMERA is not declared, so
        // it is deliberately not in this list.)
        val perms = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        val granted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (perm in perms) {
            val short = perm.substringAfterLast('.')
            if (ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                granted += short
                continue
            }
            val r = exec("pm grant $JARVIS_PACKAGE $perm")
            if (r.isSuccess && ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                granted += short
            } else {
                failed += short
            }
        }
        return if (failed.isEmpty()) {
            ProtectionStep(title, true, "granted: ${granted.joinToString()}")
        } else {
            ProtectionStep(
                title, false,
                "grant nahi hua: ${failed.joinToString()} " +
                    "(pehle se granted: ${granted.joinToString()})"
            )
        }
    }
}
