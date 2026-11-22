package com.jarvis.autonomous.agent

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-extension stage 2 — RESEARCH free/open-source options (DESIGN §3).
 *
 * Pure-Kotlin orchestration (no Android imports) so the budget and
 * parsing logic unit-tests on a plain JVM with fake LLM/fetcher.
 * The two transports are injected as blocking function interfaces:
 * - [ResearchLlm]: the LLM leg. The real adapter is
 *   `LlmClient.researchCandidates` (see LlmClient.kt) — see the
 *   D0.4 deviation note in the implementation report: there is NO
 *   app→hub LLM call path (the hub `/api/llm/route` needs the
 *   server-to-server `x-internal-secret`, which the phone must never
 *   hold), so research reuses LlmClient's existing direct BYOK
 *   callers (`callGeminiApi` / `callOpenAiCompatibleApi`) with the
 *   same model + fallback chain, OkHttp client and timeouts.
 * - [ResearchFetcher]: the verification leg. The default fetcher
 *   below uses HttpURLConnection with the SAME 25 s connect / 45 s
 *   read timeouts as the executor's OkHttp `httpGet` pattern
 *   (AgentPlanExecutor, private to that file, so the researcher
 *   carries its own fetch fun — same timeouts, same 200 KB cap, no
 *   auth headers/cookies, no redirects off the allowlist).
 *
 * The LLM's answer is a LEAD LIST, never a verdict — every lead is
 * re-checked deterministically (§3.4) before it reaches the
 * classifier. Nothing fetched is executed, parsed as code, or stored
 * as code: fetches read licence/README text for classification only.
 */
data class FetchResult(val code: Int, val body: String)

fun interface ResearchLlm {
    /** Returns the LLM's raw reply text for [prompt], or null on failure. */
    suspend fun ask(prompt: String): String?
}

fun interface ResearchFetcher {
    /** One GET; null on network failure. Must not send auth/cookies. */
    fun fetch(url: String): FetchResult?
}

enum class ResearchOutcome { CANDIDATES_FOUND, NONE_FOUND, BUDGET_EXCEEDED, BLOCKED }

data class ResearchReport(
    val capability: String,
    val signature: String,
    val candidates: List<ResearchCandidate>,
    val researchedAtMs: Long,
    val llmCalls: Int,
    val fetches: Int,
    val durationMs: Long,
    val outcome: ResearchOutcome,
    /** Machine reason only (e.g. "malformed_llm_json"); never user content. */
    val failureReason: String = ""
)

object SelfExtensionResearcher {

    /** Fixed English research prompt (parent decision §12-5). The
     *  capability string is the ONLY failure-derived input — never
     *  goal text, step inputs, or user data (S8). */
    fun researchPrompt(capability: String): String = """
        List up to 5 free, open-source tools or libraries that provide this capability: $capability.
        Constraints: open-source or free-to-use, cost $0, no credit card, no account required if possible.
        Respond ONLY with a JSON array, no prose, no markdown fences. Each element must be an object with exactly these fields:
        {"name": string, "url": string, "licence": string (SPDX id or "UNKNOWN"), "cost": string ("$0" or "UNKNOWN"), "description": string, "lastUpdated": string ("YYYY-MM-DD" or ""), "steps": [{"action": string, "target": string, "input": string}] (the on-device procedure using only these actions: OPEN_APP, TAP_NODE, TYPE_TEXT, SCROLL, NAVIGATE, YOUTUBE_SEARCH, YOUTUBE_PLAY, MEDIA_CONTROL, FLASHLIGHT, VOLUME, SET_ALARM, WAIT, SAVE_MEMORY, CREATE_PROJECT, WRITE_PROJECT_FILE, BUILD, GITHUB_PUSH, SUPABASE_TABLES, VERCEL_DEPLOY, HTTP_CHECK; empty array if the capability needs new code)}
        """.trimIndent()

