package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2.1 (1.17.0-PRO) — unit tests for TrainedIngestion's pure parts:
 * HTML stripping (link ingestion) and Latin + Devanagari OCR merging.
 * The network / ML Kit / SpeechRecognizer paths need a device and are
 * covered by the TR-T phone tests, not here.
 */
class TrainedIngestionTest {

    @Test
    fun stripHtmlRemovesTagsScriptsAndStyles() {
        val html = """
            <html><head><style>.x{color:red}</style>
            <script>var a = 1;</script><title>Test Page</title></head>
            <body><h1>Hello&nbsp;World</h1><p>Doosra &amp; line</p></body></html>
        """.trimIndent()
        val text = TrainedIngestion.stripHtml(html)
        assertTrue(text.contains("Hello World"))
        assertTrue(text.contains("Doosra & line"))
        assertTrue(!text.contains("<"))
        assertTrue(!text.contains("var a"))
        assertTrue(!text.contains("color:red"))
    }

    @Test
    fun mergeOcrTextsDedupesLinesAcrossScripts() {
        val latin = "Hello world\nNamaste\nLine three"
        val devanagari = "नमस्ते\nHello world\nLine four"
        val merged = TrainedIngestion.mergeOcrTexts(latin, devanagari)
        val lines = merged.lines()
        assertEquals(5, lines.size)
        assertEquals("Hello world", lines[0])
        assertEquals("Namaste", lines[1])
        assertEquals("Line three", lines[2])
        assertEquals("नमस्ते", lines[3])
        assertEquals("Line four", lines[4])
    }

    @Test
    fun mergeOcrTextsHandlesEmptyInputs() {
        assertEquals("", TrainedIngestion.mergeOcrTexts("", ""))
        assertEquals("only latin", TrainedIngestion.mergeOcrTexts("only latin", ""))
        assertEquals("केवल देवनागरी", TrainedIngestion.mergeOcrTexts("", "केवल देवनागरी"))
    }
}
