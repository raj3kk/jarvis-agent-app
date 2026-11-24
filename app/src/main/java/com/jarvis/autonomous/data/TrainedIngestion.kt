package com.jarvis.autonomous.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * R2.1 (1.17.0-PRO) — TrainedIngestion: how a document / PDF / image /
 * link / voice note becomes a memory Jarvis can work from.
 *
 * Every path ends the same way: the text is saved on-device in
 * DocumentMemoryStore (I1, caps 50 docs / 100k chars / 4 MB FIFO)
 * and a summary is pushed to the hub's shared memory
 * (MemoryClient.storeMemory, kind "document", first ~3,500 chars —
 * the hub caps a memory at 4,000 chars). A failed hub push never
 * blocks the on-device save; the result says so honestly.
 *
 * Paths:
 * - Text / paste / voice transcript -> saveDocument directly.
 * - Link -> plain HTTP GET, strip tags, then saveDocument.
 * - PDF -> the doc worker (Vercel, no auth): /doc/extract first;
 *   when the text is empty/short (a scanned PDF) -> /doc/ocr.
 *   On-device PDF extraction is NOT in R2 (no library exists).
 * - Image / camera photo -> on-device ML Kit OCR, Latin +
 *   Devanagari (bundled, offline, no download at first use).
 * - Audio -> the UI captures a transcript with the system
 *   SpeechRecognizer (RecognizerIntent) and hands the text here.
 *
 * Privacy note: these are the USER's own documents, given to Jarvis
 * to remember, stored in their own tenant — so ingestion does NOT
 * redact (unlike the event log, which redacts everything sensitive).
 * Nothing here ever leaves the phone except the D1 summary push.
 */
