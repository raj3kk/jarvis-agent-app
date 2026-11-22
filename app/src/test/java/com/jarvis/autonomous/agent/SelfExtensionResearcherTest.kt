package com.jarvis.autonomous.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN §8.4 — researcher budget / strict-JSON / deterministic-check tests (fake LLM + fetcher). */
class SelfExtensionResearcherTest {

    private val signature = SelfExtensionDetector.sha256Hex("convert-pdf-to-text|BUILD|BUILD")

    private fun leadJson(
        name: String = "pdf-tool",
        url: String = "https://github.com/example/pdf-tool",
        licence: String = "MIT",
        cost: String = "\$0",
        lastUpdated: String = "2026-11-01"
    ): String = """[{"name":"$name","url":"$url","licence":"$licence","cost":"$cost","description":"test tool","lastUpdated":"$lastUpdated","steps":[{"action":"OPEN_APP","target":"com.example.app","input":""}]}]"""

    private val mitLicenceText = "MIT License\n\nCopyright (c) 2026 Example\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software."

    @Test
    fun researcherHappyPathWithinBudgets() {
        var llmCalls = 0
        var fetches = 0
        val llm = ResearchLlm {
            llmCalls++
            leadJson()
        }
        val fetcher = ResearchFetcher { url ->
            fetches++
            FetchResult(200, mitLicenceText)
        }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.CANDIDATES_FOUND, report.outcome)
        assertEquals(1, report.llmCalls)
        assertTrue(report.llmCalls <= SelfExtensionBudgets.MAX_LLM_CALLS)
        assertEquals(1, report.fetches)
        assertTrue(report.fetches <= SelfExtensionBudgets.MAX_FETCHES)
        assertTrue(report.durationMs <= SelfExtensionBudgets.RESEARCH_WALL_MS)
        assertEquals(1, report.candidates.size)
        assertEquals("MIT", report.candidates.first().licence)
        assertEquals("\$0", report.candidates.first().costUsd)
    }

    @Test
    fun researcherMalformedLlmJsonIsFailureNeverProseParsing() {
        val llm = ResearchLlm { "Here are some tools you could use: pdf-tool is great, see github…" }
        val fetcher = ResearchFetcher { FetchResult(200, mitLicenceText) }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.NONE_FOUND, report.outcome)
        assertEquals("malformed_llm_json", report.failureReason)
        assertTrue(report.candidates.isEmpty())
    }

    @Test
    fun researcherTruncatedJsonIsFailure() {
        val llm = ResearchLlm { """[{"name":"pdf-tool","url":"https://github.com/example/pdf-tool","licence":"MIT""" }
        val fetcher = ResearchFetcher { FetchResult(200, mitLicenceText) }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.NONE_FOUND, report.outcome)
        assertEquals("malformed_llm_json", report.failureReason)
    }

    @Test
    fun researcherNonAllowlistedLeadDropped() {
        val llm = ResearchLlm {
            """[{"name":"shady","url":"https://evil.example/tool","licence":"MIT","cost":"$0","description":"x","lastUpdated":"2026-11-01","steps":[]}]"""
        }
        var fetches = 0
        val fetcher = ResearchFetcher { fetches++; FetchResult(200, mitLicenceText) }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.NONE_FOUND, report.outcome)
        assertEquals("no_allowlisted_leads", report.failureReason)
        assertEquals(0, fetches)
    }

    @Test
    fun researcherFetchCapIsThreeEvenWithFiveLeads() {
        val leads = (1..5).joinToString(",", "[", "]") { i ->
            """{"name":"tool-$i","url":"https://github.com/example/tool-$i","licence":"MIT","cost":"$0","description":"x","lastUpdated":"2026-11-01","steps":[]}"""
        }
        val llm = ResearchLlm { leads }
        var fetches = 0
        val fetcher = ResearchFetcher { fetches++; FetchResult(200, mitLicenceText) }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.CANDIDATES_FOUND, report.outcome)
        assertEquals(3, fetches)
        assertEquals(5, report.candidates.size)
    }

    @Test
    fun researcherPaidSignalOnFetchedPageFlipsNeedsCard() {
        val llm = ResearchLlm { leadJson() }
        val fetcher = ResearchFetcher {
            FetchResult(200, "Pricing: \$9 per month. Add a credit card to continue. MIT License.")
        }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.CANDIDATES_FOUND, report.outcome)
        assertTrue(report.candidates.first().needsCard)
    }

    @Test
    fun researcherAccountSignalOnFetchedPageFlipsNeedsAccount() {
        val llm = ResearchLlm { leadJson() }
        val fetcher = ResearchFetcher {
            FetchResult(200, "Sign up to continue — create an account or use your API key. MIT License.")
        }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertTrue(report.candidates.first().needsAccount)
    }

    @Test
    fun researcherFetchFailureDoesNotKillCandidate() {
        val llm = ResearchLlm { leadJson() }
        val fetcher = ResearchFetcher { null }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.CANDIDATES_FOUND, report.outcome)
        assertEquals("MIT", report.candidates.first().licence)
    }

    @Test
    fun researcherLlmFailureIsNoLeads() {
        val llm = ResearchLlm { null }
        val fetcher = ResearchFetcher { FetchResult(200, mitLicenceText) }
        val report = runBlocking { SelfExtensionResearcher.research("convert-pdf-to-text", signature, llm, fetcher) }
        assertEquals(ResearchOutcome.NONE_FOUND, report.outcome)
        assertEquals("malformed_llm_json", report.failureReason)
    }

    @Test
    fun researcherLicenceDetection() {
        assertEquals("MIT", SelfExtensionResearcher.detectLicence("MIT License\nPermission is hereby granted"))
        assertEquals("Apache-2.0", SelfExtensionResearcher.detectLicence("Licensed under the Apache License, Version 2.0 (Apache-2.0)"))
        assertEquals("UNKNOWN", SelfExtensionResearcher.detectLicence("Some random text with no licence id"))
    }

    @Test
    fun researcherPaidAndAccountSignals() {
        assertTrue(SelfExtensionResearcher.hasPaidSignal("Pricing starts at \$5 per month"))
        assertTrue(SelfExtensionResearcher.hasPaidSignal("Premium plan, credit card required"))
        assertTrue(SelfExtensionResearcher.hasAccountSignal("Please sign up or provide an api key"))
        assertTrue(!SelfExtensionResearcher.hasPaidSignal("Free and open source, no cost"))
        assertTrue(!SelfExtensionResearcher.hasAccountSignal("Runs fully offline, no login"))
    }

    @Test
    fun researcherAllowlist() {
        assertTrue(SelfExtensionResearcher.isAllowlistedUrl("https://github.com/example/tool"))
        assertTrue(SelfExtensionResearcher.isAllowlistedUrl("https://raw.githubusercontent.com/example/tool/HEAD/LICENSE"))
        assertTrue(SelfExtensionResearcher.isAllowlistedUrl("https://www.apache.org/licenses/"))
        assertTrue(!SelfExtensionResearcher.isAllowlistedUrl("https://evil.example/tool"))
        assertTrue(!SelfExtensionResearcher.isAllowlistedUrl("https://github.com.evil.example/tool"))
        assertTrue(!SelfExtensionResearcher.isAllowlistedUrl("ftp://github.com/example/tool"))
    }

    @Test
    fun researcherPromptContainsCapabilityOnly() {
        val prompt = SelfExtensionResearcher.researchPrompt("convert-pdf-to-text")
        assertTrue(prompt.contains("convert-pdf-to-text"))
        assertTrue(prompt.contains("JSON array"))
    }
}
