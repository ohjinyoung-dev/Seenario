package com.buzzerbeater.seenario.voice

/** 음성으로 인식한 문장을 분류한 결과. UNKNOWN은 휴대폰 명령이 아니라서 에이전트로 보낸다. */
enum class VoiceCommand {
    DESCRIBE,
    SOUND_ON,
    SOUND_OFF,
    /** 환경음 모드(ambient)로 바꾸기. */
    SOUND_AMBIENT,
    /** 배경음 모드(music)로 바꾸기. */
    SOUND_MUSIC,
    /** 앱 설정(시스템 앱 정보) 화면 열기. */
    OPEN_SETTINGS,
    /** 대화 기록 초기화. */
    RESET,
    SAVE,
    REPEAT,
    UNKNOWN,
}