class TrainedIngestion(
    private val context: Context,
    private val documentMemory: DocumentMemoryStore,
    private val memoryClient: MemoryClient
) {
    sealed class IngestResult {
        data class Ok(
            val docId: String,
            val title: String,
            val chars: Int,
            val d1Pushed: Boolean,
            val d1Note: String
        ) : IngestResult()

        data class Err(val reason: String) : IngestResult()
    }

    companion object {
        const val DOC_WORKER_URL = "https://omnijarvis-render.vercel.app"
        const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024 // == worker 4 MB cap
        const val OCR_FALLBACK_MIN_CHARS = 100 // less text than this -> try OCR
        const val D1_SUMMARY_CHARS = 3500

        /** Pure: strip tags + collapse whitespace (link ingestion). */
        fun stripHtml(html: String): String {
            var text = html.replace(Regex("(?is)<script.*?</script>"), " ")
            text = text.replace(Regex("(?is)<style.*?</style>"), " ")
            text = text.replace(Regex("<[^>]+>"), " ")
            text = text.replace(Regex("&nbsp;|&#160;"), " ")
                .replace(Regex("&amp;"), "&")
                .replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">")
                .replace(Regex("&quot;"), "\"")
            return text.replace(Regex("\\s+"), " ").trim()
        }

        /** Pure: merge Latin + Devanagari OCR output, deduped by line. */
        fun mergeOcrTexts(latin: String, devanagari: String): String {
            val seen = LinkedHashSet<String>()
            val out = StringBuilder()
            for (text in listOf(latin, devanagari)) {
                for (line in text.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && seen.add(trimmed)) {
                        if (out.isNotEmpty()) out.append('\n')
                        out.append(trimmed)
                    }
                }
            }
            return out.toString()
        }
    }

    private val httpClient = OkHttpClient.Builder().build()

    /** Text (paste / voice transcript / link body) -> on-device + D1. */
    suspend fun ingestText(title: String, text: String, source: String): IngestResult {
        val clean = text.trim()
        if (clean.isEmpty()) return IngestResult.Err("Khaali text — kuch likha hi nahi.")
        val saved = withContext(Dispatchers.IO) {
            runCatching { documentMemory.saveDocument(title, clean, source) }.getOrNull()
        } ?: return IngestResult.Err("Phone par save nahi ho paya.")
        if (!saved.ok) return IngestResult.Err("Save nahi hua: ${saved.reason}")
        val docId = saved.id ?: ""
        val d1 = pushSummaryToHub(saved.title, clean)
        return IngestResult.Ok(docId, saved.title, saved.charCount, d1.first, d1.second)
    }

    /** Link -> fetch the page, strip tags, ingest as text. */
    suspend fun ingestLink(url: String): IngestResult {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return IngestResult.Err("Link http(s) se shuru hona chahiye.")
        }
        val html = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(cleanUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) Jarvis/1.20.0")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.string()
                }
            }.getOrNull()
        } ?: return IngestResult.Err("Link khul nahi paya — network ya page check karo.")
        val text = stripHtml(html)
        if (text.length < 50) return IngestResult.Err("Page se padhne layak text nahi mila.")
        val title = runCatching {
            Regex("(?is)<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim()
        }.getOrNull().orEmpty().ifBlank { cleanUrl }
        return ingestText(title, text, "link")
    }

    /** PDF -> doc worker /doc/extract, OCR fallback when text is thin. */
    suspend fun ingestPdf(uri: Uri, title: String): IngestResult {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        } ?: return IngestResult.Err("PDF khul nahi payi.")
        if (bytes.size > MAX_UPLOAD_BYTES) {
            return IngestResult.Err("PDF 4 MB se badi hai — worker limit hai.")
        }
        val fileName = title.ifBlank { "document.pdf" }.take(120)
        val extracted = callDocWorker("/doc/extract", bytes, fileName)
        var text = extracted.first
        if (extracted.second != null) return IngestResult.Err(extracted.second!!)
        if (text.length < OCR_FALLBACK_MIN_CHARS) {
            // Scanned PDF (no text layer) — OCR the pages.
            val ocr = callDocWorker("/doc/ocr", bytes, fileName)
            if (ocr.second != null) return IngestResult.Err(ocr.second!!)
            text = ocr.first
        }
        if (text.isBlank()) {
            return IngestResult.Err("PDF se koi text nahi mila — scanned ho to OCR bhi fail hua.")
        }
        return ingestText(fileName, text, "pdf")
    }

    /** Image / camera photo -> on-device ML Kit OCR (Latin + Devanagari). */
    suspend fun ingestImage(uri: Uri, title: String): IngestResult {
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
            ?: return IngestResult.Err("Photo khul nahi payi.")
        val latinText = runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image).await().text
        }.getOrDefault("")
        val devanagariText = runCatching {
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                .process(image).await().text
        }.getOrDefault("")
        val merged = mergeOcrTexts(latinText, devanagariText)
        if (merged.isBlank()) {
            return IngestResult.Err("Photo se koi text nahi padha — saaf photo dobara lo.")
        }
        return ingestText(title.ifBlank { "photo" }, merged, "image")
    }

    // ---- Doc worker (Vercel, no auth) ----

    /** Returns (text, errorReason?) — errorReason null on success. */
    private suspend fun callDocWorker(
        path: String,
        bytes: ByteArray,
        fileName: String
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    bytes.toRequestBody("application/pdf".toMediaType())
                ).build()
            val request = Request.Builder().url("$DOC_WORKER_URL$path").post(body).build()
            httpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null || !json.optBoolean("ok", false)) {
                    val err = json?.optString("error", "") ?: ""
                    val reason = when {
                        resp.code == 413 -> "File 4 MB se badi hai."
                        resp.code == 504 -> "OCR timeout — PDF chhota karke dobara try karo."
                        err.isNotBlank() -> "Worker error: $err"
                        else -> "Worker se jawab nahi mila (HTTP ${resp.code})."
                    }
                    return@runCatching "" to reason
                }
                (json.optString("text", "")) to null
            }
        }.getOrElse { e ->
            "" to "Worker se connect nahi ho paya: ${e.javaClass.simpleName}"
        }
    }

    // ---- Hub push (best-effort, never blocks the on-device save) ----

    /** Returns (pushed, note). */
    private suspend fun pushSummaryToHub(title: String, text: String): Pair<Boolean, String> {
        val summary = "[Doc: $title]\n" + text.take(D1_SUMMARY_CHARS)
        return when (val r = runCatching { memoryClient.storeMemory(summary, "document") }.getOrNull()) {
            is MemoryClient.StoreResult.Ok -> true to "Hub par bhi save ho gaya."
            is MemoryClient.StoreResult.Err -> {
                val note = when (r.reason) {
                    "device_not_paired" -> "Hub sync nahi hua — phone paired nahi hai."
                    "byok_required" -> "Hub sync nahi hua — AI key chahiye (BYOK)."
                    else -> "Hub sync nahi hua (${r.reason}) — phone par save hai."
                }
                false to note
            }
            null -> false to "Hub sync nahi hua — network ya pairing check karo."
        }
    }
}
