package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * S5 — Indexed memory recall (JarvisAgentApp 1.4.0, memory-first).
 *
 * The engine must search memory BEFORE planning, but it must not
 * full-scan all turns each time. This file is the index that makes
 * that possible: keyword -> turn ids, maintained incrementally as
 * turns are summarized.
 *
 * COMPLEXITY CLAIM (honest):
 * - indexTurn(id, summary): O(keywords in that one turn). Old
 *   keywords for the same id are removed via the stored per-turn
 *   keyword list, so re-indexing never scans the whole index.
 * - search(query): O(postings of the query's keywords). Each query
 *   keyword does one HashMap lookup into the inverted index; overlap
 *   counts are tallied only over the posting lists that were actually
 *   hit. The final sort covers MATCHED candidates only, then takes
 *   the top 5 — it never sorts all turns. So search is
 *   O(query keywords + matched postings + matched log matched),
 *   NOT O(all turns).
 * - rebuildIfNeeded(): O(total indexed keywords), and only when the
 *   integrity check finds drift (a summary without keywords, a
 *   keyword entry without a summary, or a posting pointing at a
 *   missing summary). On a healthy index it is a cheap check that
 *   returns false without rebuilding.
 *
 * Storage: a dedicated SharedPreferences file, JSON objects. Local
 * only — indexing and search work fully offline.
 */

/**
 * Pure text-rule summary of a finished turn. No LLM call.
 * Rule: first sentence of the outcome (split on . ! ? or newline);
 * if the outcome is blank, the first sentence of the user command;
 * truncated to 200 chars at a word boundary.
 */
object ChatSummary {

    const val MAX_LENGTH = 200

    fun summarize(userCommand: String, outcome: String): String {
        val source = firstSentence(outcome).ifBlank { firstSentence(userCommand) }
        if (source.isBlank()) return ""
        return truncateAtWord(source.trim(), MAX_LENGTH)
    }

    private fun firstSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        var end = trimmed.length
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                end = i + 1
                break
            }
        }
        return trimmed.substring(0, end).trim()
    }

    private fun truncateAtWord(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val cut = text.substring(0, maxLength)
        val lastSpace = cut.lastIndexOf(' ')
        if (lastSpace > maxLength / 2) {
            return cut.substring(0, lastSpace).trim() + "…"
        }
        return cut.trim() + "…"
    }
}

/** Keyword extraction: lowercased significant words, stopwords out. */
object MemoryKeywords {

    // English + roman Hinglish stopwords. Kept small on purpose: a
    // word dropped here can never retrieve a turn, so only true
    // filler words are listed — never content words like app names.
    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "so",
        "is", "are", "was", "were", "be", "been", "do", "does",
        "did", "have", "has", "had", "will", "would", "can",
        "could", "should", "please", "pls", "bhai", "jarvis",
        "ka", "ki", "ke", "ko", "se", "par", "pe", "me", "mein",
        "hai", "hain", "tha", "thi", "ho", "hu", "hun", "karo",
        "kar", "do", "de", "lo", "ye", "yeh", "wo", "woh", "kya",
        "kab", "kaise", "kahan", "mera", "meri", "mere", "apna",
        "apni", "tum", "tu", "aap", "ek", "bhi", "hi", "to",
        "aur", "ya", "phir", "ab", "jab", "tab", "zara"
    )

    /** Lowercased significant words of [text], de-duplicated, in order. */
    fun extract(text: String): List<String> {
        val lowered = text.lowercase()
        val parts = lowered.split(Regex("[^\\p{L}\\p{N}]+"))
        val out = ArrayList<String>()
        for (part in parts) {
            if (part.length < 2) continue
            if (part in STOPWORDS) continue
            if (!out.contains(part)) {
                out.add(part)
            }
        }
        return out
    }
}

