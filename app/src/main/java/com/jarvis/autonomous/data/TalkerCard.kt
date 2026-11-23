package com.jarvis.autonomous.data

/**
 * R3.10: a Talker card — a visible card on the Command tab that
 * mirrors what the Talker just said or is waiting on.
 *
 * The Talker (engine) publishes; the Executor never writes UI
 * strings directly. One card at a time (last publish wins):
 *  - PROGRESS: a task is running (goal + current step).
 *  - APPROVAL: the Talker asked "ye link kholun?" — the card is
 *    the tap answer (Approve / Reject), the U8 phone-side record.
 *  - DOC: a recalled document was used in this turn.
 */
enum class TalkerCardKind { PROGRESS, APPROVAL, DOC }

data class TalkerCard(
    val kind: TalkerCardKind,
    val title: String,
    val body: String,
    val sourceLabel: String = "",
    /** Approval only: the URL the card answers. */
    val approvalUrl: String = ""
)
