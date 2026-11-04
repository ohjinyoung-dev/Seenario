package com.buzzerbeater.seenario.agent

import com.buzzerbeater.seenario.BuildConfig
import io.github.jan.supabase.SupabaseClient

/** 사용자 입력을 에이전트로 보내고 응답을 받는다. 시간 제한은 부르는 쪽에서 건다. */
interface AgentClient {
    suspend fun send(request: AgentRequest): AgentResponse

    companion object {
        /** `BuildConfig.USE_FAKE_AGENT`가 true면 가짜 응답, false면 Edge Function `seenario-agent`. */
        fun create(supabase: SupabaseClient): AgentClient =
            if (BuildConfig.USE_FAKE_AGENT) FakeAgentClient(supabase) else SupabaseAgentClient(supabase)
    }
}
