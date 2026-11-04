package com.buzzerbeater.seenario.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavePolicyTest {

    @Test
    fun `말한 적이 없으면 휴대폰 절전 상태를 따른다`() {
        assertFalse(PowerSavePolicy.isOn(userChoice = null, systemPowerSave = false))
        assertTrue(PowerSavePolicy.isOn(userChoice = null, systemPowerSave = true))
    }

    @Test
    fun `말로 고른 것이 휴대폰 절전 상태보다 앞선다`() {
        assertTrue(PowerSavePolicy.isOn(userChoice = true, systemPowerSave = false))
        assertFalse(PowerSavePolicy.isOn(userChoice = false, systemPowerSave = true))
    }

    @Test
    fun `휴대폰 절전 상태가 바뀌어도 앱 상태가 같으면 알리지 않는다`() {
        assertNull(PowerSavePolicy.systemChangeNotice(wasOn = true, systemPowerSave = true))
        assertNull(PowerSavePolicy.systemChangeNotice(wasOn = false, systemPowerSave = false))
        assertEquals(PowerSavePolicy.MSG_SYSTEM_ON, PowerSavePolicy.systemChangeNotice(wasOn = false, systemPowerSave = true))
        assertEquals(PowerSavePolicy.MSG_SYSTEM_OFF, PowerSavePolicy.systemChangeNotice(wasOn = true, systemPowerSave = false))
    }

    @Test
    fun `말로 켜고 끌 때 안내`() {
        assertEquals(PowerSavePolicy.MSG_ON, PowerSavePolicy.userNotice(wasOn = false, on = true))
        assertEquals(PowerSavePolicy.MSG_OFF, PowerSavePolicy.userNotice(wasOn = true, on = false))
        assertEquals(PowerSavePolicy.MSG_ALREADY_ON, PowerSavePolicy.userNotice(wasOn = true, on = true))
        assertEquals(PowerSavePolicy.MSG_ALREADY_OFF, PowerSavePolicy.userNotice(wasOn = false, on = false))
    }

    @Test
    fun `휴대폰이 절전 중일 때만 시작 안내에 한 줄을 붙인다`() {
        assertEquals("안녕", PowerSavePolicy.startupGreeting("안녕", systemPowerSave = false))
        assertEquals("안녕 ${PowerSavePolicy.MSG_STARTUP}", PowerSavePolicy.startupGreeting("안녕", systemPowerSave = true))
    }
}
