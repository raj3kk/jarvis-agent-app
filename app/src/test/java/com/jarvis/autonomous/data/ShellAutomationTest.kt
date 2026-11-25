package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.26.0 (power features) — unit tests for ShellAutomation: the
 * pure shell-command builders, the input-text escaping, and the
 * UI-dump parser. No phone, no Shizuku — every function here is pure.
 */
class ShellAutomationTest {

    @Test
    fun tapSwipeAndKeyeventCommands() {
        assertEquals("input tap 100 200", ShellAutomation.tapCommand(100, 200))
        assertEquals("input swipe 0 0 100 200 300", ShellAutomation.swipeCommand(0, 0, 100, 200, 300))
        // Duration is clamped to 50–5000 ms.
        assertEquals("input swipe 0 0 100 200 50", ShellAutomation.swipeCommand(0, 0, 100, 200, 10))
        assertEquals("input swipe 0 0 100 200 5000", ShellAutomation.swipeCommand(0, 0, 100, 200, 99999))
        assertEquals("input keyevent 4", ShellAutomation.keyeventCommand(4))
    }

    @Test
    fun inputTextEscapesSpacesPercentsAndQuotes() {
        assertEquals("input text 'hi'", ShellAutomation.typeCommand("hi"))
        // Spaces become %s, % becomes %%, and the whole argument is
        // single-quoted for the shell.
        assertEquals("input text 'hello%sworld'", ShellAutomation.typeCommand("hello world"))
        assertEquals("input text '100%%%ssure'", ShellAutomation.typeCommand("100% sure"))
        assertEquals("input text 'it'\\''s'", ShellAutomation.typeCommand("it's"))
        // Newlines become spaces (input text has no newline support).
        assertEquals("input text 'a%sb'", ShellAutomation.typeCommand("a\nb"))
    }

    @Test
    fun uiDumpAndScreenshotCommands() {
        assertEquals(
            "uiautomator dump /data/local/tmp/jarvis-ui.xml",
            ShellAutomation.dumpUiCommand()
        )
        assertEquals("cat /data/local/tmp/jarvis-ui.xml", ShellAutomation.readUiDumpCommand())
        assertEquals(
            "mkdir -p '/sdcard' && screencap -p '/sdcard/a.png'",
            ShellAutomation.screenshotCommand("/sdcard/a.png")
        )
        assertEquals(
            "mkdir -p '/sdcard' && screenrecord --time-limit 30 '/sdcard/a.mp4'",
            ShellAutomation.screenRecordCommand("/sdcard/a.mp4", 30)
        )
        // Record time is clamped to 1–180 s.
        assertTrue(ShellAutomation.screenRecordCommand("/sdcard/a.mp4", 999).contains("--time-limit 180"))
    }

