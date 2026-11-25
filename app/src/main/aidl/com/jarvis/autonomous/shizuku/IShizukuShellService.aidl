package com.jarvis.autonomous.shizuku;

// 1.25.0 (Shizuku): the privileged shell runner. This interface is
// implemented by ShizukuShellService, which the Shizuku server loads
// directly from this APK (via classLoader) and runs in a separate
// process as the shell identity (UID 2000 on a non-root phone, UID 0
// on a rooted phone). The app process talks to it over Binder.
//
// Transaction ids are explicit (AIDL ids are transactionCode -
// FIRST_CALL_TRANSACTION(1); when one method has an explicit id, all
// of them must — this mirrors the Shizuku demo's IUserService.aidl).
interface IShizukuShellService {
    // Reserved by the Shizuku server: transaction 16777115
    // == 16777114 + FIRST_CALL_TRANSACTION. The server sends this
    // (oneway) when the service is removed; the implementation must
    // terminate the process (System.exit).
    void destroy() = 16777114;

    // User-defined exit: same as destroy().
    void exit() = 1;

    // Runs `sh -c <command>` in the shell-uid process. Returns a
    // Bundle with "code" (int: the process exit code; 124 = timeout,
    // 127 = spawn failure), "out" (String: stdout), "err" (String:
    // stderr). The call blocks until the command finishes or
    // timeoutMs elapses; the caller serialises calls with a Mutex
    // (the service is single-flight).
    Bundle exec(String command, int timeoutMs) = 2;
}
