package com.jarvis.autonomous.agent

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * R1 (1.16.0-PRO) — in-app terminal backend (parity §1.2 A).
 *
 * Phone = Muse's VM (user-locked): the terminal is the phone-side
 * equivalent of the VM shell, split in two — this file is the
 * LIMITED on-phone shell, and the Main Executor shell (GitHub
 * Actions as computer) is the real full shell (parity §1.2 B).
 *
 * Backend: ProcessBuilder("sh") as the app UID — NO root, NO
 * compilers, NO git, NO package manager. The sandbox itself is the
 * confinement: the shell can only touch what the app UID can touch
 * (app storage; other apps' private files are unreadable). On top
 * of that, a small denylist blocks the obviously destructive
 * shapes (`rm -rf /`, `su`, `reboot`, raw-disk writes). This is a
 * safety net, not a sandbox proof — the app UID is the sandbox.
 *
 * Working directory is ALWAYS the app filesDir (passed in) — the
 * shell starts there and `cd` out is refused by the denylist when
 * it targets an absolute path outside app storage. Output is
 * capped (last 200 KB kept) so a runaway command cannot OOM the
 * app. History is in-memory only (session scope, never persisted).
 *
 * Graceful when `sh` is missing: start() returns the plain-words
 * error instead of throwing — the UI shows it, nothing crashes.
 */
class TerminalSession(private val workingDir: File) {

    /** Pure denylist check (JVM unit test seam — no process). */
    companion object {
        private val BLOCKED = listOf(
            Regex("""(?i)\brm\s+.*-rf\s+/(\s|$)"""),          // rm -rf /
            Regex("""(?i)\brm\s+.*--no-preserve-root\b"""),
            Regex("""(?i)(^|[;&|]\s*)su\b"""),                // su
            Regex("""(?i)(^|[;&|]\s*)sudo\b"""),
            Regex("""(?i)\breboot\b"""),
            Regex("""(?i)\bshutdown\b"""),
            Regex("""(?i)\bmkfs\b"""),
            Regex("""(?i)\bdd\s+.*of=/dev/"""),               // raw disk write
            Regex("""(?i)\bchmod\s+.*\s+/(\s|$)"""),          // chmod on /
            Regex("""(?i)\bcd\s+/"""),                        // cd to an absolute path
            Regex("""(?i)\bcd\s+\.\.""")                      // cd out of app storage
        )

        fun blockedReason(command: String): String? {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) return null
            for (pattern in BLOCKED) {
                if (pattern.containsMatchIn(trimmed)) {
                    return "Ye command phone par allowed nahi hai — app storage ke andar hi kaam hota hai, root/system nahi."
                }
            }
            return null
        }
    }

    private var process: Process? = null
    private var readerThread: Thread? = null
    private val outputLock = Any()
    private val output = StringBuilder()
    private var outputChars = 0

    /** Last 200 KB of output are kept; older output is dropped. */
    private val maxOutputChars = 200_000

    /** In-memory command history (session only, never persisted). */
    val history = ArrayList<String>()
        private set

    var onOutput: ((String) -> Unit)? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * Start `sh` in [workingDir]. Returns null on success, or a
     * plain-words error when `sh` is missing / cannot start.
     */
    fun start(): String? {
        if (isRunning) return null
        return try {
            val pb = ProcessBuilder("sh")
                .directory(workingDir)
                .redirectErrorStream(true)
            process = pb.start()
            readerThread = Thread({
                val buffer = ByteArray(4096)
                try {
                    val stream = process?.inputStream ?: return@Thread
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        val chunk = String(buffer, 0, read, Charsets.UTF_8)
                        synchronized(outputLock) {
                            output.append(chunk)
                            outputChars += chunk.length
                            if (outputChars > maxOutputChars) {
                                val drop = outputChars - maxOutputChars
                                output.delete(0, drop.coerceAtMost(output.length))
                                outputChars = output.length
                            }
                        }
                        onOutput?.invoke(chunk)
                    }
                } catch (e: Exception) {
                    // Process died / stream closed — the reader just ends.
                } finally {
                    onOutput?.invoke("\r\n[shell band ho gaya]\r\n")
                }
            }, "jarvis-terminal-reader").apply {
                isDaemon = true
                start()
            }
            null
        } catch (e: Exception) {
            process = null
            "Phone par shell (sh) nahi mila — is phone par terminal nahi chalega. (${e.javaClass.simpleName})"
        }
    }

    /**
     * Send one line to the shell. Returns a plain-words error when
     * the command is blocked or the shell is not running; null when
     * the line was sent.
     */
    fun send(line: String): String? {
        val blocked = blockedReason(line)
        if (blocked != null) return blocked
        val proc = process
        if (proc == null || !proc.isAlive) {
            return "Shell chal nahi raha — pehle terminal dobara kholo."
        }
        return try {
            history.add(line)
            proc.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
            null
        } catch (e: Exception) {
            "Shell ko likh nahi paya (${e.javaClass.simpleName}) — terminal dobara kholo."
        }
    }

    /** Interrupt the foreground command (Ctrl-C). */
    fun interrupt() {
        try {
            process?.outputStream?.write(3) // ETX
            process?.outputStream?.flush()
        } catch (e: Exception) {
            // Best-effort; a dead shell needs nothing.
        }
    }

    fun stop() {
        readerThread?.interrupt()
        readerThread = null
        try {
            process?.destroy()
            runCatching {
                process?.waitFor(2, TimeUnit.SECONDS)
            }
            if (process?.isAlive == true) process?.destroyForcibly()
        } catch (e: Exception) {
            // Best-effort.
        }
        process = null
    }

    /** Drain helper for tests / non-UI callers (not used by the UI). */
    fun drainOutput(): String = synchronized(outputLock) { output.toString() }
}
