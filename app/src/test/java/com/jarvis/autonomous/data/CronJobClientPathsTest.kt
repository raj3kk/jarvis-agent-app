package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R4.7 — cron lifecycle route builders. Pure Kotlin (the paths the
 * suspend functions call). The server routes were verified live in
 * the R4 turn (create → list → disable → enable → delete); these
 * tests pin the app side to the same paths.
 */
class CronJobClientPathsTest {

    @Test
    fun `setEnabledPath builds enable and disable routes`() {
        assertEquals(
            "/api/billing/cron-jobs/job-1/enable",
            CronJobClient.setEnabledPath("job-1", true)
        )
        assertEquals(
            "/api/billing/cron-jobs/job-1/disable",
            CronJobClient.setEnabledPath("job-1", false)
        )
    }

    @Test
    fun `deletePath builds the delete route`() {
        assertEquals(
            "/api/billing/cron-jobs/job-1",
            CronJobClient.deletePath("job-1")
        )
    }
}
