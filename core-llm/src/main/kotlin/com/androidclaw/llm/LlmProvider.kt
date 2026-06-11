package com.androidclaw.llm

import kotlinx.coroutines.flow.Flow

/**
 * Backend-agnostic LLM interface. Online providers (Anthropic, OpenAI, Gemini)
 * and the future on-device provider all implement this.
 */
interface LlmProvider {
    val supportsTools: Boolean

    /**
     * Stream one model response. The flow completes after [LlmEvent.Completed]
     * and throws [LlmException] on transport or API errors. Cancelling the
     * collector aborts the underlying request.
     */
    fun stream(request: LlmRequest): Flow<LlmEvent>
}
