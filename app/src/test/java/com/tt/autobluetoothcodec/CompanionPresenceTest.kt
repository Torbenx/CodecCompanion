package com.tt.autobluetoothcodec

import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.events
import com.tt.autobluetoothcodec.TestSupport.resetApp
import com.tt.autobluetoothcodec.TestSupport.sbc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * K6 of the test concept: which API levels and phones link devices and observe their presence,
 * that the manifest receiver is enabled on all of them and the companion service since API 33.
 * Each test runs on every SDK level in its @Config.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ObservingCompanionDeviceManager::class])
class CompanionPresenceTest {
    private val sdk get() = Build.VERSION.SDK_INT

    @Before
    fun setUp() {
        resetApp(FakeA2dp())
        setCompanionFeature(true)
    }

    private fun setCompanionFeature(available: Boolean) =
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, available)

    private val companionManager get() = Shadow.extract<ObservingCompanionDeviceManager>(
        context.getSystemService(CompanionDeviceManager::class.java))

    /** Links the device like the companion device manager stores it, in lower case since API 33. */
    private fun link(address: String = ADDRESS) = companionManager.addAssociation(if (sdk >= 33) address.lowercase() else address)

    /**
     * Links the device through the system's dialog. Unlike [link], which only stores the association,
     * this also exempts the app from losing its permissions while unused, like the system does.
     */
    private fun linkLikeTheSystem(address: String = ADDRESS) {
        link(address)
        setAutoRevokeExempt(true)
    }

    private fun setAutoRevokeExempt(exempt: Boolean) = shadowOf(context.packageManager).setAutoRevokeWhitelisted(exempt)

    private fun autoApplyNeedsLink(address: String = ADDRESS) =
        PermissionUtil.isAssociationRequiredForAutoApply(context, device(address))

    private val receiverState get() = context.packageManager.getComponentEnabledSetting(
        ComponentName(context, A2dpConnectReceiver::class.java))

    @Test
    @Config(sdk = [28, 31, 33, 34, 36])
    fun canAssociateSince31WithTheFeature() {
        assertEquals(sdk >= 31, PermissionUtil.canAssociate(context))
        setCompanionFeature(false)
        assertFalse(PermissionUtil.canAssociate(context))
    }

    @Test
    @Config(sdk = [28, 31, 33, 34, 36])
    fun associationRequirements() {
        assertEquals(sdk >= 34, PermissionUtil.isAssociationRequired(context, device()))
        assertEquals(sdk >= 31, PermissionUtil.isAssociationRequiredForAutoApply(context, device()))
        if (sdk >= 31) {
            link()
            assertTrue(PermissionUtil.hasAssociation(context, device()))
            assertFalse(PermissionUtil.isAssociationRequired(context, device()))
            assertFalse(PermissionUtil.isAssociationRequiredForAutoApply(context, device()))
        }
    }

    @Test
    @Config(sdk = [34, 36])
    fun withoutTheFeatureTheCodecCantBeChangedSince34() {
        setCompanionFeature(false)
        assertTrue(PermissionUtil.isAssociationRequired(context, device()))
        assertFalse(PermissionUtil.isAssociationRequiredForAutoApply(context, device()))
    }

    @Test
    @Config(sdk = [31, 32])
    fun aLinkIsOnlyRequiredOnceOn31And32() {
        assertTrue(autoApplyNeedsLink())
        assertTrue(autoApplyNeedsLink(OTHER_ADDRESS))
        linkLikeTheSystem()
        // The link is only for the exemption, which covers every device
        assertFalse(autoApplyNeedsLink(OTHER_ADDRESS))
        // Unlinking keeps the exemption
        assertTrue(PermissionUtil.removeAssociation(context, device()))
        assertFalse(autoApplyNeedsLink())
        assertFalse(autoApplyNeedsLink(OTHER_ADDRESS))
    }

    @Test
    @Config(sdk = [31, 32])
    fun anExemptAppNeedsNoLinkOn31And32() {
        // Like after the user turned off removing the permissions of the unused app
        setAutoRevokeExempt(true)
        assertFalse(autoApplyNeedsLink())
        assertFalse(PermissionUtil.hasAssociation(context, device()))
    }

    @Test
    @Config(sdk = [31, 32])
    fun aLinkedDeviceIsEnoughWithoutTheExemptionOn31And32() {
        // The user turned removing the permissions back on after linking, linking again wouldn't change it
        linkLikeTheSystem()
        setAutoRevokeExempt(false)
        assertFalse(autoApplyNeedsLink(OTHER_ADDRESS))
    }

    @Test
    @Config(sdk = [33, 34, 36])
    fun everyDeviceNeedsItsOwnLinkSince33() {
        linkLikeTheSystem()
        assertFalse(autoApplyNeedsLink())
        // The companion service is only told about linked devices
        assertTrue(autoApplyNeedsLink(OTHER_ADDRESS))
    }

    @Test
    @Config(sdk = [28, 31, 32, 33, 36])
    fun theCompanionServiceIsEnabledSince33() {
        val service = ComponentName(context, CodecCompanionService::class.java)
        val info = context.packageManager.getServiceInfo(service, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals(sdk >= 33, info.enabled)
    }

    @Test
    @Config(sdk = [33, 34, 36])
    fun observesWhileLinkedWithAPreferredCodec() {
        link()
        CompanionPresence.update(context, device())
        assertTrue(companionManager.observed.isEmpty())
        SavedDeviceStore.setPreferred(context, device(), sbc())
        CompanionPresence.update(context, device())
        // By address up to API 35, as the manager stores it, and by association id since API 36
        val expected = if (sdk >= 36) "id " + PermissionUtil.findAssociations(context, device()).single().id
            else PermissionUtil.findAssociatedAddresses(context, device()).single()
        assertEquals(setOf(expected), companionManager.observed)
        SavedDeviceStore.remove(context, device())
        CompanionPresence.update(context, device())
        assertTrue(companionManager.observed.isEmpty())
        assertEquals(listOf("not observing presence", "observing presence", "not observing presence"),
            events.filter { it.type == EventType.OBSERVING_CHANGED }.map { it.message })
    }

    @Test
    @Config(sdk = [28, 31, 32])
    fun nothingIsObservedBefore33() {
        link()
        SavedDeviceStore.setPreferred(context, device(), sbc())
        CompanionPresence.update(context, device())
        CompanionPresence.syncAll(context)
        assertTrue(companionManager.observed.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    @Config(sdk = [33, 36])
    fun syncAllRepairsObservingWithoutLogging() {
        link()
        SavedDeviceStore.setPreferred(context, device(), sbc())
        CompanionPresence.syncAll(context)
        assertEquals(1, companionManager.observed.size)
        // Like after clearing the app data: the link and observing stay, the preferred codec is gone
        SavedDeviceStore.remove(context, device())
        CompanionPresence.syncAll(context)
        assertTrue(companionManager.observed.isEmpty())
        assertTrue(events.none { it.type == EventType.OBSERVING_CHANGED })
    }

    @Test
    @Config(sdk = [28, 31, 32, 33, 36])
    fun theReceiverIsEnabledOnEveryLevel() {
        // It applies the preferred codec when A2DP connects while the companion service doesn't run (C3)
        val receiver = ComponentName(context, A2dpConnectReceiver::class.java)
        assertTrue(context.packageManager.getReceiverInfo(receiver, 0).enabled)
        CompanionPresence.syncAll(context)
        setCompanionFeature(false)
        CompanionPresence.syncAll(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, receiverState)
    }

    @Test
    @Config(sdk = [31, 33])
    fun addressesAreMatchedIgnoringCase() {
        companionManager.addAssociation(ADDRESS.lowercase())
        assertTrue(PermissionUtil.hasAssociation(context, device()))
        // Passed back unchanged, the manager compares the strings exactly before API 33
        assertEquals(listOf(ADDRESS.lowercase()), PermissionUtil.findAssociatedAddresses(context, device()))
        SavedDeviceStore.setPreferred(context, device(), sbc())
        CompanionPresence.update(context, device())
        if (sdk >= 33)
            assertEquals(setOf(ADDRESS.lowercase()), companionManager.observed)
    }

    private companion object {
        const val OTHER_ADDRESS = "00:11:22:33:44:66"
    }
}
