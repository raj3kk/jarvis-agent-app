package com.jarvis.autonomous.data

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-I-30 (1.14.0): CronJobClient — the cron manager's only pure
 * seam (JSON parsing + IST rendering). JVM unit test (JUnit 4,
 * runs in CI `./gradlew testDebugUnitTest`); no Android, no
 * Robolectric needed. Network + signing are exercised on the phone
 * (C-T1–C-T9), never faked here.
 */
class CronJobClientTest {

    // ---- parseJobsJson ----

    @Test
    fun parseJobs_parsesEnabledAndDisabled_nullRunsBecomeNull() {
        val json = JSONObject(
            "{\"ok\":true,\"count\":2,\"jobs\":[" +
                "{\"id\":\"job-1\",\"tenant_id\":\"t\",\"device_id\":\"d\",\"cron_expr\":\"0 8 * * *\"," +
                "\"command\":\"good morning bolo\",\"enabled\":1,\"last_run_at\":1795300000000," +
                "\"next_run_at\":1795363200000,\"created_at\":1795290000000}," +
                "{\"id\":\"job-2\",\"tenant_id\":\"t\",\"device_id\":\"d\",\"cron_expr\":\"*/15 * * * *\"," +
                "\"command\":\"paani piyo\",\"enabled\":0,\"last_run_at\":null," +
                "\"next_run_at\":null,\"created_at\":1795290000000}]}"
        )
        val jobs = CronJobClient.parseJobsJson(json)
        assertEquals(2, jobs.size)
        assertEquals("job-1", jobs[0].id)
        assertEquals("0 8 * * *", jobs[0].cronExpr)
        assertEquals("good morning bolo", jobs[0].command)
        assertTrue(jobs[0].enabled)
        assertEquals(1795363200000L, jobs[0].nextRunAt)
        assertEquals("job-2", jobs[1].id)
        assertTrue(!jobs[1].enabled)
        assertNull(jobs[1].lastRunAt)
        assertNull(jobs[1].nextRunAt)
    }

    @Test
    fun parseJobs_emptyArray_returnsEmpty() {
        val jobs = CronJobClient.parseJobsJson(JSONObject("{\"ok\":true,\"jobs\":[],\"count\":0}"))
        assertTrue(jobs.isEmpty())
    }

    // ---- parseParseJson ----

    @Test
    fun parseParse_ok_returnsExprAndNext() {
        val r = CronJobClient.parseParseJson(
            JSONObject("{\"ok\":true,\"cron_expr\":\"0 8 * * *\",\"source\":\"deterministic\",\"next_run_at\":1795363200000}")
        )
        assertTrue(r is CronJobClient.ParseResult.Ok)
        val ok = r as CronJobClient.ParseResult.Ok
        assertEquals("0 8 * * *", ok.cronExpr)
        assertEquals("deterministic", ok.source)
        assertEquals(1795363200000L, ok.nextRunAt)
    }

    @Test
    fun parseParse_unparseable_returnsDetail() {
        val r = CronJobClient.parseParseJson(
            JSONObject("{\"ok\":false,\"error\":\"unparseable\",\"detail\":\"Time samajh nahi aaya — dobara saaf shabdon me likho (jaise: har subah 8 baje)\"}")
        )
        assertTrue(r is CronJobClient.ParseResult.Unparseable)
        val u = r as CronJobClient.ParseResult.Unparseable
        assertTrue(u.detail.contains("har subah 8 baje"))
    }

    // ---- formatNextRun ----

    @Test
    fun formatNextRun_istRendersKnownTime() {
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(kolkata)
        cal.set(2026, Calendar.NOVEMBER, 23, 8, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        assertEquals("23 Nov, 8:00 AM", CronJobClient.formatNextRun(cal.timeInMillis, kolkata))
    }

    @Test
    fun formatNextRun_null_returnsDash() {
        assertEquals("—", CronJobClient.formatNextRun(null))
        assertEquals("—", CronJobClient.formatNextRun(0))
    }
}
