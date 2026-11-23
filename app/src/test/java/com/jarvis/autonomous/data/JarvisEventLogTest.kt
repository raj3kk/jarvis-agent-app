package com.jarvis.autonomous.data

import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * R2.2 (1.17.0-PRO) — unit tests for the on-device event log.
 * Runs on the JVM (JUnit4 + Robolectric). Covers: append + newest-first
 * read, the 500-event FIFO cap, and the privacy exclusion list (OTP /
 * banking / password content is redacted, the event itself is kept).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisEventLogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        File(context.filesDir, JarvisEventLog.FILE_NAME).delete()
        JarvisEventLog.init(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, JarvisEventLog.FILE_NAME).delete()
    }

    @Test
    fun appendAndRecentNewestFirst() {
        JarvisEventLog.log("turn-1", "TURN", "STARTED", "", "User command aaya")
        JarvisEventLog.log("turn-1", "BROWSER_JOB", "DONE", "ok", "example.com khola")
        val recent = JarvisEventLog.recent(10)
        assertEquals(2, recent.size)
        assertEquals("BROWSER_JOB", recent[0].action)
        assertEquals("DONE", recent[0].result)
        assertEquals("TURN", recent[1].action)
        assertEquals(2, JarvisEventLog.count())
    }

    @Test
    fun sensitiveContentIsRedactedNotDropped() {
        JarvisEventLog.log("turn-2", "TURN", "DONE", "", "mera otp 123456 hai")
        JarvisEventLog.log("turn-2", "GMAIL_SEND", "DONE", "", "bank account number bhej diya")
        // A token inside a URL is sensitive too — the whole line is
        // redacted (the query-strip rule never gets a chance to leak it).
        JarvisEventLog.log(
            "turn-2", "HTTP_CHECK", "DONE", "ok",
            "https://example.com/page?token=secret123 khola"
        )
        val recent = JarvisEventLog.recent(10)
        assertEquals(3, recent.size)
        assertTrue(recent.all { it.screenMeaning.contains("redacted") })
        // The event itself (kind + result) is still recorded.
        assertEquals("HTTP_CHECK", recent[0].action)
        assertEquals("GMAIL_SEND", recent[1].action)
    }

    @Test
    fun urlQueryStringsAreStripped() {
        // Non-sensitive query params: the query is stripped, the
        // path stays. (A token-bearing URL is a different rule — the
        // sensitive-word redaction below redacts the whole line.)
        JarvisEventLog.log(
            "turn-3", "HTTP_CHECK", "DONE", "ok",
            "https://example.com/page?ref=home&page=2 khola"
        )
        val recent = JarvisEventLog.recent(10)
        assertEquals(1, recent.size)
        assertTrue(!recent[0].screenMeaning.contains("ref=home"))
        assertTrue(!recent[0].screenMeaning.contains("page=2"))
        assertTrue(recent[0].screenMeaning.contains("https://example.com/page"))
    }

    @Test
    fun fifoCapKeepsTheNewest500() {
        for (i in 1..505) {
            JarvisEventLog.log("turn-cap", "LOOP", "DONE", "", "event $i")
        }
        assertEquals(JarvisEventLog.MAX_EVENTS, JarvisEventLog.count())
        val newest = JarvisEventLog.recent(1)
        assertEquals(1, newest.size)
        assertTrue(newest[0].screenMeaning.contains("event 505"))
        val oldestKept = JarvisEventLog.recent(500).last()
        assertTrue(oldestKept.screenMeaning.contains("event 6"))
    }
}
