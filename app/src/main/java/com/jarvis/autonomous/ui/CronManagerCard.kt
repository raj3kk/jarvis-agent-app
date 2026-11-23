package com.jarvis.autonomous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.CronJobClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Scheduled Jobs — Cron Manager card (M-I-30, 1.14.0).
 *
 * Lives in AiBrainSettingsScreen, directly under GoogleConnectCard
 * (one call-site line, like PairingCard). Lists the device's
 * scheduled jobs from GET /api/billing/cron-jobs, parses natural
 * language time via POST /api/cron/parse ("har subah 8 baje" ->
 * `0 8 * * *`), and creates jobs via POST /api/billing/cron-jobs.
 * Enable/disable + delete use the matching hub routes.
 *
 * Honest limits, stated on the card: the hub ticks every 5 min, so
 * an exact minute is never promised (a job can fire a few minutes
 * late); the command runs on the phone as a CRON_COMMAND step when
 * due. Delete is two-tap (no un-delete server-side). Plan caps come
 * from the server (cron_cap 403) and are shown verbatim. All calls
 * are device-signed (PairingManager); no tokens on the phone, no
 * new Android permission.
 *
 * U9 (R1 audit follow-up, B6): EDIT — the same create flow with
 * prefill (command + the job's cron expression; a new NL time can
 * be parsed over it). There is NO server update route (hub
 * /api/billing/cron-jobs has create/list/enable/disable/delete
 * only), so an edit is create-then-delete: the NEW job is created
 * first and the old one deleted only after that succeeds, so a
 * failed edit never loses the running job. LAST RUN is shown per
 * job ("Pichla run") — the server returns last_run_at in the list
 * (hub.ts SELECT), and a never-run job says so explicitly instead
 * of showing nothing.
 */
