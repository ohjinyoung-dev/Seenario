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
    /** 감성 모드(소리 없이 3~4문장 감성 묘사)로 바꾸기. 묘사는 하지 않는다. */
    EMOTIONAL_MODE,
    /** "간단하게 묘사해줘" 등. 기본 모드(1~2문장)가 아니면 기본 모드로 바꾸고 바로 묘사한다. */
    BRIEF_DESCRIBE,
    /** "자세히 묘사해줘" 등. 기본 모드면 감성 모드로 바꾸고 바로 자세히 묘사한다. */
    DETAILED_DESCRIBE,
    /** 앱 설정(시스템 앱 정보) 화면 열기. */
    OPEN_SETTINGS,
    /** 대화 기록 초기화. */
    RESET,
    SAVE,
    REPEAT,
    /** 절전 모드 켜기(미리보기 끄기, 사진은 요청할 때만). */
    POWER_SAVE_ON,
    /** 절전 모드 끄기. */
    POWER_SAVE_OFF,
    UNKNOWN,
}
