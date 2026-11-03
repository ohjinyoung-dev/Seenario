package com.buzzerbeater.seenario.sound

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.buzzerbeater.seenario.agent.SoundMode
import kotlinx.coroutines.flow.first
import java.io.IOException

/** "지난 모드"(사용자가 마지막으로 고른 소리 모드, off 포함)를 휴대폰 안에 저장하는 곳. */
interface LastSoundModeStorage {
    /** 저장된 값이 없으면 null. */
    suspend fun load(): SoundMode?

    suspend fun save(mode: SoundMode)
}

private val Context.soundModeDataStore: DataStore<Preferences> by preferencesDataStore(name = "sound_mode")

/** DataStore(Preferences)에 저장한다. 읽기/쓰기 실패는 로그만 남기고 넘어간다. */
class DataStoreLastSoundModeStorage(context: Context) : LastSoundModeStorage {
    private val dataStore = context.applicationContext.soundModeDataStore

    override suspend fun load(): SoundMode? = try {
        dataStore.data.first()[KEY_LAST_MODE]?.let(SoundMode::fromWire)
    } catch (e: IOException) {
        Log.e(TAG, "지난 모드 읽기 실패", e)
        null
    }

    override suspend fun save(mode: SoundMode) {
        try {
            dataStore.edit { it[KEY_LAST_MODE] = mode.wire }
        } catch (e: IOException) {
            Log.e(TAG, "지난 모드 저장 실패", e)
        }
    }

    private companion object {
        const val TAG = "SEENARIO"
        val KEY_LAST_MODE = stringPreferencesKey("last_sound_mode")
    }
}