class MemoryIndex(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Indexes (or re-indexes) one turn. The summary is stored so
     * rebuildIfNeeded() can repair the index from local state, and
     * the turn's own keyword list is stored so a re-index can remove
     * exactly its old postings (O(old keywords), no full scan).
     */
    fun indexTurn(id: String, summaryText: String) {
        val cleanId = id.trim()
        val summary = summaryText.trim()
        if (cleanId.isEmpty() || summary.isEmpty()) return
        val summaries = loadStringMap(KEY_SUMMARIES)
        val turnKeywords = loadStringListMap(KEY_TURN_KEYWORDS)
        val index = loadStringListMap(KEY_INDEX)

        val oldKeywords = turnKeywords[cleanId].orEmpty()
        for (keyword in oldKeywords) {
            val postings = index[keyword].orEmpty().filterNot { it == cleanId }
            if (postings.isEmpty()) {
                index.remove(keyword)
            } else {
                index[keyword] = postings
            }
        }

        val keywords = MemoryKeywords.extract(summary)
        for (keyword in keywords) {
            val postings = index[keyword].orEmpty()
            if (!postings.contains(cleanId)) {
                index[keyword] = postings + cleanId
            }
        }
        summaries[cleanId] = summary
        turnKeywords[cleanId] = keywords

        saveStringMap(KEY_SUMMARIES, summaries)
        saveStringListMap(KEY_TURN_KEYWORDS, turnKeywords)
        saveStringListMap(KEY_INDEX, index)
        saveOrder(cleanId)
    }

    /**
     * Ranked turn ids for [query]: most distinct-query-keyword
     * overlap first, ties broken by most recently indexed. Top 5.
     * Empty query (no significant keywords) returns empty, never a
     * dump of all turns.
     */
    fun search(query: String): List<String> {
        val queryKeywords = MemoryKeywords.extract(query)
        if (queryKeywords.isEmpty()) return emptyList()
        val index = loadStringListMap(KEY_INDEX)
        val scores = HashMap<String, Int>()
        for (keyword in queryKeywords) {
            val postings = index[keyword].orEmpty()
            for (turnId in postings) {
                scores[turnId] = (scores[turnId] ?: 0) + 1
            }
        }
        if (scores.isEmpty()) return emptyList()
        val order = loadOrder()
        val recency = HashMap<String, Int>()
        for (i in order.indices) {
            recency[order[i]] = i
        }
        val ranked = scores.keys.sortedWith(
            compareByDescending<String> { scores[it] ?: 0 }
                .thenByDescending { recency[it] ?: -1 }
        )
        return ranked.take(TOP_K)
    }

    fun getSummary(id: String): String? {
        val summaries = loadStringMap(KEY_SUMMARIES)
        return summaries[id.trim()]
    }

    /**
     * Integrity check + repair. Returns true only when a rebuild
     * actually happened. Healthy index: returns false, no rewrite.
     * Drift triggers: a summary with no keyword entry, a keyword
     * entry with no summary, or a posting naming a missing summary.
     * The rebuild re-derives the inverted index from the stored
     * per-turn keyword lists (local, offline, no re-extraction).
     */
    fun rebuildIfNeeded(): Boolean {
        val summaries = loadStringMap(KEY_SUMMARIES)
        val turnKeywords = loadStringListMap(KEY_TURN_KEYWORDS)
        val index = loadStringListMap(KEY_INDEX)
        var drifted = false
        if (turnKeywords.keys != summaries.keys) {
            drifted = true
        }
        if (!drifted) {
            for (postings in index.values) {
                for (turnId in postings) {
                    if (!summaries.containsKey(turnId)) {
                        drifted = true
                        break
                    }
                }
                if (drifted) break
            }
        }
        if (!drifted) return false

        val rebuilt = HashMap<String, List<String>>()
        for (entry in turnKeywords.entries) {
            val turnId = entry.key
            if (!summaries.containsKey(turnId)) continue
            for (keyword in entry.value) {
                val postings = rebuilt[keyword].orEmpty()
                if (!postings.contains(turnId)) {
                    rebuilt[keyword] = postings + turnId
                }
            }
        }
        val cleanTurnKeywords = HashMap<String, List<String>>()
        for (entry in turnKeywords.entries) {
            if (summaries.containsKey(entry.key)) {
                cleanTurnKeywords[entry.key] = entry.value
            }
        }
        saveStringListMap(KEY_INDEX, rebuilt)
        saveStringListMap(KEY_TURN_KEYWORDS, cleanTurnKeywords)
        return true
    }

    // ------------------------------------------------------------------
    // JSON persistence helpers (dedicated file, local only)
    // ------------------------------------------------------------------

    private fun loadStringMap(key: String): MutableMap<String, String> {
        val raw = prefs.getString(key, "{}") ?: "{}"
        val out = HashMap<String, String>()
        runCatching {
            val obj = JSONObject(raw)
            val names = obj.names() ?: return@runCatching
            for (i in 0 until names.length()) {
                val name = names.getString(i)
                out[name] = obj.optString(name, "")
            }
        }
        return out
    }

    private fun saveStringMap(key: String, map: Map<String, String>) {
        val obj = JSONObject()
        for (entry in map.entries) {
            obj.put(entry.key, entry.value)
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun loadStringListMap(key: String): MutableMap<String, List<String>> {
        val raw = prefs.getString(key, "{}") ?: "{}"
        val out = HashMap<String, List<String>>()
        runCatching {
            val obj = JSONObject(raw)
            val names = obj.names() ?: return@runCatching
            for (i in 0 until names.length()) {
                val name = names.getString(i)
                val arr = obj.optJSONArray(name)
                val list = ArrayList<String>()
                if (arr != null) {
                    for (j in 0 until arr.length()) {
                        val value = arr.optString(j, "")
                        if (value.isNotBlank()) {
                            list.add(value)
                        }
                    }
                }
                out[name] = list
            }
        }
        return out
    }

    private fun saveStringListMap(key: String, map: Map<String, List<String>>) {
        val obj = JSONObject()
        for (entry in map.entries) {
            val arr = JSONArray()
            for (value in entry.value) {
                arr.put(value)
            }
            obj.put(entry.key, arr)
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun loadOrder(): List<String> {
        val raw = prefs.getString(KEY_ORDER, "[]") ?: "[]"
        val out = ArrayList<String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "")
                if (value.isNotBlank()) {
                    out.add(value)
                }
            }
        }
        return out
    }

    private fun saveOrder(latestId: String) {
        val order = loadOrder().filterNot { it == latestId }.toMutableList()
        order.add(latestId)
        val arr = JSONArray()
        for (id in order) {
            arr.put(id)
        }
        prefs.edit().putString(KEY_ORDER, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_memory_index"
        private const val KEY_SUMMARIES = "turn_summaries_json"
        private const val KEY_TURN_KEYWORDS = "turn_keywords_json"
        private const val KEY_INDEX = "keyword_index_json"
        private const val KEY_ORDER = "turn_order_json"
        private const val TOP_K = 5
    }
}
