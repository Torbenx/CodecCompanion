package com.tt.autobluetoothcodec

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.OTHER_ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.denyPermission
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.grantPermission
import com.tt.autobluetoothcodec.TestSupport.ldac
import com.tt.autobluetoothcodec.TestSupport.operation
import com.tt.autobluetoothcodec.TestSupport.resetApp
import com.tt.autobluetoothcodec.TestSupport.resolution
import com.tt.autobluetoothcodec.TestSupport.runFor
import com.tt.autobluetoothcodec.TestSupport.runPending
import com.tt.autobluetoothcodec.TestSupport.VERIFY_MILLIS
import com.tt.autobluetoothcodec.TestSupport.sbc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** K1 to K3 of the test concept: the resolutions of a request, how requests are coordinated, the profile proxy. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CodecApplierTest {
    private val a2dp = FakeA2dp()
    private lateinit var applier: CodecApplier

    @Before
    fun setUp() {
        grantPermission()
        applier = resetApp(a2dp)
        a2dp.connected.add(ADDRESS)
        a2dp.connected.add(OTHER_ADDRESS)
    }

    private fun begin(address: String = ADDRESS) =
        EventLog.get(context).begin(EventType.USER_SET_CODEC, LogDevice(address))

    /** The resolutions passed to onDone, which must be called at most once. */
    private class Done {
        val resolutions = ArrayList<EventType>()
        val callback: (EventType) -> Unit = { resolutions.add(it) }
        val only: EventType? get() {
            assertTrue("onDone called ${resolutions.size} times", resolutions.size <= 1)
            return resolutions.firstOrNull()
        }
    }

    private fun apply(target: CodecConfig?, address: String = ADDRESS): Pair<EventLog.Operation, Done> {
        val operation = begin(address)
        val done = Done()
        applier.apply(device(address), operation, target, onDone = done.callback)
        return operation to done
    }

    private fun types(operation: EventLog.Operation) = operation(operation.uid).map { it.type }

    @Test
    fun verifiedOnTheFirstAttempt() {
        val (operation, done) = apply(sbc())
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, done.only)
        // Read twice before it counts
        assertEquals(listOf(EventType.USER_SET_CODEC, EventType.SET_CODEC_CONFIG, EventType.VALIDATE_CODEC_CONFIG,
            EventType.VALIDATE_CODEC_CONFIG, EventType.CODEC_VERIFIED), types(operation))
        assertEquals(1, resolution(operation.uid)!!.attempt)
        assertEquals(sbc().toAndroid(), resolution(operation.uid)!!.actual!!.android)
        assertTrue(applier.pending.value.isEmpty())
    }

    @Test
    fun verifiedOnALaterAttempt() {
        var sets = 0
        a2dp.onSet = { address, config -> if (++sets >= 3) a2dp.codecs[address] = config }
        val (operation, done) = apply(sbc())
        runFor(2500)
        assertEquals(EventType.CODEC_VERIFIED, done.only)
        assertEquals(3, resolution(operation.uid)!!.attempt)
        assertEquals(3, types(operation).count { it == EventType.SET_CODEC_CONFIG })
    }

    @Test
    fun aCodecChangedBackAfterTheFirstMatchingReadIsSetAgain() {
        val (operation, done) = apply(sbc())
        runFor(600)
        // Like the S24 right after A2DP connects: it reported the codec, then went back to its own
        a2dp.codecs[ADDRESS] = ldac().toAndroid()
        runFor(VERIFY_MILLIS + 500)
        assertEquals(EventType.CODEC_VERIFIED, done.only)
        assertEquals(2, resolution(operation.uid)!!.attempt)
        assertEquals(listOf(EventType.USER_SET_CODEC, EventType.SET_CODEC_CONFIG, EventType.VALIDATE_CODEC_CONFIG,
            EventType.VALIDATE_CODEC_CONFIG, EventType.SET_CODEC_CONFIG, EventType.VALIDATE_CODEC_CONFIG,
            EventType.VALIDATE_CODEC_CONFIG, EventType.CODEC_VERIFIED), types(operation))
        assertEquals(sbc().toAndroid(), a2dp.codecs[ADDRESS])
    }

    @Test
    fun maxAttemptsAfterFive() {
        a2dp.codecs[ADDRESS] = ldac().toAndroid()
        a2dp.onSet = { _, _ -> }
        val (operation, done) = apply(sbc())
        runFor(4000)
        assertEquals(EventType.MAX_ATTEMPTS, done.only)
        assertEquals(5, resolution(operation.uid)!!.attempt)
        assertEquals(5, types(operation).count { it == EventType.SET_CODEC_CONFIG })
        assertEquals(5, types(operation).count { it == EventType.VALIDATE_CODEC_CONFIG })
        assertEquals(ldac().toAndroid(), resolution(operation.uid)!!.actual!!.android)
    }

    @Test
    fun notConnected() {
        a2dp.connected.clear()
        val (operation, done) = apply(sbc())
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.NOT_CONNECTED, done.only)
        assertTrue(a2dp.setCalls.isEmpty())
        assertEquals(0, resolution(operation.uid)!!.attempt)
    }

    @Test
    @Config(sdk = [34])
    fun noAssociationSince34() {
        val (_, done) = apply(sbc())
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.NO_ASSOCIATION, done.only)
        assertTrue(a2dp.setCalls.isEmpty())
    }

    @Test
    @Config(sdk = [31])
    fun noPermission() {
        denyPermission()
        val (_, done) = apply(sbc())
        assertEquals(EventType.NO_PERMISSION, done.only)
        assertEquals(0, a2dp.connectCount)
    }

    @Test
    fun noPreferredCodec() {
        val (_, done) = apply(null)
        assertEquals(EventType.NO_PREFERRED_CODEC, done.only)
        assertEquals(0, a2dp.connectCount)
    }

    @Test
    fun appliesThePreferredCodecWithoutTarget() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        val (operation, done) = apply(null)
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, done.only)
        assertEquals(sbc().toAndroid(), resolution(operation.uid)!!.actual!!.android)
    }

    @Test
    fun profileUnavailableWhileBluetoothIsOff() {
        a2dp.isBluetoothEnabled = false
        val (_, done) = apply(sbc())
        assertEquals(EventType.PROFILE_UNAVAILABLE, done.only)
        assertEquals(0, a2dp.connectCount)
    }

    @Test
    fun profileUnavailableWithoutProxy() {
        a2dp.canConnect = false
        val (_, done) = apply(sbc())
        assertEquals(EventType.PROFILE_UNAVAILABLE, done.only)
        assertTrue(applier.pending.value.isEmpty())
    }

    @Test
    fun profileDisconnectedDuringARequest() {
        a2dp.onSet = { _, _ -> }
        val (operation, done) = apply(sbc())
        runPending()
        a2dp.disconnectProfile()
        assertEquals(EventType.PROFILE_DISCONNECTED, done.only)
        assertEquals(1, resolution(operation.uid)!!.attempt)
        assertEquals(1, a2dp.closeCount)
        // The next request asks for the profile again
        a2dp.onSet = { address, config -> a2dp.codecs[address] = config }
        val (_, next) = apply(sbc())
        runFor(VERIFY_MILLIS)
        assertEquals(2, a2dp.connectCount)
        assertEquals(EventType.CODEC_VERIFIED, next.only)
    }

    @Test
    fun securityException() {
        a2dp.setFailure = SecurityException("refused")
        val (operation, done) = apply(sbc())
        runPending()
        assertEquals(EventType.SECURITY_EXCEPTION, done.only)
        assertEquals("java.lang.SecurityException: refused", resolution(operation.uid)!!.message)
    }

    @Test
    fun securityExceptionIsHandedBack() {
        a2dp.setFailure = SecurityException("refused")
        val operation = begin()
        val done = Done()
        var handedBack: EventLog.Operation? = null
        applier.apply(device(), operation, sbc(), onDone = done.callback, onSecurityException = { op, _ -> handedBack = op })
        runPending()
        assertSame(operation, handedBack)
        assertTrue(operation.isOpen)
        assertNull(done.only)
        assertTrue(applier.pending.value.isEmpty())
        operation.resolve(EventType.ERROR)
    }

    @Test
    fun otherExceptionsResolveError() {
        a2dp.setFailure = IllegalStateException("broken")
        val (_, done) = apply(sbc())
        runPending()
        assertEquals(EventType.ERROR, done.only)
    }

    @Test
    fun theSameCodecAgainIsAlreadyPending() {
        val (first, firstDone) = apply(sbc())
        val (second, secondDone) = apply(sbc())
        assertEquals(EventType.ALREADY_PENDING, secondDone.only)
        assertEquals("pending in operation ${first.uid}", resolution(second.uid)!!.message)
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, firstDone.only)
    }

    @Test
    fun anotherCodecSupersedesThePendingOne() {
        val first = begin()
        var pendingWhenSuperseded: Map<String, CodecConfig>? = null
        applier.apply(device(), first, sbc(), onDone = { pendingWhenSuperseded = applier.pending.value })
        runPending()
        val (second, secondDone) = apply(ldac())
        // Regression test for manual 1.5: observers never see the device without a request
        assertEquals(mapOf(ADDRESS to ldac()), pendingWhenSuperseded)
        assertEquals(EventType.SUPERSEDED, resolution(first.uid)!!.type)
        assertEquals("superseded by operation ${second.uid}", resolution(first.uid)!!.message)
        assertEquals(1, resolution(first.uid)!!.attempt)
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, secondDone.only)
        assertEquals(ldac().toAndroid(), a2dp.codecs[ADDRESS])
    }

    @Test
    fun cancelByDevice() {
        a2dp.onSet = { _, _ -> }
        val (operation, done) = apply(sbc())
        runPending()
        applier.cancel(device(), EventType.CANCELLED, "device disappeared")
        assertEquals(EventType.CANCELLED, done.only)
        assertEquals("device disappeared", resolution(operation.uid)!!.message)
        assertTrue(applier.pending.value.isEmpty())
        runFor(3000)
        assertEquals(1, a2dp.setCalls.size)
    }

    @Test
    fun cancelByOperation() {
        a2dp.onSet = { _, _ -> }
        val (operation, done) = apply(sbc())
        runPending()
        assertFalse(applier.cancel(begin().also { it.resolve(EventType.ERROR) }, EventType.CANCELLED))
        assertTrue(applier.cancel(operation, EventType.TIMEOUT))
        assertEquals(EventType.TIMEOUT, done.only)
        assertFalse(applier.cancel(operation, EventType.TIMEOUT))
    }

    @Test
    fun devicesHaveTheirOwnTiming() {
        var otherSets = 0
        a2dp.onSet = { address, config ->
            if (address == ADDRESS || ++otherSets >= 3)
                a2dp.codecs[address] = config
        }
        val (operation, done) = apply(sbc())
        val (other, otherDone) = apply(ldac(), OTHER_ADDRESS)
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, done.only)
        assertNull(otherDone.only)
        assertEquals(setOf(OTHER_ADDRESS), applier.pending.value.keys)
        runFor(1500)
        assertEquals(EventType.CODEC_VERIFIED, otherDone.only)
        assertEquals(1, resolution(operation.uid)!!.attempt)
        assertEquals(3, resolution(other.uid)!!.attempt)
    }

    @Test
    fun theKnownCodecIsTheLastReadWhenTheRequestEnds() {
        assertNull(applier.knownCodec(ADDRESS))
        var knownWhenDone: AndroidCodecConfig? = null
        applier.apply(device(), begin(), sbc(), onDone = { knownWhenDone = applier.knownCodec(ADDRESS) })
        runFor(VERIFY_MILLIS)
        // The main screen compares the codec with it once the device leaves pending
        assertEquals(sbc().toAndroid(), knownWhenDone)
        assertEquals(sbc().toAndroid(), applier.knownCodec(ADDRESS.lowercase()))
        assertNull(applier.knownCodec(OTHER_ADDRESS))
    }

    @Test
    fun theKnownCodecOfAFailedRequestIsItsLastRead() {
        a2dp.codecs[ADDRESS] = ldac().toAndroid()
        a2dp.onSet = { _, _ -> }
        val (_, done) = apply(sbc())
        runFor(4000)
        assertEquals(EventType.MAX_ATTEMPTS, done.only)
        assertEquals(ldac().toAndroid(), applier.knownCodec(ADDRESS))
    }

    @Test
    fun anObservedCodecIsKnownUntilTheNextRead() {
        applier.noteObservedCodec(ADDRESS.lowercase(), ldac().toAndroid())
        assertEquals(ldac().toAndroid(), applier.knownCodec(ADDRESS))
        // A read without a codec status keeps it
        a2dp.onSet = { _, _ -> }
        val (operation, _) = apply(sbc())
        runFor(600)
        assertTrue(EventType.VALIDATE_CODEC_CONFIG in types(operation))
        assertEquals(ldac().toAndroid(), applier.knownCodec(ADDRESS))
        a2dp.codecs[ADDRESS] = sbc().toAndroid()
        runFor(VERIFY_MILLIS)
        assertEquals(sbc().toAndroid(), applier.knownCodec(ADDRESS))
    }

    @Test
    fun theProfileIsRequestedOnceAndKept() {
        a2dp.connectsAutomatically = false
        val (_, first) = apply(sbc())
        val (_, other) = apply(ldac(), OTHER_ADDRESS)
        assertEquals(1, a2dp.connectCount)
        a2dp.connectProfile()
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, first.only)
        assertEquals(EventType.CODEC_VERIFIED, other.only)
        // Kept open after the requests
        val (_, later) = apply(ldac())
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, later.only)
        assertEquals(1, a2dp.connectCount)
        assertEquals(0, a2dp.closeCount)
    }

    @Test
    fun aDisconnectWhileIdleClosesTheProfileAndTheNextRequestOpensIt() {
        val (_, first) = apply(sbc())
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, first.only)
        // Like Bluetooth turning off (manual 2.3)
        a2dp.disconnectProfile()
        assertEquals(1, a2dp.closeCount)
        a2dp.isBluetoothEnabled = false
        val (_, off) = apply(ldac())
        assertEquals(EventType.PROFILE_UNAVAILABLE, off.only)
        assertEquals(1, a2dp.connectCount)
        a2dp.isBluetoothEnabled = true
        val (_, on) = apply(ldac())
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, on.only)
        assertEquals(2, a2dp.connectCount)
        assertEquals(1, a2dp.closeCount)
    }

    @Test
    fun resettingTheApplierClosesTheProfile() {
        val (_, done) = apply(sbc())
        runFor(VERIFY_MILLIS)
        assertEquals(EventType.CODEC_VERIFIED, done.only)
        CodecApplier.resetForTest(context)
        assertEquals(1, a2dp.closeCount)
        assertFalse(a2dp.isProfileConnected)
    }
}
