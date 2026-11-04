package com.buzzerbeater.seenario.sound

import android.util.Log
import com.buzzerbeater.seenario.BuildConfig

/** 소리 재생기 로그(디버그 빌드만). signed URL은 토큰이 들어 있으므로 절대 남기지 않는다. */
object SoundLog {
    const val TAG = "SEENARIO_SOUND"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