    @Test
    fun parseUiDumpExtractsNodesWithBounds() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node index="0" text="Voice call" content-desc="Voice call" class="android.widget.ImageButton" clickable="true" bounds="[900,100][1000,200]" />
              <node index="1" text="Hello &amp; bye" content-desc="" class="android.widget.TextView" clickable="false" bounds="[10,20][300,60]" />
              <node index="2" text="" content-desc="Back" class="android.widget.ImageButton" clickable="true" bounds="[0,0][100,100]" />
            </hierarchy>
        """.trimIndent()
        val nodes = ShellAutomation.parseUiDump(xml)
        assertEquals(3, nodes.size)

        val call = nodes[0]
        assertEquals("Voice call", call.text)
        assertTrue(call.clickable)
        assertEquals(950, call.centerX)
        assertEquals(150, call.centerY)

        val hello = nodes[1]
        assertEquals("Hello & bye", hello.text)
        assertFalse(hello.clickable)
        assertEquals(155, hello.centerX)
        assertEquals(40, hello.centerY)
    }

    @Test
    fun parseUiDumpAlsoMatchesParentOpeningTags() {
        // A parent container node is NOT self-closing
        // (`<node ...>` with children) — it is still a valid target.
        val xml = """
            <hierarchy>
              <node index="0" text="Chat" content-desc="" class="android.widget.LinearLayout" clickable="true" bounds="[0,100][500,300]">
                <node index="0" text="Voice call" content-desc="" class="android.widget.ImageButton" clickable="true" bounds="[400,150][480,250]" />
              </node>
            </hierarchy>
        """.trimIndent()
        val nodes = ShellAutomation.parseUiDump(xml)
        assertEquals(2, nodes.size)
        assertEquals("Chat", nodes[0].text)
        assertTrue(nodes[0].clickable)
        assertEquals("Voice call", nodes[1].text)
    }

    @Test
    fun findNodeByTextPrefersClickableMatches() {
        val xml = """
            <hierarchy>
              <node text="Call" content-desc="" class="android.widget.TextView" clickable="false" bounds="[0,0][100,50]" />
              <node text="Call" content-desc="" class="android.widget.ImageButton" clickable="true" bounds="[200,0][300,50]" />
            </hierarchy>
        """.trimIndent()
        val nodes = ShellAutomation.parseUiDump(xml)
        val found = ShellAutomation.findNodeByText(nodes, "call")
        assertEquals(250, found!!.centerX)
        assertTrue(found.clickable)
        assertNull(ShellAutomation.findNodeByText(nodes, "nope"))
    }

    @Test
    fun packageValidation() {
        assertTrue(ShellAutomation.isValidPackage("com.whatsapp"))
        assertTrue(ShellAutomation.isValidPackage("com.google.android.youtube"))
        assertFalse(ShellAutomation.isValidPackage("whatsapp"))
        assertFalse(ShellAutomation.isValidPackage("com."))
        assertFalse(ShellAutomation.isValidPackage(""))
        assertFalse(ShellAutomation.isValidPackage("com.whatsapp!"))
    }

    @Test
    fun appManagementCommands() {
        assertEquals(
            "pm install -r -g '/sdcard/app.apk'",
            ShellAutomation.installAppCommand("/sdcard/app.apk")
        )
        assertEquals("pm uninstall --user 0 com.whatsapp", ShellAutomation.uninstallAppCommand("com.whatsapp"))
        assertEquals("pm enable com.whatsapp", ShellAutomation.enableAppCommand("com.whatsapp"))
        assertEquals("pm disable com.whatsapp", ShellAutomation.disableAppCommand("com.whatsapp"))
        assertEquals("pm clear com.whatsapp", ShellAutomation.clearAppCommand("com.whatsapp"))
        assertEquals("am force-stop com.whatsapp", ShellAutomation.forceStopAppCommand("com.whatsapp"))
        assertEquals(
            "monkey -p com.whatsapp -c android.intent.category.LAUNCHER 1",
            ShellAutomation.launchAppCommand("com.whatsapp")
        )
        assertEquals("pm grant com.whatsapp android.permission.CAMERA", ShellAutomation.grantPermissionCommand("com.whatsapp", "android.permission.CAMERA"))
        assertEquals("pm revoke com.whatsapp android.permission.CAMERA", ShellAutomation.revokePermissionCommand("com.whatsapp", "android.permission.CAMERA"))
    }

    @Test
    fun settingsCommands() {
        assertEquals("settings put system screen_brightness 127", ShellAutomation.brightnessCommand(50))
        assertEquals("settings put system screen_brightness 255", ShellAutomation.brightnessCommand(100))
        assertEquals("settings put system screen_brightness 0", ShellAutomation.brightnessCommand(0))
        assertEquals("cmd media_session volume --stream 3 --set 10", ShellAutomation.volumeCommand(3, 10))
        assertEquals("cmd uimode night yes", ShellAutomation.nightModeCommand(true))
        assertEquals("cmd uimode night no", ShellAutomation.nightModeCommand(false))
        assertEquals("cmd power set-mode 1", ShellAutomation.powerSaverCommand(true))
        assertEquals("cmd power set-mode 0", ShellAutomation.powerSaverCommand(false))
        assertEquals("cmd notification set_dnd on", ShellAutomation.dndCommand(true))
        assertEquals("cmd notification set_dnd none", ShellAutomation.dndCommand(false))
    }

    @Test
    fun connectivityCommands() {
        assertEquals("svc wifi enable", ShellAutomation.wifiCommand(true))
        assertEquals("svc wifi disable", ShellAutomation.wifiCommand(false))
        assertEquals("svc data enable", ShellAutomation.mobileDataCommand(true))
        assertEquals("svc bluetooth disable", ShellAutomation.bluetoothCommand(false))
        // Airplane mode ONLY via the cmd route (settings put alone
        // does not flip the radios).
        assertEquals("cmd connectivity airplane-mode enable", ShellAutomation.airplaneModeCommand(true))
        assertEquals("cmd connectivity airplane-mode disable", ShellAutomation.airplaneModeCommand(false))
    }
}
