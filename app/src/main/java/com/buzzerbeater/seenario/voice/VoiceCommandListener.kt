package com.buzzerbeater.seenario.voice

/**
 * 음성 모듈이 다른 모듈(카메라 등)에 명령을 전달하는 통로.
 * 카메라 모듈은 이 인터페이스를 구현해 사진 캡처를 시작하면 된다.
 * 항상 메인 스레드에서 호출된다.
 */
fun interface VoiceCommandListener {
    /** 사용자가 "묘사해줘"라고 말했거나 화면을 탭했을 때 호출된다. */
    fun onDescribeRequested()
}
