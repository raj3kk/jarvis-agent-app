package com.jarvis.autonomous.agent

/**
 * Self-extension on-device pre-scan (DESIGN §6.1, E4).
 *
 * Pure Kotlin — ZERO Android imports — JVM-testable. String/regex
 * over the step list + any fetched reference text (≤ 200 KB). It is
 * a PRE-FILTER ONLY: a clean pre-scan never approves a CODE skill and
 * never replaces the authoritative Render re-scan. A pre-scan hit
 * means immediate REJECT (no server submit — don't waste the review
 * queue on something the phone already knows is dirty).
 *
 * Pattern families mirror the server's SCAN_PATTERNS
 * (`omnijarvis-server/src/skills.ts`, same first-hit order) plus the
 * credential-assignment and exfiltration families from §6.1.
 */
data class ScanFinding(val pattern: String, val where: String)

object SelfExtensionScanner {

    private data class Pattern(val regex: Regex, val name: String)

    private val PATTERNS = listOf(
        Pattern(Regex("eval\\s*\\("), "eval("),
        Pattern(Regex("exec\\s*\\("), "exec("),
        Pattern(Regex("os\\.system"), "os.system"),
        Pattern(Regex("subprocess"), "subprocess"),
        Pattern(Regex("requests\\.post"), "requests.post"),
        Pattern(Regex("child_process"), "child_process"),
        Pattern(Regex("DexClassLoader"), "DexClassLoader"),
        Pattern(Regex("Runtime\\.exec"), "Runtime.exec"),
        Pattern(Regex("PathClassLoader"), "PathClassLoader"),
        Pattern(Regex("ScriptEngine"), "ScriptEngine"),
        Pattern(Regex("System\\.load"), "System.load"),
        Pattern(Regex("api[_-]?key\\s*=", RegexOption.IGNORE_CASE), "credential_assignment"),
        Pattern(Regex("password\\s*=", RegexOption.IGNORE_CASE), "credential_assignment"),
        Pattern(Regex("token\\s*=\\s*[\"']", RegexOption.IGNORE_CASE), "credential_assignment"),
        Pattern(Regex("socket\\s*\\("), "socket"),
        Pattern(Regex("http[s]?://[^\\s\"']+", RegexOption.IGNORE_CASE), "remote_url")
    )

    /** A long high-entropy blob smells like an obfuscated payload (§6.1). */
    private const val BLOB_MIN_LENGTH = 200

    /**
     * Scans [steps] (action + target + input joined) and [fetchedText]
     * (licence/README text, may be ""). Returns every distinct
     * pattern hit, first-hit order; empty = clean.
     *
     * NOTE: the `remote_url` pattern fires on any URL in step text —
     * a step that carries a URL is network-touching by construction,
     * and the classifier already routes network steps to APPROVAL;
     * the scanner flags it here so the installer gate also sees it.
     * Fetched licence/README text is NOT scanned for URLs (a README
     * legitimately links its own repo) — only for the code/credential
     * families.
     */
    fun preScan(steps: List<SkillStep>, fetchedText: String = ""): List<ScanFinding> {
        val findings = ArrayList<ScanFinding>()
        val seen = HashSet<String>()
        fun add(pattern: String, where: String) {
            if (seen.add(pattern)) findings.add(ScanFinding(pattern, where))
        }
        steps.forEachIndexed { index, step ->
            val haystack = listOf(step.action, step.target, step.input ?: "").joinToString(" ")
            for (pattern in PATTERNS) {
                if (pattern.regex.containsMatchIn(haystack)) {
                    add(pattern.name, "step[$index]")
                }
            }
            if (hasHighEntropyBlob(step.input ?: "") || hasHighEntropyBlob(step.target)) {
                add("high_entropy_blob", "step[$index]")
            }
        }
        if (fetchedText.isNotEmpty()) {
            for (pattern in PATTERNS) {
                if (pattern.name == "remote_url") continue
                if (pattern.regex.containsMatchIn(fetchedText)) {
                    add(pattern.name, "fetched_text")
                }
            }
            if (hasHighEntropyBlob(fetchedText)) add("high_entropy_blob", "fetched_text")
        }
        return findings
    }

    /** First hit only, for the classifier's C11 row. Null = clean. */
    fun firstHit(steps: List<SkillStep>, fetchedText: String = ""): String? =
        preScan(steps, fetchedText).firstOrNull()?.pattern

    /**
     * High-entropy blob heuristic: a single token ≥ 200 chars with
     * very few distinct characters per length is not prose — it is
     * an encoded/obfuscated blob. Plain long words (a URL, a file
     * path) have spaces/slashes; a blob does not.
     */
    fun hasHighEntropyBlob(text: String): Boolean {
        for (token in text.split(Regex("\\s+"))) {
            if (token.length < BLOB_MIN_LENGTH) continue
            val distinct = token.toSet().size
            // A base64-ish blob: long, few separators, high distinct
            // ratio. Ordinary prose tokens never reach 200 chars.
            if (distinct > 20 && !token.contains("/") && !token.contains(".")) return true
        }
        return false
    }
}
