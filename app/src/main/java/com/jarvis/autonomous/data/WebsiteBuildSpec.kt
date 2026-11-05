package com.jarvis.autonomous.data

/**
 * 1.4.20 (item 4): THE standing website-build spec — one source of
 * truth. The builder prompt ([BUILDER_PROMPT_FRAGMENT]) is embedded
 * verbatim by LlmClient.buildSystemPrompt step 5; there is NO second
 * copy of the spec in the prompt string, because drift between two
 * copies is how the old 3-file shape got frozen. The validator
 * ([ProjectStore.validateProject]) enforces the machine-checkable
 * rules (4, 5, 6, 8); the rest are prompt-level requirements the
 * model must follow on every website build, automatically — the
 * user never repeats these details.
 *
 * HONEST PLATFORM STATEMENT (frozen): a true on-phone backend
 * runtime is NOT feasible in this bundle (no Node/Python/PHP
 * runtime on the phone; the preview server is a static file
 * server). The design previews the full-stack CONTRACT: the
 * frontend is wired to local data mirroring the backend, and the
 * backend ships as real, previewable files for deployment
 * elsewhere. Never claim a running server on the phone.
 */
object WebsiteBuildSpec {

    /** The 8-item checklist, as user-facing text. */
    const val CHECKLIST_TEXT: String = """
        1. Frontend pages — multi-page via the existing WRITE_PROJECT_FILE + index-rewrite pattern, every page linked (see 4).
        2. Backend APIs — backend/api.md (endpoint list: method, path, request/response shape, plain language) + backend/server.js (framework-free Node-style handler code implementing those endpoints against the schema — generated, documented as NOT executed on the phone).
        3. Database schema + seed — backend/schema.sql (tables/columns) + backend/seed.json (real sample rows) + data/<collection>.json (verbatim copy of the seed, served by the preview server as the preview-time data source).
        4. Navigation — every page links to every top-level page (shared nav snippet, same markup on all pages); validation fails on any page missing nav or linking a missing page.
        5. Responsive layout — viewport tag on every page + CSS that works at phone width (no fixed pixel widths that overflow; template max-width:720px pattern extended, plus at least one narrow-screen rule).
        6. Error handling — every fetch() has try/catch (or .catch) with a visible on-page fallback message ("Data load nahi hua"), every form/button has a visible success/failure message; no silent blank section.
        7. Working localhost preview — preview serves ALL of the above; frontend JS fetches ONLY same-origin data/*.json (the preview server already serves application/json) — never a remote backend — so the previewed site behaves with real data; backend/ files are previewable as text via links from an included backend/README.md (or opened as files in the editor), and that README states in one line that the backend code is for deployment elsewhere, not run on the phone.
        8. Referential integrity (carried over, extended) — every href/src, CSS url(), AND JS fetch() target must resolve to an existing non-empty project file.
    """

    /**
     * The exact prompt fragment the builder must follow. Embedded
     * verbatim as system-prompt step 5 (see LlmClient). It contains
     * no dollar signs, so Kotlin string interpolation cannot
     * mangle it.
     */
    const val BUILDER_PROMPT_FRAGMENT: String = """
        CODE BUILDER — WEBSITE BUILD (multi-step, never one-and-done): If the user asks you to make a website/app/page — Hinglish or English, e.g. "ek website banao", "website bana do", "meri portfolio/dukaan/restaurant ki site banao", "ek calculator/todo page banao", "is ki website banao" — do NOT open a browser. Build a FULL website automatically, with NO extra details needed from the user: the checklist below applies to EVERY website build request by itself. Build it in steps, one action per step, with the SAME projectName (short, no spaces, same spelling and case) on every step, in this order:
        Step 1: action="CREATE_PROJECT", projectName=<name>, fileContent=the COMPLETE index.html (full, working, mobile-friendly, creamy theme). index.html MUST contain exactly `<link rel="stylesheet" href="style.css">` and `<script src="script.js"></script>` and NO other local file reference unless you also write that file. Every page MUST have a viewport meta tag. Every page MUST carry the SAME shared nav snippet (a nav element linking to every top-level page of the site). Set isCompleted=false on this step — the build is NOT done yet.
        Step 2: action="WRITE_PROJECT_FILE", same projectName, fileName="style.css", fileContent=the COMPLETE custom CSS for this site (all classes the HTML uses; page width capped with a max-width 720px pattern; NO fixed pixel widths that overflow a phone screen; at least one narrow-screen media-query rule). Set isCompleted=false.
        Step 3: action="WRITE_PROJECT_FILE", same projectName, fileName="script.js", fileContent=the COMPLETE custom JS for this site (every id the HTML references must exist in the JS, every JS getElementById target must exist in the HTML). Frontend JS fetches ONLY same-origin data files (data/*.json) — never a remote backend. Every fetch() MUST have try/catch or .catch with a VISIBLE on-page fallback message ("Data load nahi hua") — never a silent blank section. Every form/button MUST show a visible success/failure message. Set isCompleted=false.
        Step 4 (extra pages, only if the site needs more than one page): for each extra page, action="WRITE_PROJECT_FILE" with the full page (viewport tag + the SAME shared nav snippet, updated so every page links to every top-level page), then rewrite index.html with its nav updated the same way. Set isCompleted=false on each of these steps.
        Step 5: action="WRITE_PROJECT_FILE", same projectName, fileName="backend/api.md", fileContent=the endpoint list for this site in plain language (method, path, request shape, response shape for every endpoint). Set isCompleted=false.
        Step 6: action="WRITE_PROJECT_FILE", same projectName, fileName="backend/server.js", fileContent=framework-free Node-style handler code implementing exactly the api.md endpoints against the schema below. This code is GENERATED for deployment elsewhere — it is NOT executed on the phone, and you must never claim it runs on the phone. Set isCompleted=false.
        Step 7: action="WRITE_PROJECT_FILE", same projectName, fileName="backend/schema.sql", fileContent=the database schema (tables and columns) for this site. Set isCompleted=false.
        Step 8: action="WRITE_PROJECT_FILE", same projectName, fileName="backend/seed.json", fileContent=real sample rows matching the schema (not placeholders). Set isCompleted=false.
        Step 9: action="WRITE_PROJECT_FILE", same projectName, fileName="data/items.json" (or a better collection name for the site), fileContent=a VERBATIM copy of the seed data the frontend actually fetches. The localhost preview serves this file as application/json, so the previewed site behaves with real data. Set isCompleted=false.
        Step 10: action="WRITE_PROJECT_FILE", same projectName, fileName="backend/README.md", fileContent=a short readme that links to api.md, server.js, schema.sql and seed.json as previewable text, and states in one line that the backend code is for deployment elsewhere, not run on the phone. Set isCompleted=true, and your reply MUST be the build summary: files created (frontend pages, style.css, script.js, backend files, data file), the project name, and "Projects tab me Preview dabao, ya Browser me kholo".
        HONEST LIMIT, stated in the build summary only if the user asks about the backend: the phone preview runs the frontend with local data only; the backend code ships as real files for deployment elsewhere.
        EXCEPTION — single-file build ONLY when the user explicitly asks for one file ("sirf ek file", "single file page"): then do ONE step, action="CREATE_PROJECT" with a fully self-contained index.html (inline <style> and <script> inside the HTML, NO href="style.css" and NO src="script.js"), isCompleted=true, and say it is a single-file build. The checklist then reduces to items 5 (viewport + responsive), 6 (fetch fallback + visible messages) and 7 (working localhost preview) for that file. Never mix the two modes: a multi-file HTML must never reference files you did not write, and a single-file HTML must never reference external files.
    """
}
