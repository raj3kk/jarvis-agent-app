package com.jarvis.autonomous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.jarvis.autonomous.data.SkillsClient
import kotlinx.coroutines.launch

/**
 * 1.21.0-PRO — Skills tab (tab 10). Browse + install the hub's
 * skills library (hub v22, migration 0018 — 12 starter skills).
 *
 * Browse: GET /api/skills/catalog (device-signed, tenant resolved
 * server-side). Install: POST /api/skills/catalog/install (same
 * auth; the tenant id is resolved by SkillsClient.ensureTenantId).
 * The tenant is Pro, so all 12 are installable. Tier/cap 403s are
 * shown honestly — never a fake success. An idempotent repeat
 * shows "pehle se installed tha" (deduped:true), not an error.
 * Installing a skill records it in D1 (skill_catalog_installs);
 * the skill itself runs through the hub — this screen is the
 * browse + install surface, not an executor.
 */
@Composable
fun SkillsScreen() {
    val context = LocalContext.current
    val skillsClient = remember { SkillsClient }
    val scope = rememberCoroutineScope()

    var skills by remember { mutableStateOf<List<SkillsClient.CatalogSkill>>(emptyList()) }
    var planTier by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            status = "Skills ki list la raha hoon…"
            when (val result = skillsClient.listCatalog(context.applicationContext)) {
                is SkillsClient.CatalogOutcome.Ok -> {
                    skills = result.result.skills
                    planTier = result.result.planTier
                    status = if (result.result.skills.isEmpty()) {
                        "Abhi koi skill nahi mili — dobara try karo."
                    } else {
                        "${result.result.skills.size} skills — ${result.result.skills.count { it.installed }} installed hain."
                    }
                    loaded = true
                }
                is SkillsClient.CatalogOutcome.Err -> {
                    status = result.message
                    loaded = true
                }
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun install(skill: SkillsClient.CatalogSkill) {
        scope.launch {
            installingId = skill.id
            status = "'${skill.name}' install kar raha hoon…"
            when (val result = skillsClient.installSkill(context.applicationContext, skill.id)) {
                is SkillsClient.InstallOutcome.Ok -> {
                    skills = skills.map {
                        if (it.id == skill.id) it.copy(installed = true) else it
                    }
                    status = if (result.deduped) {
                        "✓ '${skill.name}' pehle se installed tha."
                    } else {
                        "✓ '${skill.name}' install ho gaya."
                    }
                }
                is SkillsClient.InstallOutcome.TierRequired -> {
                    status = "'${skill.name}' ${result.requiredTier} plan me hai — " +
                        "aapka plan: ${result.planTier.ifBlank { "Free" }}. Upgrade ke bina install nahi hoga."
                }
                is SkillsClient.InstallOutcome.CapReached -> {
                    status = "Install limit khatam (${result.installed}/${result.cap}) — " +
                        "purana hatao ya upgrade karo."
                }
                is SkillsClient.InstallOutcome.Err -> {
                    status = result.message
                }
            }
            installingId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Skills",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )
        Text(
            text = "Hub ki skills library — jo chahiye, install karo. Installed skill hub par record hoti hai.",
            fontSize = 13.sp,
            color = Color(0xFF8A7A70)
        )
        if (planTier.isNotBlank()) {
            Text(
                text = "Aapka plan: ${planTier.replaceFirstChar { it.uppercaseChar() }} · ${skills.size} skills",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (busy && !loaded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(text = "Skills ki list la raha hoon…", fontSize = 13.sp)
            }
        }
        if (status.isNotBlank()) {
            Text(text = status, fontSize = 13.sp, color = Color(0xFF5C4A3A))
        }

        OutlinedButton(
            onClick = { refresh() },
            enabled = !busy && installingId == null
        ) { Text("Dobara lao") }

        if (loaded && skills.isEmpty() && !busy) {
            Text(
                text = "Abhi koi skill nahi dikhi — pairing ya network check karke 'Dobara lao' dabao.",
                fontSize = 13.sp,
                color = Color(0xFF8A7A70)
            )
        }
        for (skill in skills) {
            val installing = installingId == skill.id
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = skill.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = skill.tier.replaceFirstChar { it.uppercaseChar() },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (skill.tier.equals("free", ignoreCase = true)) {
                                Color(0xFF5C7A4A)
                            } else {
                                Color(0xFFB85C38)
                            }
                        )
                    }
                    if (skill.description.isNotBlank()) {
                        Text(
                            text = skill.description,
                            fontSize = 13.sp,
                            color = Color(0xFF5C4A3A)
                        )
                    }
                    if (skill.category.isNotBlank()) {
                        Text(
                            text = skill.category,
                            fontSize = 12.sp,
                            color = Color(0xFF8A7A70)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (skill.installed) {
                            Text(
                                text = "Installed ✓",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5C7A4A)
                            )
                        } else {
                            Button(
                                onClick = { install(skill) },
                                enabled = !busy && installingId == null
                            ) {
                                Text(if (installing) "Install ho raha hai…" else "Install")
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
