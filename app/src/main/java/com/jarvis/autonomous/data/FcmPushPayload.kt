package com.jarvis.autonomous.data

/**
 * FCM push payload (1.12.0, DESIGN expansion/fcm/DESIGN.md).
 *
 * Pure Kotlin — no Android/Firebase types, so it is unit-testable on
 * the JVM. The hub sends data-only messages {title, body, kind}; an
 * empty body means "no notification" (logged only, never shown).
 */
data class FcmPushPayload(val title: String, val body: String, val kind: String) {
    companion object {
        fun fromData(data: Map<String, String>): FcmPushPayload? {
            val body = data["body"]?.trim().orEmpty()
            if (body.isEmpty()) return null
            return FcmPushPayload(
                title = data["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Jarvis",
                body = body,
                kind = data["kind"]?.trim()?.takeIf { it.isNotEmpty() } ?: "test"
            )
        }
    }
}