    /** Allowlisted research hosts (§3.3, = server SELF_EXT_ALLOWED_HOSTS). */
    private val ALLOWED_HOSTS = setOf(
        "github.com", "raw.githubusercontent.com", "gitlab.com",
        "opensource.org", "spdx.org", "repo.maven.apache.org",
        "plugins.gradle.org", "dl.google.com"
    )

    fun isAllowlistedUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            if (parsed.protocol != "http" && parsed.protocol != "https") return false
            val host = parsed.host.lowercase()
            if (host in ALLOWED_HOSTS) return true
            host.endsWith(".apache.org") || host.endsWith(".gnu.org")
        } catch (e: Exception) {
            false
        }
    }

    // ---- Deterministic licence / cost / card checks (§3.4) ----
    val LICENCE_ALLOWLIST = CapabilityClassifier.LICENSE_ALLOWLIST
    val LICENCE_COPYLEFT = CapabilityClassifier.LICENSE_COPYLEFT

    private val PAID_SIGNALS = listOf(
        "price", "pricing", "per month", "per user", "subscription",
        "premium", "pro plan", "credit card", "card required", "add a card",
        "paywall", "buy now"
    )
    private val ACCOUNT_SIGNALS = listOf(
        "sign up", "sign-up", "create an account", "api key", "api_key",
        "auth token", "login required", "oauth"
    )

    /** SPDX id found in licence text, else "UNKNOWN" (never guessed). */
    fun detectLicence(text: String): String {
        val upper = text.uppercase()
        // Longest/most specific ids first so "Apache-2.0" wins over "Apache".
        val candidates = listOf(
            "Apache-2.0", "BSD-3-Clause", "BSD-2-Clause", "GPL-3.0",
            "GPL-2.0", "LGPL-3.0", "LGPL-2.1", "AGPL-3.0", "MPL-2.0",
            "EPL-2.0", "CC0-1.0", "Unlicense", "Zlib", "ISC", "MIT"
        )
        for (id in candidates) {
            if (upper.contains(id.uppercase())) return id
        }
        return "UNKNOWN"
    }

    fun hasPaidSignal(text: String): Boolean {
        val lower = text.lowercase()
        if (PAID_SIGNALS.any { lower.contains(it) }) return true
        // A bare "$" / "€" / "£" / "₹" is a paid signal (§3.4 — a "$"
        // in a code sample can false-positive; the bias is toward
        // NOT auto-adding, so a false positive is acceptable).
        return lower.contains("\$") || lower.contains("€") || lower.contains("£") || lower.contains("₹")
    }

    fun hasAccountSignal(text: String): Boolean {
        val lower = text.lowercase()
        return ACCOUNT_SIGNALS.any { lower.contains(it) }
    }

    /** Licence/source page URL for a candidate name on an allowlisted host. */
    fun licenceUrlFor(sourceUrl: String): String? {
        if (!isAllowlistedUrl(sourceUrl)) return null
        return try {
            val parsed = URL(sourceUrl)
            if (parsed.host == "github.com") {
                // https://github.com/<owner>/<repo> -> raw LICENSE
                val parts = parsed.path.trim('/').split("/").filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/HEAD/LICENSE"
                } else null
            } else if (parsed.host == "raw.githubusercontent.com") sourceUrl
            else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Strict JSON parse of the LLM lead list (§3.1): malformed JSON
     * is a research failure, never best-effort prose parsing.
     * Accepts a top-level array, or an object with a "candidates"
     * array. Returns null on any shape violation.
     */
    fun parseLeadList(raw: String?): List<Map<String, Any?>>? {
        if (raw.isNullOrBlank()) return null
        val parsed = SelfExtensionJson.parse(raw.trim()) ?: return null
        @Suppress("UNCHECKED_CAST")
        val array: List<Any?> = when (parsed) {
            is List<*> -> parsed as List<Any?>
            is Map<*, *> -> {
                val obj = parsed as Map<String, Any?>
                SelfExtensionJson.asList(obj["candidates"]) ?: return null
            }
            else -> return null
        }
        val out = ArrayList<Map<String, Any?>>()
        for (entry in array) {
            val obj = SelfExtensionJson.asObject(entry) ?: return null
            out.add(obj)
        }
        return out
    }

    fun leadToCandidate(lead: Map<String, Any?>): ResearchCandidate? {
        val name = SelfExtensionJson.asString(lead["name"])?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val url = SelfExtensionJson.asString(lead["url"])?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!isAllowlistedUrl(url)) return null
        val licence = SelfExtensionJson.asString(lead["licence"])?.trim()?.ifBlank { "UNKNOWN" } ?: "UNKNOWN"
        val cost = SelfExtensionJson.asString(lead["cost"])?.trim()?.ifBlank { "UNKNOWN" } ?: "UNKNOWN"
        val lastUpdated = SelfExtensionJson.asString(lead["lastUpdated"])?.trim()?.takeIf { it.isNotBlank() }
        val steps = ArrayList<SkillStep>()
        val stepsRaw = SelfExtensionJson.asList(lead["steps"])
        if (stepsRaw != null) {
            for (entry in stepsRaw) {
                val stepObj = SelfExtensionJson.asObject(entry) ?: return null
                val action = SelfExtensionJson.asString(stepObj["action"])?.trim()?.uppercase() ?: return null
                if (action.isBlank()) return null
                steps.add(
                    SkillStep(
                        action = action,
                        target = SelfExtensionJson.asString(stepObj["target"]) ?: "",
                        input = SelfExtensionJson.asString(stepObj["input"])
                    )
                )
            }
        }
        return ResearchCandidate(
            name = name,
            sourceUrl = url,
            licence = licence,
            costUsd = cost,
            needsCard = false,
            needsAccount = false,
            needsNetwork = false,
            lastUpdated = lastUpdated,
            suggestedSteps = steps,
            signalsComplete = true
        )
    }

    /**
     * One bounded research run (§3.5): ≤2 LLM calls, ≤3 fetches,
     * 200 KB/fetch, 90 s wall, ≤5 candidates. [nowMs] is injected
     * for tests; production passes System.currentTimeMillis().
     * Suspend because the LLM leg is suspend; the blocking fetch
     * leg runs on the caller's IO context (the loop wraps this in
     * withContext(Dispatchers.IO)).
     */
    suspend fun research(
        capability: String,
        signature: String,
        llm: ResearchLlm,
        fetcher: ResearchFetcher,
        nowMs: Long = System.currentTimeMillis()
    ): ResearchReport {
        val startMs = nowMs
        var now = nowMs
        fun elapsed(): Long = now - startMs
        fun tick(): Long {
            now = System.currentTimeMillis()
            return now
        }
        var llmCalls = 0
        var fetches = 0

        fun finish(candidates: List<ResearchCandidate>, outcome: ResearchOutcome, reason: String = ""): ResearchReport =
            ResearchReport(
                capability = capability,
                signature = signature,
                candidates = candidates.take(SelfExtensionBudgets.MAX_CANDIDATES),
                researchedAtMs = startMs,
                llmCalls = llmCalls,
                fetches = fetches,
                durationMs = tick() - startMs,
                outcome = outcome,
                failureReason = reason
            )

        // LLM leg, call 1: candidate leads.
        tick()
        if (elapsed() > SelfExtensionBudgets.RESEARCH_WALL_MS) {
            return finish(emptyList(), ResearchOutcome.BUDGET_EXCEEDED, "research_timeout")
        }
        llmCalls++
        val rawLeads = try {
            llm.ask(researchPrompt(capability))
        } catch (e: Exception) {
            null
        }
        val leads = parseLeadList(rawLeads)
            ?: return finish(emptyList(), ResearchOutcome.NONE_FOUND, "malformed_llm_json")
        if (leads.isEmpty()) {
            return finish(emptyList(), ResearchOutcome.NONE_FOUND, "no_leads")
        }

        // Deterministic re-check leg: ≤3 fetches of licence pages.
        val candidates = ArrayList<ResearchCandidate>()
        for (lead in leads) {
            if (candidates.size >= SelfExtensionBudgets.MAX_CANDIDATES) break
            tick()
            if (elapsed() > SelfExtensionBudgets.RESEARCH_WALL_MS) {
                return finish(candidates, ResearchOutcome.BUDGET_EXCEEDED, "research_timeout")
            }
            val base = leadToCandidate(lead) ?: continue
            var candidate = base
            // Fetch leg (only while fetches remain): confirm licence /
            // cost on the candidate's own licence page or repo page.
            if (fetches < SelfExtensionBudgets.MAX_FETCHES) {
                val fetchUrl = licenceUrlFor(base.sourceUrl) ?: base.sourceUrl
                val fetched = try {
                    fetches++
                    fetcher.fetch(fetchUrl)
                } catch (e: Exception) {
                    null
                }
                if (fetched != null && fetched.code in 200..299) {
                    val text = fetched.body.take(SelfExtensionBudgets.MAX_FETCH_BYTES.toInt())
                    val fetchedLicence = detectLicence(text)
                    val paid = hasPaidSignal(text) || hasPaidSignal(base.costUsd)
                    val account = hasAccountSignal(text)
                    val snippets = extractEvidenceSnippets(text)
                    candidate = base.copy(
                        licence = if (fetchedLicence != "UNKNOWN") fetchedLicence else base.licence,
                        licenceUrl = fetchUrl,
                        needsCard = paid,
                        needsAccount = account,
                        needsNetwork = base.needsNetwork,
                        evidence = snippets
                    )
                }
            }
            // Cost "$0" is a claim until the fetch says otherwise;
            // a paid signal on the fetched page already flipped
            // needsCard above; the classifier re-checks costUsd too.
            candidates.add(candidate)
        }
        if (candidates.isEmpty()) {
            return finish(emptyList(), ResearchOutcome.NONE_FOUND, "no_allowlisted_leads")
        }
        return finish(candidates, ResearchOutcome.CANDIDATES_FOUND)
    }

    /** ≤5 evidence snippets (≤200 chars each) supporting licence/cost. */
    fun extractEvidenceSnippets(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()
            if (lower.contains("licence") || lower.contains("license") ||
                lower.contains("mit") || lower.contains("apache") ||
                lower.contains("free") || lower.contains("permission")
            ) {
                out.add(trimmed.take(200))
                if (out.size >= 5) break
            }
        }
        return out
    }

    /** Default fetcher: HttpURLConnection, 25 s connect / 45 s read
     *  (the executor's OkHttp httpGet timeouts), 200 KB body cap, no
     *  auth headers, no cookies, no redirects off the allowlist. */
    fun defaultFetcher(): ResearchFetcher = ResearchFetcher { url ->
        if (!isAllowlistedUrl(url)) return@ResearchFetcher null
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 25_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("User-Agent", "JarvisAgentApp/1.11.0-PRO (self-extension research)")
            conn.setRequestProperty("Accept", "text/plain, text/html;q=0.9, */*;q=0.1")
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: return@ResearchFetcher FetchResult(code, "")
                if (!isAllowlistedUrl(location)) return@ResearchFetcher FetchResult(code, "")
                return@ResearchFetcher FetchResult(code, "")
            }
            if (code !in 200..299) return@ResearchFetcher FetchResult(code, "")
            val bytes = conn.inputStream.use { stream ->
                val buf = ByteArray(SelfExtensionBudgets.MAX_FETCH_BYTES.toInt() + 1)
                var read = 0
                var total = 0
                while (total <= SelfExtensionBudgets.MAX_FETCH_BYTES) {
                    read = stream.read(buf, total, buf.size - total)
                    if (read < 0) break
                    total += read
                    if (total >= buf.size) break
                }
                buf.copyOf(minOf(total, SelfExtensionBudgets.MAX_FETCH_BYTES.toInt()))
            }
            FetchResult(code, String(bytes, Charsets.UTF_8))
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
