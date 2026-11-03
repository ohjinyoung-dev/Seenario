package com.buzzerbeater.seenario.voice

import com.buzzerbeater.seenario.agent.SoundMode

/**
 * 음성 모듈이 다른 모듈(에이전트, 카메라 등)에 명령을 전달하는 통로.
 * 항상 메인 스레드에서 호출된다.
 */
interface VoiceCommandListener {
    /**
     * 사용자가 "묘사해줘"라고 말했거나, 음성 인식을 못 쓰는 기기에서 화면을 탭했을 때 호출된다.
     * [heard]는 인식한 문장(없으면 null).
     */
    fun onDescribeRequested(heard: String? = null)

    /** 사용자가 "다시 들려줘"라고 말했을 때 호출된다. */
    fun onRepeatRequested() {}

    /** 휴대폰 명령이 아닌 말. 에이전트에 대화로 보낸다. */
    fun onSpeechInput(text: String) {}

    /** "환경음 모드" / "배경음 모드" / "소리 꺼". */
    fun onSoundModeRequested(mode: SoundMode) {}

    /** "소리 켜": 마지막으로 쓴 모드(없으면 환경음)로 켠다. */
    fun onSoundOnRequested() {}

    /** "설정 열어줘". */
    fun onOpenSettingsRequested() {}

    /** "처음부터": 대화 기록을 지운다. */
    fun onResetRequested() {}
}
