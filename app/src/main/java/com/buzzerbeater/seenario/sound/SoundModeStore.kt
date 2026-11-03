package com.buzzerbeater.seenario.sound

import com.buzzerbeater.seenario.agent.SoundMode
import com.buzzerbeater.seenario.data.UserSettingsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from

/** 사용자가 고른 소리 모드를 `user_settings.sound_mode`에 저장한다. */
class SoundModeStore(
    private val supabase: SupabaseClient,
    private val userRepository: UserSettingsRepository = UserSettingsRepository(supabase),
) {
    suspend fun save(mode: SoundMode) {
        val userId = userRepository.ensureSignedIn()
        supabase.from(TABLE).update(mapOf("sound_mode" to mode.wire)) {
            filter { eq("user_id", userId) }
        }
    }

    private companion object {
        const val TABLE = "user_settings"
    }
}
