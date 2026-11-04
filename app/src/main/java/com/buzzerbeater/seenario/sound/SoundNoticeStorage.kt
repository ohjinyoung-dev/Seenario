package com.buzzerbeater.seenario.sound

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * 처음 소리를 틀 때 하는 안전 안내("분위기를 위한 효과음이에요…")를 이미 했는지 휴대폰 안에 기록한다.
 * "지난 모드"와 같은 DataStore(`sound_mode`)에 키만 따로 둔다.
 */
class SoundNoticeStorage(context: Context) {
    private val dataStore = context.applicationContext.soundModeDataStore

    suspend fun isDone(): Boolean = try {
        dataStore.data.first()[KEY_DONE] == true
    } catch (e: IOException) {
        Log.e(TAG, "안전 안내 기록 읽기 실패", e)
        false
    }

    suspend fun markDone() {
        try {
            dataStore.edit { it[KEY_DONE] = true }
        } catch (e: IOException) {
            Log.e(TAG, "안전 안내 기록 저장 실패", e)
        }
    }

    private companion object {
        const val TAG = "SEENARIO"
        val KEY_DONE = booleanPreferencesKey("safety_notice_done")
    }
}
