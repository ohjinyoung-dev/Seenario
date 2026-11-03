package com.buzzerbeater.seenario.agent

/**
 * 에이전트에 같이 보내는 최근 대화 기록. 메모리에만 두므로 앱을 다시 켜면 비어 있다.
 * 최대 [maxSize]개(user/agent 메시지를 각각 1개로 센다)를 넘으면 오래된 것부터 버린다.
 */
class ConversationHistory(private val maxSize: Int = DEFAULT_MAX_SIZE) {
    private val items = ArrayDeque<HistoryItem>()

    val size: Int get() = items.size

    fun add(role: String, text: String) {
        items.addLast(HistoryItem(role, text))
        while (items.size > maxSize) items.removeFirst()
    }

    /** 사용자 말과 에이전트 답을 한 번에 넣는다. */
    fun addExchange(userText: String, agentText: String) {
        add(HistoryItem.ROLE_USER, userText)
        add(HistoryItem.ROLE_AGENT, agentText)
    }

    fun snapshot(): List<HistoryItem> = items.toList()

    fun clear() = items.clear()

    companion object {
        const val DEFAULT_MAX_SIZE = 6
    }
}
