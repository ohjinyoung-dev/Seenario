package com.buzzerbeater.seenario.camera

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
    @Test
    fun `최근 프레임이면 요청하지 않고 바로 돌려준다`() = runBlocking {
        val provider = LatestFrameProvider(now = { 2_000L })
        var requests = 0
        provider.requester = { requests++ }
        val recent = frame(1_000)
        provider.update(recent)
        assertSame(recent, provider.fresh(maxAgeMs = 1_500))
        assertEquals(0, requests)
    }

    @Test
    fun `오래된 프레임이면 새 프레임을 요청하고 기다린다`() = runBlocking {
        val provider = LatestFrameProvider(now = { 10_000L })
        val old = frame(1_000)
        val newer = frame(10_000)
        provider.update(old)
        provider.requester = { provider.update(newer) }
        assertSame(newer, provider.fresh(maxAgeMs = 1_500, timeoutMs = 1_000))
    }

    @Test
    fun `요청한 프레임이 늦게 와도 기다린다`() = runBlocking {
        val provider = LatestFrameProvider(now = { 10_000L })
        var requests = 0
        provider.requester = { requests++ }
        val result = async { provider.fresh(timeoutMs = 1_000) }
        yield()
        val newer = frame(10_000)
        provider.update(newer)
        assertSame(newer, result.await())
        assertEquals(1, requests)
    }

    @Test
    fun `기다려도 오지 않으면 지금 있는 프레임을 돌려준다`() = runBlocking {
        val provider = LatestFrameProvider(now = { 10_000L })
        val old = frame(1_000)
        provider.update(old)
        provider.requester = {}
        assertSame(old, provider.fresh(maxAgeMs = 1_500, timeoutMs = 50))
    }

    @Test
    fun `카메라가 없으면 기다리지 않는다`() = runBlocking {
        val provider = LatestFrameProvider(now = { 10_000L })
        assertNull(provider.fresh(timeoutMs = 5_000))
    }
}
