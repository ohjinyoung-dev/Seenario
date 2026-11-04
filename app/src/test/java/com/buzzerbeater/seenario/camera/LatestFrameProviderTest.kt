package com.buzzerbeater.seenario.camera

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LatestFrameProviderTest {

    private fun frame(timestampMs: Long) = CameraFrame(ByteArray(1), width = 1, height = 1, timestampMs = timestampMs)

    @Test
    fun `처음에는 프레임이 없다`() {
        assertNull(LatestFrameProvider().latest())
    }

    @Test
    fun `가장 최근에 채운 프레임을 돌려준다`() {
        val provider = LatestFrameProvider()
        val first = frame(1)
        val second = frame(2)
        provider.update(first)
        assertSame(first, provider.latest())
        provider.update(second)
        assertSame(second, provider.latest())
    }

    @Test
    fun `비우면 null을 돌려준다`() {
        val provider = LatestFrameProvider()
        provider.update(frame(1))
        provider.update(null)
        assertNull(provider.latest())
    }
}
