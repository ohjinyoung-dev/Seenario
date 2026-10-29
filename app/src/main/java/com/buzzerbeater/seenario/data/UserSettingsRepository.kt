package com.buzzerbeater.seenario.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserSettings(
    @SerialName("user_id") val userId: String,
    @SerialName("sound_mode") val soundMode: String = "off",
    @SerialName("speech_rate") val speechRate: Float? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

class UserSettingsRepository(private val supabase: SupabaseClient) {

    /** 저장된 세션이 있으면 재사용하고, 없으면 익명 로그인한다. 사용자 ID를 반환. */
    suspend fun ensureSignedIn(): String {
        val auth = supabase.auth
        auth.awaitInitialization()
        if (auth.currentSessionOrNull() == null) {
            auth.signInAnonymously()
        }
        return requireNotNull(auth.currentUserOrNull()?.id) { "Supabase user is missing after sign-in" }
    }

    /** 내 user_settings 행을 가져오고, 없으면 DB 기본값으로 새로 만든다. */
    suspend fun getOrCreate(userId: String): UserSettings {
        val table = supabase.from(TABLE)
        table.select { filter { eq("user_id", userId) } }
            .decodeSingleOrNull<UserSettings>()
            ?.let { return it }

        return table.insert(mapOf("user_id" to userId)) { select() }
            .decodeSingle<UserSettings>()
    }

    private companion object {
        const val TABLE = "user_settings"
    }
}
