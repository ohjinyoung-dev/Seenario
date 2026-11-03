package com.buzzerbeater.seenario.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {

    @Test
    fun keepsOnlyLatestSix() {
        val history = ConversationHistory()
        repeat(4) { i -> history.addExchange("질문$i", "답변$i") } // 메시지 8개
        val items = history.snapshot()
        assertEquals(6, items.size)
        assertEquals(
            listOf("질문1", "답변1", "질문2", "답변2", "질문3", "답변3"),
            items.map { it.text },
        )
        assertEquals(HistoryItem.ROLE_USER, items.first().role)
        assertEquals(HistoryItem.ROLE_AGENT, items.last().role)
    }

    @Test
    fun underLimit_keepsAll() {
        val history = ConversationHistory()
        history.addExchange("안녕", "반가워요")
        assertEquals(2, history.size)
    }

    @Test
    fun clear_emptiesHistory() {
        val history = ConversationHistory()
        history.addExchange("안녕", "반가워요")
        history.clear()
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun snapshot_isCopy() {
        val history = ConversationHistory()
        history.add(HistoryItem.ROLE_USER, "하나")
        val before = history.snapshot()
        history.add(HistoryItem.ROLE_AGENT, "둘")
        assertEquals(1, before.size)
    }
}
