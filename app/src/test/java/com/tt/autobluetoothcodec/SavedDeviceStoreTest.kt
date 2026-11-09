package com.tt.autobluetoothcodec

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.sbc
import com.tt.autobluetoothcodec.TestSupport.setUserUnlocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The saved devices' two tiers: the preferred codec at any time, their info only read once the phone is unlocked. */
@RunWith(AndroidJUnit4::class)
class SavedDeviceStoreTest {
    private val masks = listOf(
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_32),
    )

    @Before
    fun setUp() {
        setUserUnlocked(true)
        TestSupport.resetKeys()
        SavedDeviceStore.clearForTest(context)
    }

    @Test
    fun hasAnyFollowsTheSavedCodecs() {
        assertFalse(SavedDeviceStore.hasAny(context))
        SavedDeviceStore.setPreferred(context, device(), sbc())
        assertTrue(SavedDeviceStore.hasAny(context))
        assertEquals(setOf(ADDRESS), SavedDeviceStore.addresses(context))
        SavedDeviceStore.remove(context, device())
        assertFalse(SavedDeviceStore.hasAny(context))
    }

    @Test
    fun onlySavedDevicesHaveInfo() {
        SavedDeviceStore.updateInfo(context, device(), name = "Qudelix-5K")
        assertNull(SavedDeviceStore.getInfo(context, device()))
        SavedDeviceStore.setPreferred(context, device(), sbc())
        // Saved without info yet
        assertEquals(SavedDeviceStore.Info(), SavedDeviceStore.getInfo(context, device()))
    }

    @Test
    fun updatingTheInfoKeepsTheValuesNotGiven() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        SavedDeviceStore.updateInfo(context, device(), name = "Qudelix-5K", lastConnected = 1000, capabilities = masks)
        SavedDeviceStore.updateInfo(context, device(), lastConnected = 2000)
        assertEquals(SavedDeviceStore.Info("Qudelix-5K", 2000, masks), SavedDeviceStore.getInfo(context, ADDRESS.lowercase()))
    }

    @Test
    fun removingRemovesBothTiersAndRestoringPutsThemBack() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        SavedDeviceStore.updateInfo(context, device(), name = "Qudelix-5K", lastConnected = 1000, capabilities = masks)
        val entry = SavedDeviceStore.remove(context, device())!!
        assertNull(SavedDeviceStore.getPreferred(context, device()))
        assertNull(SavedDeviceStore.getInfo(context, device()))
        // Saving again starts without the old info
        SavedDeviceStore.setPreferred(context, device(), sbc())
        assertEquals(SavedDeviceStore.Info(), SavedDeviceStore.getInfo(context, device()))
        SavedDeviceStore.remove(context, device())

        SavedDeviceStore.restore(context, device(), entry)
        assertEquals(sbc(), SavedDeviceStore.getPreferred(context, device()))
        assertEquals(SavedDeviceStore.Info("Qudelix-5K", 1000, masks), SavedDeviceStore.getInfo(context, device()))
    }

    @Test
    fun removingADeviceThatIsntSavedReturnsNothing() {
        assertNull(SavedDeviceStore.remove(context, device()))
    }

    @Test
    fun beforeTheUnlockTheInfoCanOnlyBeWritten() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        setUserUnlocked(false)
        assertEquals(sbc(), SavedDeviceStore.getPreferred(context, device()))
        SavedDeviceStore.updateInfo(context, device(), lastConnected = 1000)
        assertThrows(IllegalStateException::class.java) { SavedDeviceStore.getInfo(context, device()) }
        setUserUnlocked(true)
        assertEquals(1000L, SavedDeviceStore.getInfo(context, device())!!.lastConnected)
    }
}
