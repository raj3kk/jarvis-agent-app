package com.jarvis.autonomous.data

/**
 * 1.26.0 (power features) — ShellAutomation: shell primitives for
 * driving the phone like a human finger, plus app management and
 * settings/connectivity toggles.
 *
 * Pure shell-command builders + UI-dump parsers — no Android API, no
 * Shizuku call here, so every function is unit-testable without a
 * phone. The plan executor ([AgentPlanExecutor] SHELL_* steps) and
 * the Calls tab (WhatsApp control) call [ShizukuManager.exec] with
 * the commands built here; the AccessibilityService gestures stay as
 * the fallback when Shizuku is not ready (shell first, Accessibility
 * second — shell is faster and works even when Accessibility is off).
 *
 * Command sources: spec §1d (prism / android-jev / autarch shell
 * research, verified against AOSP). Airplane mode ONLY via the `cmd`
 * route — `settings put global airplane_mode_on` alone does not work
 * (the radios react to the broadcast, which shell cannot send).
 * `wm size` / `wm density` / `device_config` / phantom-proc are
 * deliberately NOT built here (§6 rule 8).
 */
object ShellAutomation {

    // ---- Input injection (§1d A1) ----

    fun tapCommand(x: Int, y: Int): String = "input tap $x $y"

    fun swipeCommand(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        "input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(50, 5000)}"

    fun keyeventCommand(keyCode: Int): String = "input keyevent $keyCode"

    /**
     * Escapes text for `input text`: the command itself wants spaces
     * as `%s` and `%` as `%%`; the whole argument is single-quoted
     * for the shell (so `;`, `&`, `$` and friends are literal).
     * Newlines become spaces — `input text` has no newline support.
     */
    fun escapeInputText(text: String): String {
        val singleLine = text.replace('\n', ' ').replace('\r', ' ')
        val percentEscaped = singleLine.replace("%", "%%")
        val spaceEscaped = percentEscaped.replace(" ", "%s")
        return "'" + spaceEscaped.replace("'", "'\\''") + "'"
    }

    fun typeCommand(text: String): String = "input text ${escapeInputText(text)}"

    // ---- Screen reading / capture (§1d A2–A5) ----

    /** UI-dump file Jarvis stages in /data/local/tmp (shell-writable). */
    const val UI_DUMP_PATH = "/data/local/tmp/jarvis-ui.xml"

    fun dumpUiCommand(): String = "uiautomator dump $UI_DUMP_PATH"

    fun readUiDumpCommand(): String = "cat $UI_DUMP_PATH"

    fun screenshotCommand(path: String): String =
        "mkdir -p ${FilePower.quotePath(path.substringBeforeLast('/'))} && screencap -p ${FilePower.quotePath(path)}"

    fun screenRecordCommand(path: String, seconds: Int): String =
        "mkdir -p ${FilePower.quotePath(path.substringBeforeLast('/'))} && " +
            "screenrecord --time-limit ${seconds.coerceIn(1, 180)} ${FilePower.quotePath(path)}"

    data class UiNode(
        val text: String,
        val contentDesc: String,
        val className: String,
        val clickable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    // Matches both self-closing leaf nodes (`<node ... />`) and
    // parent opening tags (`<node ...>` with children) — a
    // clickable parent container is a valid tap target too.
    private val NODE_REGEX = Regex("<node\\b([^>]*?)/?>")
    private val ATTR_REGEX = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val BOUNDS_REGEX = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    private fun decodeXmlEntities(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")

    /**
     * Parses a `uiautomator dump` XML file into its nodes. Attribute
     * values may contain XML entities; bounds are `[x1,y1][x2,y2]`.
     * A node without bounds is kept with 0 bounds (never tapped).
     */
    fun parseUiDump(xml: String): List<UiNode> {
        val nodes = ArrayList<UiNode>()
        for (match in NODE_REGEX.findAll(xml)) {
            val attrs = ATTR_REGEX.findAll(match.groupValues[1]).associate { m ->
                m.groupValues[1] to decodeXmlEntities(m.groupValues[2])
            }
            val bounds = attrs["bounds"]?.let { BOUNDS_REGEX.find(it) }
            nodes += UiNode(
                text = attrs["text"].orEmpty(),
                contentDesc = attrs["content-desc"].orEmpty(),
                className = attrs["class"].orEmpty(),
                clickable = attrs["clickable"] == "true",
                left = bounds?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
                top = bounds?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0,
                right = bounds?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0,
                bottom = bounds?.groupValues?.getOrNull(4)?.toIntOrNull() ?: 0
            )
        }
        return nodes
    }

    /**
     * Finds the first node whose text or content-desc contains
     * [query] (case-insensitive). Clickable nodes win over
     * non-clickable ones with the same text.
     */
    fun findNodeByText(nodes: List<UiNode>, query: String): UiNode? {
        val q = query.lowercase()
        val matches = nodes.filter {
            it.text.lowercase().contains(q) || it.contentDesc.lowercase().contains(q)
        }
        return matches.firstOrNull { it.clickable } ?: matches.firstOrNull()
    }

    // ---- App management (§1d A6–A13) ----

    /** Package names are [a-zA-Z0-9_.] with at least one dot. */
    fun isValidPackage(pkg: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$").matches(pkg.trim())

    fun installAppCommand(apkPath: String): String =
        "pm install -r -g ${FilePower.quotePath(apkPath)}"

    fun uninstallAppCommand(pkg: String): String = "pm uninstall --user 0 $pkg"

    fun enableAppCommand(pkg: String): String = "pm enable $pkg"

    fun disableAppCommand(pkg: String): String = "pm disable $pkg"

    fun clearAppCommand(pkg: String): String = "pm clear $pkg"

    fun forceStopAppCommand(pkg: String): String = "am force-stop $pkg"

    fun launchAppCommand(pkg: String): String =
        "monkey -p $pkg -c android.intent.category.LAUNCHER 1"

    fun appInfoCommand(pkg: String): String = "pm dump $pkg"

    fun grantPermissionCommand(pkg: String, permission: String): String =
        "pm grant $pkg $permission"

    fun revokePermissionCommand(pkg: String, permission: String): String =
        "pm revoke $pkg $permission"

    fun appOpCommand(pkg: String, op: String, allow: Boolean): String =
        "cmd appops set $pkg $op ${if (allow) "allow" else "deny"}"

    // ---- Settings toggles (§1d A14, A20–A23) ----

    fun brightnessCommand(percent: Int): String {
        val value = (percent.coerceIn(0, 100) * 255 / 100)
        return "settings put system screen_brightness $value"
    }

    fun volumeCommand(stream: Int, level: Int): String =
        "cmd media_session volume --stream ${stream.coerceIn(0, 10)} --set ${level.coerceIn(0, 15)}"

    fun nightModeCommand(enable: Boolean): String =
        "cmd uimode night ${if (enable) "yes" else "no"}"

    fun powerSaverCommand(enable: Boolean): String =
        "cmd power set-mode ${if (enable) 1 else 0}"

    fun dndCommand(enable: Boolean): String =
        "cmd notification set_dnd ${if (enable) "on" else "none"}"

    // ---- Connectivity toggles (§1d A15–A19) ----

    fun wifiCommand(enable: Boolean): String =
        "svc wifi ${if (enable) "enable" else "disable"}"

    fun mobileDataCommand(enable: Boolean): String =
        "svc data ${if (enable) "enable" else "disable"}"

    fun bluetoothCommand(enable: Boolean): String =
        "svc bluetooth ${if (enable) "enable" else "disable"}"

    /**
     * Airplane mode ONLY via the `cmd` route — `settings put global
     * airplane_mode_on` alone flips the key but the radios never
     * react (shell cannot send the protected broadcast).
     */
    fun airplaneModeCommand(enable: Boolean): String =
        "cmd connectivity airplane-mode ${if (enable) "enable" else "disable"}"
}
