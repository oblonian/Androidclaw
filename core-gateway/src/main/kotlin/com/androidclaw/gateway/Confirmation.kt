package com.androidclaw.gateway

import com.androidclaw.llm.ToolCall

/**
 * A user's verdict on a single proposed device action — the "game controller"
 * for stepping through what the agent wants to do (SPEC §6/§12).
 */
sealed interface ConfirmDecision {
    /** Forward — run this action. */
    data object Proceed : ConfirmDecision

    /** Back — don't run it; tell the agent to step back and reconsider. */
    data object Back : ConfirmDecision

    /** Chat — don't run it; hand the agent the user's note and let it re-plan. */
    data class Chat(val note: String) : ConfirmDecision

    /** Stop — abort the whole turn. */
    data object Cancel : ConfirmDecision
}

/**
 * Asked before a CONFIRM-tier tool runs. Suspends until the user decides, so
 * the UI can present a step-by-step card. The default just proceeds, preserving
 * non-interactive behaviour.
 */
fun interface Confirmer {
    suspend fun confirm(call: ToolCall): ConfirmDecision

    companion object {
        val AutoApprove = Confirmer { ConfirmDecision.Proceed }
    }
}
