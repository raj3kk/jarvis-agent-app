package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.26.0 (power features) — unit tests for FilePower: the pure
 * shell-command builders, the ls/stat/pm parsers, and the safety
 * gates (writable paths, blocked paths, safe delete targets). No
 * phone, no Shizuku — every function here is pure.
 */
class FilePowerTest {

    @Test
    fun listCommandUsesLongIsoFormat() {
        assertEquals(
            "ls -la --time-style=long-iso '/sdcard'",
            FilePower.listCommand("/sdcard")
        )
        assertEquals(
            "ls -la --time-style=long-iso '/sdcard/Android/data'",
            FilePower.listCommand("/sdcard/Android/data")
        )
    }

    @Test
    fun parseLsOutputParsesFilesDirsAndSymlinks() {
        val out = """
            total 24
            drwxrwx--x  5 u0_a123 sdcard_rw 4096 2026-11-25 10:00 Documents
            -rw-rw----  1 u0_a123 sdcard_rw 1234 2026-11-25 10:01 notes.txt
            lrwxrwxrwx  1 root    root         21 2026-11-25 10:02 link -> /sdcard/real
            -rw-rw----  1 u0_a123 sdcard_rw  512 2026-11-25 10:03 .hidden
        """.trimIndent()
        val entries = FilePower.parseLsOutput(out)
        assertEquals(4, entries.size)

        val dir = entries[0]
        assertTrue(dir.isDir)
        assertFalse(dir.isLink)
        assertEquals("Documents", dir.name)
        assertEquals(4096L, dir.sizeBytes)
        assertEquals("2026-11-25 10:00", dir.date)

        val file = entries[1]
        assertFalse(file.isDir)
        assertEquals("notes.txt", file.name)
        assertEquals(1234L, file.sizeBytes)
        assertNull(file.linkTarget)

        val link = entries[2]
        assertTrue(link.isLink)
        assertEquals("link", link.name)
        assertEquals("/sdcard/real", link.linkTarget)

        // Hidden files are listed too (ls -la shows them).
        assertEquals(".hidden", entries[3].name)
    }

    @Test
    fun parseLsOutputSkipsDotEntriesAndTotalLine() {
        val out = """
            total 8
            drwxrwx--x  2 u0_a123 sdcard_rw 4096 2026-11-25 10:00 .
            drwxrwx--x  5 u0_a123 sdcard_rw 4096 2026-11-25 10:00 ..
            -rw-rw----  1 u0_a123 sdcard_rw  100 2026-11-25 10:00 a.txt
        """.trimIndent()
        val entries = FilePower.parseLsOutput(out)
        assertEquals(1, entries.size)
        assertEquals("a.txt", entries[0].name)
    }

    @Test
    fun parseLsOutputHandlesFilenamesWithSpaces() {
        val out = "-rw-rw----  1 u0_a123 sdcard_rw  100 2026-11-25 10:00 my notes.txt"
        val entries = FilePower.parseLsOutput(out)
        assertEquals(1, entries.size)
        assertEquals("my notes.txt", entries[0].name)
    }

    @Test
    fun parseLsOutputHandlesEmptyAndErrorOutput() {
        assertTrue(FilePower.parseLsOutput("").isEmpty())
        assertTrue(FilePower.parseLsOutput("ls: /sdcard/nope: No such file or directory").isEmpty())
    }

    @Test
    fun parseStatParsesSizeModeMtime() {
        val stat = FilePower.parseStat("1234|644|2026-11-25 10:00:00 +0530")
        assertEquals(1234L, stat!!.sizeBytes)
        assertEquals("644", stat.mode)
        assertTrue(stat.mtime.startsWith("2026-11-25"))
        assertNull(FilePower.parseStat(""))
        assertNull(FilePower.parseStat("not-a-stat"))
    }

    @Test
    fun parsePmPathAndList() {
        assertEquals(
            "/data/app/com.whatsapp/base.apk",
            FilePower.parsePmPath("package:/data/app/com.whatsapp/base.apk")
        )
        assertNull(FilePower.parsePmPath(""))
        assertNull(FilePower.parsePmPath("no packages here"))
        val pkgs = FilePower.parsePmList(
            "package:com.whatsapp\npackage:com.google.android.youtube\npackage:android\n"
        )
        assertEquals(listOf("android", "com.google.android.youtube", "com.whatsapp"), pkgs)
    }

    @Test
    fun parsePackageVersion() {
        assertEquals(
            "2.25.1",
            FilePower.parsePackageVersion("    versionName=2.25.1\n    versionCode=123")
        )
        assertNull(FilePower.parsePackageVersion("no version here"))
    }

    @Test
    fun writablePathsAreSdcardAndLocalTmpOnly() {
        assertTrue(FilePower.isWritablePath("/sdcard/notes.txt"))
        assertTrue(FilePower.isWritablePath("/sdcard/Android/data/com.example"))
        assertTrue(FilePower.isWritablePath("/data/local/tmp/jarvis-edit-1"))
        assertFalse(FilePower.isWritablePath("/data/data/com.example"))
        assertFalse(FilePower.isWritablePath("/data/app/com.example/base.apk"))
        assertFalse(FilePower.isWritablePath("/system/build.prop"))
    }

    @Test
    fun blockedPathsAreDataDataAndSystem() {
        assertTrue(FilePower.isBlockedPath("/data/data"))
        assertTrue(FilePower.isBlockedPath("/data/data/com.example"))
        assertTrue(FilePower.isBlockedPath("/system"))
        assertTrue(FilePower.isBlockedPath("/system/build.prop"))
        assertFalse(FilePower.isBlockedPath("/data/local/tmp"))
        assertFalse(FilePower.isBlockedPath("/sdcard/Android/data"))
    }