@Composable
fun CronManagerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<CronJobClient.CronJob>>(emptyList()) }
    var statusText by remember { mutableStateOf("Jobs load ho rahe hain…") }
    var busy by remember { mutableStateOf(false) }
    var nlText by remember { mutableStateOf("") }
    var commandText by remember { mutableStateOf("") }
    var parsedExpr by remember { mutableStateOf<String?>(null) }
    var parsedNextRun by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    // U9 (R1): the job being edited, if any. Edit prefills the
    // create form below (command + the job's cron expression);
    // saving creates the new job FIRST, then deletes this one.
    var editingJobId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            when (val r = CronJobClient.listJobs(context)) {
                is CronJobClient.ListResult.Ok -> {
                    jobs = r.jobs
                    statusText = if (r.jobs.isEmpty()) {
                        "Koi scheduled job nahi hai — neeche se pehla banao."
                    } else {
                        "${r.jobs.size} job(s) — chalu: ${r.jobs.count { it.enabled }}, band: ${r.jobs.count { !it.enabled }}."
                    }
                }
                is CronJobClient.ListResult.Err -> {
                    statusText = r.message
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3EC))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scheduled Jobs — Cron Manager", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                text = "Jarvis ko time par kaam do — time natural language me likho (jaise: har subah 8 baje), " +
                    "kaam likho, Schedule karo. Time par Jarvis phone par ye command chalata hai. " +
                    "Hub har 5 minute par tick karta hai, to exact minute ka vaada nahi — kuch minute aage-peeche ho sakta hai.",
                fontSize = 12.sp
            )
            Text(statusText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

            // ---- Existing jobs ----
            for (job in jobs) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(job.command, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Har: ${job.cronExpr}", fontSize = 12.sp)
                        Text("Agla run: ${CronJobClient.formatNextRun(job.nextRunAt)}", fontSize = 12.sp)
                        if (job.lastRunAt != null) {
                            Text("Pichla run: ${CronJobClient.formatNextRun(job.lastRunAt)}", fontSize = 12.sp)
                        } else {
                            // U9 (R1): a never-run job says so
                            // explicitly — a missing line looked like
                            // a missing feature, it is a real state.
                            Text("Pichla run: abhi tak nahi chala", fontSize = 12.sp)
                        }
                        Text(if (job.enabled) "Status: Chalu" else "Status: Band", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    // U9 (R1): edit = the create flow
                                    // with prefill. Command + the
                                    // job's cron expression are loaded
                                    // into the form below; typing a new
                                    // time + "Time samjho" replaces the
                                    // expression, otherwise the old
                                    // time is kept as-is.
                                    editingJobId = job.id
                                    commandText = job.command
                                    parsedExpr = job.cronExpr
                                    parsedNextRun = job.nextRunAt
                                    nlText = ""
                                    statusText = "Edit kar rahe ho: ${job.command} — purana time ${job.cronExpr} rakha hai. Naya time likh kar 'Time samjho' dabao, ya waise hi 'Save edit' dabao."
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                            ) {
                                Text("Edit", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch(Dispatchers.IO) {
                                        val target = !job.enabled
                                        when (val r = CronJobClient.setEnabled(context, job.id, target)) {
                                            is CronJobClient.OpResult.Ok -> {
                                                statusText = if (target) "Job chalu ho gaya." else "Job band ho gaya."
                                                refresh()
                                            }
                                            is CronJobClient.OpResult.Err -> {
                                                statusText = r.message
                                            }
                                        }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                            ) {
                                Text(if (job.enabled) "Band karo" else "Chalu karo", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    if (confirmDeleteId == job.id) {
                                        confirmDeleteId = null
                                        busy = true
                                        scope.launch(Dispatchers.IO) {
                                            when (val r = CronJobClient.deleteJob(context, job.id)) {
                                                is CronJobClient.OpResult.Ok -> {
                                                    statusText = "Job delete ho gaya."
                                                    refresh()
                                                }
                                                is CronJobClient.OpResult.Err -> {
                                                    statusText = r.message
                                                }
                                            }
                                            busy = false
                                        }
                                    } else {
                                        confirmDeleteId = job.id
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                            ) {
                                Text(
                                    if (confirmDeleteId == job.id) "Pakka delete?" else "Delete",
                                    color = Color.White, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ---- Add a job (U9: doubles as the edit form when editingJobId != null) ----
            Text(
                if (editingJobId != null) "Edit job — neeche badlo aur Save edit dabao" else "Naya schedule",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = nlText,
                onValueChange = { nlText = it },
                label = { Text("Kab? — jaise: har subah 8 baje") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                label = { Text("Kya karna hai? — jaise: mujhe good morning bolo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !busy && nlText.isNotBlank(),
                onClick = {
                    busy = true
                    statusText = "Time samajh raha hai…"
                    scope.launch(Dispatchers.IO) {
                        when (val r = CronJobClient.parseNatural(context, nlText.trim())) {
                            is CronJobClient.ParseResult.Ok -> {
                                parsedExpr = r.cronExpr
                                parsedNextRun = r.nextRunAt
                                statusText = "Parsed: ${r.cronExpr} — agla run ${CronJobClient.formatNextRun(r.nextRunAt)}."
                            }
                            is CronJobClient.ParseResult.Unparseable -> {
                                parsedExpr = null
                                parsedNextRun = null
                                statusText = r.detail
                            }
                            is CronJobClient.ParseResult.Err -> {
                                parsedExpr = null
                                parsedNextRun = null
                                statusText = r.message
                            }
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
            ) {
                Text("Time samjho", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Button(
                enabled = !busy && parsedExpr != null && commandText.isNotBlank(),
                onClick = {
                    val expr = parsedExpr ?: return@Button
                    busy = true
                    statusText = if (editingJobId != null) "Job edit ho raha hai…" else "Job schedule ho raha hai…"
                    scope.launch(Dispatchers.IO) {
                        when (val r = CronJobClient.createJob(context, expr, commandText.trim())) {
                            is CronJobClient.CreateResult.Ok -> {
                                // U9 (R1): edit = create FIRST, then
                                // delete the old job. A failed create
                                // leaves the old job untouched (the
                                // branches below never reach the
                                // delete); a failed delete after a
                                // good create is reported honestly
                                // instead of claiming the edit worked.
                                val oldId = editingJobId
                                nlText = ""
                                commandText = ""
                                parsedExpr = null
                                parsedNextRun = null
                                editingJobId = null
                                if (oldId != null) {
                                    when (val del = CronJobClient.deleteJob(context, oldId)) {
                                        is CronJobClient.OpResult.Ok -> {
                                            statusText = "Job edit ho gaya — agla run ${CronJobClient.formatNextRun(r.nextRunAt)}."
                                        }
                                        is CronJobClient.OpResult.Err -> {
                                            statusText = "Naya job ban gaya (agla run ${CronJobClient.formatNextRun(r.nextRunAt)}), par purana job delete nahi hua — use list se haath se delete karo: ${del.message}"
                                        }
                                    }
                                } else {
                                    statusText = "Job schedule ho gaya — agla run ${CronJobClient.formatNextRun(r.nextRunAt)}."
                                }
                                refresh()
                            }
                            is CronJobClient.CreateResult.Cap -> {
                                statusText = r.detail
                            }
                            is CronJobClient.CreateResult.Err -> {
                                statusText = r.message
                            }
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
            ) {
                Text(
                    if (editingJobId != null) "Save edit" else "Schedule karo",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            // U9 (R1): cancel an edit without touching the job.
            if (editingJobId != null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        editingJobId = null
                        nlText = ""
                        commandText = ""
                        parsedExpr = null
                        parsedNextRun = null
                        statusText = "Edit cancel — job waisa ka waisa hai."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                ) {
                    Text("Edit cancel karo", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                enabled = !busy,
                onClick = { refresh() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
            ) {
                Text("Refresh jobs", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
