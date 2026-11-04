package com.buzzerbeater.seenario.power

/**
 * 절전 모드 규칙(안드로이드에 의존하지 않아 단위 테스트할 수 있다).
 *
 * 절전 모드에서는 카메라 미리보기를 끄고(검은 화면, 밝기 최소), 사진은 요청할 때만 만든다.
 * - 사용자가 "절전 모드 켜/꺼"라고 말하면 그 선택을 따른다.
 * - 말한 적이 없으면 휴대폰 절전 모드를 따른다.
 * - 휴대폰 절전 모드가 바뀌면 사용자 선택을 지우고 다시 휴대폰을 따른다.
 * - 사용자 선택은 저장하지 않는다. 앱을 다시 켜면 일반 모드(휴대폰이 절전 중이면 절전 모드)로 시작한다.
 */
object PowerSavePolicy {

    /** 실제로 절전 모드인지. [userChoice]가 null이면 휴대폰 절전 상태를 따른다. */
    fun isOn(userChoice: Boolean?, systemPowerSave: Boolean): Boolean = userChoice ?: systemPowerSave

    /** 휴대폰 절전 상태가 바뀔 때 지금 상태에서 새 상태로 바뀌는지와 안내 문구. 바뀌지 않으면 null. */
    fun systemChangeNotice(wasOn: Boolean, systemPowerSave: Boolean): String? = when {
        wasOn == systemPowerSave -> null
        systemPowerSave -> MSG_SYSTEM_ON
        else -> MSG_SYSTEM_OFF
    }

    /** 사용자가 말로 켜거나 껐을 때 안내 문구. */
    fun userNotice(wasOn: Boolean, on: Boolean): String = when {
        on && wasOn -> MSG_ALREADY_ON
        !on && !wasOn -> MSG_ALREADY_OFF
        on -> MSG_ON
        else -> MSG_OFF
    }

    /** 시작할 때 휴대폰이 절전 중이면 시작 안내 끝에 한 줄을 붙인다. */
    fun startupGreeting(greeting: String, systemPowerSave: Boolean): String =
        if (systemPowerSave) "$greeting $MSG_STARTUP" else greeting

    const val MSG_ON = "절전 모드를 켰어요. 화면을 끄고 배터리를 아낄게요."
    const val MSG_OFF = "절전 모드를 껐어요. 다시 화면에 카메라를 보여 드릴게요."
    const val MSG_ALREADY_ON = "이미 절전 모드예요."
    const val MSG_ALREADY_OFF = "지금은 절전 모드가 아니에요."
    const val MSG_SYSTEM_ON = "휴대폰 절전 모드라서 씨나리오도 절전 모드로 바꿨어요."
    const val MSG_SYSTEM_OFF = "휴대폰 절전 모드가 꺼져서 씨나리오도 절전 모드를 껐어요."
    const val MSG_STARTUP = "휴대폰이 절전 모드라서 화면을 끄고 절전 모드로 시작해요. '절전 모드 꺼'라고 말하면 꺼져요."
}