    @Test
    fun safeDeleteTargetsNeverIncludeRoots() {
        assertTrue(FilePower.isSafeDeleteTarget("/sdcard/notes.txt"))
        assertTrue(FilePower.isSafeDeleteTarget("/sdcard/Android/data/com.example"))
        assertFalse(FilePower.isSafeDeleteTarget("/"))
        assertFalse(FilePower.isSafeDeleteTarget("/sdcard"))
        assertFalse(FilePower.isSafeDeleteTarget("/sdcard/Android"))
        assertFalse(FilePower.isSafeDeleteTarget("/sdcard/Android/data"))
        assertFalse(FilePower.isSafeDeleteTarget("/sdcard/Android/obb"))
        assertFalse(FilePower.isSafeDeleteTarget("/data/local/tmp"))
        assertFalse(FilePower.isSafeDeleteTarget("/data/data/com.example"))
    }

    @Test
    fun readCommandIsPlainCat() {
        assertEquals("cat '/sdcard/notes.txt'", FilePower.readTextCommand("/sdcard/notes.txt"))
    }

    @Test
    fun writeCommandGoesThroughBase64StagingAndRestoresMode() {
        val cmd = FilePower.writeTextCommand(
            "/data/local/tmp/jarvis-write-1", "aGVsbG8=", "/sdcard/notes.txt", "644"
        )
        // Base64 in, staged file, then cp + chmod (mode from stat) + rm staging.
        assertTrue(cmd.contains("echo 'aGVsbG8=' | base64 -d"))
        assertTrue(cmd.contains("cp '/data/local/tmp/jarvis-write-1' '/sdcard/notes.txt'"))
        assertTrue(cmd.contains("chmod 644 '/sdcard/notes.txt'"))
        assertTrue(cmd.contains("rm '/data/local/tmp/jarvis-write-1'"))
        // The content must be base64, never the raw text.
        assertFalse(cmd.contains("hello"))
    }

    @Test
    fun deleteCopyMoveQuotePaths() {
        assertEquals("rm -rf '/sdcard/old.txt'", FilePower.deleteCommand("/sdcard/old.txt"))
        assertEquals(
            "cp -r '/sdcard/a.txt' '/sdcard/b.txt'",
            FilePower.copyCommand("/sdcard/a.txt", "/sdcard/b.txt")
        )
        assertEquals(
            "mv '/sdcard/a.txt' '/sdcard/b.txt'",
            FilePower.moveCommand("/sdcard/a.txt", "/sdcard/b.txt")
        )
        assertEquals("mkdir -p '/sdcard/Jarvis/apks'", FilePower.mkdirCommand("/sdcard/Jarvis/apks"))
    }

    @Test
    fun quotePathEscapesSingleQuotesForTheShell() {
        assertEquals("'/sdcard/it'\\''s.txt'", FilePower.quotePath("/sdcard/it's.txt"))
    }

    @Test
    fun apkCommands() {
        assertEquals("pm path com.whatsapp", FilePower.apkPathCommand("com.whatsapp"))
        assertEquals(
            "cp '/data/app/com.whatsapp/base.apk' '/sdcard/Jarvis/apks/com.whatsapp.apk' && " +
                "ls -l '/sdcard/Jarvis/apks/com.whatsapp.apk'",
            FilePower.apkBackupCommand(
                "/data/app/com.whatsapp/base.apk",
                "/sdcard/Jarvis/apks/com.whatsapp.apk"
            )
        )
        assertEquals("pm list packages -3", FilePower.listPackagesCommand(true))
        assertEquals("pm list packages", FilePower.listPackagesCommand(false))
    }

    @Test
    fun searchCommandsSanitizeTheQuery() {
        // Shell metacharacters are stripped — no injection through search.
        val find = FilePower.findCommand("/sdcard", "notes; rm -rf /")
        assertFalse(find.contains(";"))
        assertFalse(find.contains("rm"))
        assertTrue(find.contains("notes"))
        val grep = FilePower.grepCommand("/sdcard", "hello\$world")
        assertFalse(grep.contains("\$"))
    }

    @Test
    fun parentPathAndHumanSize() {
        assertEquals("/sdcard", FilePower.parentPath("/sdcard/Android/data"))
        assertEquals("/sdcard", FilePower.parentPath("/sdcard/file.txt"))
        assertEquals("/", FilePower.parentPath("/sdcard"))
        assertEquals("/", FilePower.parentPath("/"))
        assertEquals("512 B", FilePower.humanSize(512))
        assertEquals("2.0 KB", FilePower.humanSize(2048))
        assertEquals("1.5 MB", FilePower.humanSize(1572864))
    }

    @Test
    fun textFileDetection() {
        assertTrue(FilePower.isTextFile("notes.txt"))
        assertTrue(FilePower.isTextFile("data.json"))
        assertTrue(FilePower.isTextFile("README.MD"))
        assertTrue(FilePower.isTextFile("MainActivity.kt"))
        assertFalse(FilePower.isTextFile("photo.jpg"))
        assertFalse(FilePower.isTextFile("app.apk"))
        assertFalse(FilePower.isTextFile("noext"))
    }

    @Test
    fun maxEditBytesIsOneMegabyte() {
        assertEquals(1_048_576L, FilePower.MAX_EDIT_BYTES)
        assertTrue(FilePower.BROWSE_ROOTS.any { it.first == "/sdcard/Android/data" })
        assertTrue(FilePower.BROWSE_ROOTS.any { it.first == "/sdcard/Android/obb" })
    }
}
