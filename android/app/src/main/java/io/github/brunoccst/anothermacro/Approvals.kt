package io.github.brunoccst.anothermacro

/**
 * Deferred Allow/Deny answers. Claude's risky taps ask the user on the screen first (AskOverlay).
 * When nobody answers in time (the user is away from the phone), the tap is not just denied: the
 * question moves to a notification with Allow and Deny, and the tool call ends saying so. Claude
 * then tells the user in the chat, and calls the same tap again later. If the user allowed it
 * meanwhile, that one tap goes through without asking again; if they denied it, it is refused.
 *
 * The answer only ever comes from the user's own tap on the phone (the overlay or the
 * notification), never from Claude. One question at a time; it expires after [TTL_MS].
 */
object Approvals {
    const val TTL_MS = 15 * 60_000L

    data class Pending(val what: String, val question: String, val at: Long, val answer: Boolean? = null)

    @Volatile var pending: Pending? = null
        private set

    /**
     * The user's earlier answer to a question about [what], used up by this call: true = allowed,
     * false = denied, null = no answer yet (ask again).
     */
    @Synchronized fun take(what: String, now: Long = System.currentTimeMillis()): Boolean? {
        val p = pending ?: return null
        if (now - p.at > TTL_MS) { pending = null; return null }
        if (p.what != what || p.answer == null) return null
        pending = null
        return p.answer
    }

    /** Nobody answered on the screen: ask in a notification instead. */
    @Synchronized fun defer(app: android.content.Context?, what: String, question: String, now: Long = System.currentTimeMillis()) {
        pending = Pending(what, question, now)
        if (app == null) return
        PhoneEvents.init(app)
        PhoneEvents.add("approval", "Claude asks: $question", "No answer on the screen; the question is now in a notification.", ok = false, now = now)
        Alerts.approval(app, question)
        MacroLog.i("Claude's question moved to a notification: $question")
    }

    /** The user pressed Allow or Deny on the notification. */
    @Synchronized fun answer(app: android.content.Context?, allow: Boolean, now: Long = System.currentTimeMillis()) {
        val p = pending
        if (p == null || now - p.at > TTL_MS) {
            pending = null
            if (app != null) Alerts.cancelApproval(app)
            return
        }
        pending = p.copy(answer = allow)
        if (app == null) return
        Alerts.cancelApproval(app)
        PhoneEvents.init(app)
        PhoneEvents.add("approval", "You ${if (allow) "allowed" else "denied"}: ${p.question}",
            if (allow) "Claude's next tap on '${p.what}' goes through without asking again." else "Claude's tap on '${p.what}' will be refused.", ok = true, now = now)
        MacroLog.i("you ${if (allow) "allowed" else "denied"} Claude's tap on '${p.what}'")
    }

    /** Tests: forget everything. */
    @Synchronized fun clear() { pending = null }
}
