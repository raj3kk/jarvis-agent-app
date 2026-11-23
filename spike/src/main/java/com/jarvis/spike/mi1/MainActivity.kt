package com.jarvis.spike.mi1

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * M-I-1 spike UI — deliberately plain (no Compose, no extra deps).
 * One screen: an HF-token field, a RUN SPIKE button, a
 * Download-model-only button, and a scrolling log. Everything runs
 * on a worker thread; log lines are posted back to the UI thread.
 * The HF token is typed by the phone's owner, used once for the
 * Gemma download, and never stored or logged.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var tokenField: EditText
    private lateinit var runButton: Button
    private lateinit var modelButton: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "M-I-1 LiteRT Spike"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = "On-demand liblitertlm_jni.so: download → SHA-verify → System.load → one local Gemma turn.\n" +
                "PASS = sab steps OK + non-blank reply. FAIL = pehla failing step + error."
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        tokenField = EditText(this).apply {
            hint = "HF token (apna — sirf model download ke liye)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        runButton = Button(this).apply {
            text = "RUN SPIKE"
            setOnClickListener { runSpike() }
        }
        modelButton = Button(this).apply {
            text = "Download model only (304 MB)"
            setOnClickListener { downloadModelOnly() }
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { topMargin = 24 }
        }
        logView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
        }
        scroll.addView(logView)
        root.addView(title)
        root.addView(sub)
        root.addView(tokenField)
        root.addView(runButton)
        root.addView(modelButton)
        root.addView(scroll)
        setContentView(root)

        log("Ready. Pehle HF token daalo (model download ke liye), phir RUN SPIKE dabao.")
        log("Model pehle se downloaded hai to step 4 skip hoga.")
    }

    private fun log(line: String) {
        runOnUiThread {
            logView.append(line + "\n")
            logView.post {
                val scroll = logView.parent as? ScrollView
                scroll?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun runSpike() {
        if (running) return
        running = true
        runButton.isEnabled = false
        modelButton.isEnabled = false
        logView.text = ""
        val token = tokenField.text.toString()
        Thread {
            try {
                val verdict = SpikeRunner.run(this, token.ifBlank { null }) { log(it) }
                log("")
                log("====================")
                log(verdict)
                log("====================")
            } finally {
                runOnUiThread {
                    running = false
                    runButton.isEnabled = true
                    modelButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun downloadModelOnly() {
        if (running) return
        running = true
        runButton.isEnabled = false
        modelButton.isEnabled = false
        val token = tokenField.text.toString()
        Thread {
            try {
                val verdict = SpikeRunner.downloadModel(this, token.ifBlank { null }) { log(it) }
                log("")
                log("====================")
                log(verdict)
                log("====================")
            } finally {
                runOnUiThread {
                    running = false
                    runButton.isEnabled = true
                    modelButton.isEnabled = true
                }
            }
        }.start()
    }
}
