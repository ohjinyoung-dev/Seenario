package com.buzzerbeater.seenario.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFrameRateTest {

    @Test
    fun `S21+ 목록에서는 고정 15를 고른다`() {
        val s21 = listOf(15 to 15, 15 to 20, 20 to 20, 24 to 24, 8 to 30, 10 to 30, 15 to 30, 30 to 30)
        assertEquals(15 to 15, CameraFrameRate.pick(s21))
    }

    @Test
    fun `고정 15가 없으면 10~15 사이 고정 중 가장 큰 것`() {
        assertEquals(12 to 12, CameraFrameRate.pick(listOf(10 to 10, 12 to 12, 30 to 30)))
    }

    @Test
    fun `10보다 낮은 고정 속도는 고르지 않는다`() {
        assertEquals(15 to 20, CameraFrameRate.pick(listOf(5 to 5, 15 to 20, 30 to 30)))
    }

    @Test
    fun `고정이 없으면 최댓값이 가장 작은 범위, 같으면 최솟값이 큰 것`() {
        assertEquals(15 to 24, CameraFrameRate.pick(listOf(7 to 30, 8 to 24, 15 to 24)))
    }

    @Test
    fun `30만 지원하면 30`() {
        assertEquals(30 to 30, CameraFrameRate.pick(listOf(30 to 30)))
    }

    @Test
    fun `목록이 비면 null`() {
        assertNull(CameraFrameRate.pick(emptyList()))
    }
}
