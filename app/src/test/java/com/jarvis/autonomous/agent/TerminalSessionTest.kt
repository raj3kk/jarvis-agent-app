package com.jarvis.autonomous.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gapfill (2026-11-24) — unit tests for the in-app terminal backend's
 * PURE parts (parity §5.1A).
 *
 * TerminalSession's denylist check (blockedReason) is documented in
 * the class as a "JVM unit test seam — no process", but no test file
 * ever existed for it — the seam was built, the test was never
 * written. These tests close that gap. No process is started here:
 * blockedReason is a pure companion function, and the
 * construction / not-running assertions never call start(), so the
 * tests run on the plain JVM in CI (testDebugUnitTest) with no
 * Android runtime and no `sh`.
 *
 * What this does NOT prove (phone tests, still owed): E-T7 (ls/echo
 * run in the Terminal tab on the Vivo) and E-T8 (toybox applets
 * actually present on the Vivo). A denylist test cannot prove the
 * shell exists on the device.
 */
class TerminalSessionTest {

    // ---- blockedReason: destructive shapes are blocked ----

    @Test
    fun `rm -rf slash is blocked`() {
        assertNotNull(TerminalSession.blockedReason("rm -rf /"))
    }

    @Test
    fun `rm -rf absolute path is blocked`() {
        assertNotNull(TerminalSession.blockedReason("rm -rf /data"))
        assertNotNull(TerminalSession.blockedReason("rm -rf /sdcard/foo"))
    }

    @Test
    fun `rm --no-preserve-root is blocked`() {
        assertNotNull(TerminalSession.blockedReason("rm --no-preserve-root /data"))
    }

    @Test
    fun `denylist is case-insensitive`() {
        assertNotNull(TerminalSession.blockedReason("RM -RF /"))
        assertNotNull(TerminalSession.blockedReason("Su"))
        assertNotNull(TerminalSession.blockedReason("REBOOT"))
    }

    @Test
    fun `su and sudo are blocked, including chained`() {
        assertNotNull(TerminalSession.blockedReason("su"))
        assertNotNull(TerminalSession.blockedReason("sudo"))
        assertNotNull(TerminalSession.blockedReason("echo hi; su"))
        assertNotNull(TerminalSession.blockedReason("ls | sudo"))
    }

    @Test
    fun `reboot shutdown mkfs are blocked`() {
        assertNotNull(TerminalSession.blockedReason("reboot"))
        assertNotNull(TerminalSession.blockedReason("shutdown"))
        assertNotNull(TerminalSession.blockedReason("mkfs.ext4 /dev/sda"))
    }

    @Test
    fun `raw disk write is blocked`() {
        assertNotNull(TerminalSession.blockedReason("dd if=/dev/zero of=/dev/sda"))
    }

    @Test
    fun `chmod on slash is blocked`() {
        assertNotNull(TerminalSession.blockedReason("chmod 777 /"))
        assertNotNull(TerminalSession.blockedReason("chmod 777 /data"))
        assertNull(TerminalSession.blockedReason("chmod 755 foo.txt"))
    }

    @Test
    fun `cd to absolute path or parent is blocked`() {
        assertNotNull(TerminalSession.blockedReason("cd /sdcard"))
        assertNotNull(TerminalSession.blockedReason("cd /"))
        assertNotNull(TerminalSession.blockedReason("cd .."))
        assertNotNull(TerminalSession.blockedReason("cd ../.."))
    }

    // ---- blockedReason: ordinary app-storage commands pass ----

    @Test
    fun `ordinary toybox commands are allowed`() {
        assertNull(TerminalSession.blockedReason("ls"))
        assertNull(TerminalSession.blockedReason("ls -la"))
        assertNull(TerminalSession.blockedReason("cat foo.txt"))
        assertNull(TerminalSession.blockedReason("echo hello"))
        assertNull(TerminalSession.blockedReason("grep -r foo ."))
        assertNull(TerminalSession.blockedReason("toybox"))
    }

    @Test
    fun `relative cd inside app storage is allowed`() {
        assertNull(TerminalSession.blockedReason("cd subdir"))
        assertNull(TerminalSession.blockedReason("cd ."))
    }

    @Test
    fun `relative rm inside app storage is allowed by the denylist`() {
        // Boundary, documented on purpose: the denylist blocks only
        // absolute-root shapes. A relative rm stays inside the app
        // filesDir the shell starts in — the app UID is the sandbox.
        assertNull(TerminalSession.blockedReason("rm -rf subdir"))
        assertNull(TerminalSession.blockedReason("rm foo.txt"))
    }

    @Test
    fun `blank command is not blocked`() {
        assertNull(TerminalSession.blockedReason(""))
        assertNull(TerminalSession.blockedReason("   "))
    }

    // ---- session lifecycle without starting a process ----

    @Test
    fun `fresh session is not running and has no history`() {
        val session = TerminalSession(File("/tmp"))
        assertFalse(session.isRunning)
        assertTrue(session.history.isEmpty())
        assertEquals("", session.drainOutput())
    }

    @Test
    fun `send before start reports shell not running`() {
        val session = TerminalSession(File("/tmp"))
        val error = session.send("ls")
        assertNotNull(error)
        assertTrue(error!!.contains("Shell chal nahi raha"))
    }

    @Test
    fun `stop before start is a safe no-op`() {
        val session = TerminalSession(File("/tmp"))
        session.stop() // Must not throw; nothing was started.
        assertFalse(session.isRunning)
    }

    @Test
    fun `blocked command is refused even before start`() {
        // The denylist runs before the running-check, so a blocked
        // shape is refused with the allow-list message, not the
        // not-running message.
        val session = TerminalSession(File("/tmp"))
        val error = session.send("rm -rf /")
        assertNotNull(error)
        assertTrue(error!!.contains("allowed nahi hai"))
    }
}
