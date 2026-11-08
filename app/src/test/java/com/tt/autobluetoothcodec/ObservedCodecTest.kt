package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.grantPermission
import com.tt.autobluetoothcodec.TestSupport.ldac
import com.tt.autobluetoothcodec.TestSupport.resetApp
import com.tt.autobluetoothcodec.TestSupport.runFor
import com.tt.autobluetoothcodec.TestSupport.sbc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothA2dp

/**
 * K10 of the test concept: the main screen logs the codecs it reads that differ from the last one the applier knows
 * for the device in this process, so the first one read in a process as well, but not the codecs the applier read.
 *
 * The screen reads the codec through its own profile proxy, Robolectric's, and the applier through the fake. Both
 * report the codec of [stack], like the phone's single Bluetooth stack.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ObservedCodecTest {
    private val a2dp = FakeA2dp()
    private lateinit var applier: CodecApplier
    private lateinit var profile: BluetoothA2dp
    private lateinit var proxy: ShadowBluetoothA2dp
    private var screen: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        grantPermission()
        applier = resetApp(a2dp)
        MainActivity.resetForTest()
        a2dp.connected.add(ADDRESS)
        a2dp.onSet = { _, config -> stack(config) }
        profile = Shadow.newInstanceOf(BluetoothA2dp::class.java)
        proxy = Shadow.extract(profile)
        proxy.addDevice(device(), BluetoothProfile.STATE_CONNECTED)
        adapter.setEnabled(true)
        stack(sbc().toAndroid())
    }

    private val adapter get() = shadowOf(context.getSystemService(BluetoothManager::class.java).adapter)

    @After
    fun tearDown() {
        screen?.pause()?.stop()?.destroy()
    }

    /** The stack uses [config] for the device from now on. */
    @Suppress("DEPRECATION")
    private fun stack(config: AndroidCodecConfig) {
        a2dp.codecs[ADDRESS] = config
        // setCodecType is deprecated since API 35, the app only reads types it knows
        val codec = BluetoothCodecConfig.Builder()
            .setCodecType(config.codecType)
            .setSampleRate(config.sampleRate)
            .setBitsPerSample(config.bitsPerSample)
            .setCodecSpecific1(config.codecSpecific1.toLong())
            .build()
        proxy.setCodecStatus(device(), BluetoothCodecStatus.Builder()
            .setCodecConfig(codec)
            .setCodecsSelectableCapabilities(listOf(codec))
            .build())
    }

    /** Opens the main screen, which reads the codec every second while it's started. */
    private fun open() {
        // Robolectric drops a proxy once it's closed, the system hands out a new one
        adapter.setProfileProxy(BluetoothProfile.A2DP, profile)
        screen = Robolectric.buildActivity(MainActivity::class.java).setup()
        // A few polls
        runFor(3_000)
    }

    private fun close() {
        screen!!.pause().stop().destroy()
        screen = null
    }

    /** Like a new process: the applier forgets the codecs it knows, the log stays. */
    private fun restartProcess() {
        CodecApplier.resetForTest(context, a2dp)
        applier = CodecApplier.get(context)
        MainActivity.resetForTest()
    }

    private fun apply(target: CodecConfig): EventType? {
        var resolution: EventType? = null
        val operation = EventLog.get(context).begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        applier.apply(device(), operation, target, onDone = { resolution = it })
        runFor(4_000)
        return resolution
    }

    /** The codecs logged as observed, in order. */
    private fun observed() = TestSupport.events
        .filter { it.type == EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE }
        .map {
            assertEquals(ADDRESS, it.device!!.address)
            it.actual!!.android
        }

    @Test
    fun theFirstCodecReadInAProcessIsLoggedOnce() {
        open()
        assertEquals(listOf(sbc().toAndroid()), observed())
    }

    @Test
    fun theScreenOpenedAgainInTheSameProcessLogsNothing() {
        open()
        close()
        open()
        assertEquals(listOf(sbc().toAndroid()), observed())
        // It does read the codec
        stack(ldac().toAndroid())
        runFor(3_000)
        assertEquals(listOf(sbc().toAndroid(), ldac().toAndroid()), observed())
    }

    @Test
    fun aNewProcessLogsTheCodecAgain() {
        open()
        close()
        restartProcess()
        open()
        assertEquals(listOf(sbc().toAndroid(), sbc().toAndroid()), observed())
    }

    @Test
    fun aCodecTheApplierReadIsntLogged() {
        assertEquals(EventType.CODEC_VERIFIED, apply(ldac()))
        open()
        assertEquals(emptyList<AndroidCodecConfig>(), observed())
    }

    @Test
    fun aCodecTheApplierFailedToChangeIsntLogged() {
        // The stack ignores the change, the applier read the codec it kept
        a2dp.onSet = { _, _ -> }
        assertEquals(EventType.MAX_ATTEMPTS, apply(ldac()))
        open()
        assertEquals(emptyList<AndroidCodecConfig>(), observed())
    }

    @Test
    fun aChangedCodecIsLoggedOnce() {
        open()
        stack(ldac().toAndroid())
        runFor(3_000)
        assertEquals(listOf(sbc().toAndroid(), ldac().toAndroid()), observed())
        // Only the last codec is known, so changing back is a change as well
        stack(sbc().toAndroid())
        runFor(3_000)
        assertEquals(listOf(sbc().toAndroid(), ldac().toAndroid(), sbc().toAndroid()), observed())
    }

    @Test
    fun aChangeWhileTheScreenIsStoppedIsLoggedOnceItsBack() {
        open()
        screen!!.pause().stop()
        stack(ldac().toAndroid())
        runFor(3_000)
        assertEquals(listOf(sbc().toAndroid()), observed())
        screen!!.start().resume()
        runFor(3_000)
        assertEquals(listOf(sbc().toAndroid(), ldac().toAndroid()), observed())
    }

    @Test
    fun theApplierOwnChangeWithTheScreenOpenIsntLogged() {
        open()
        // The first attempt only gets the stack to an intermediate codec, which the screen reads while it's pending
        val intermediate = sbc(CodecConfig.SAMPLE_RATE_48000).toAndroid()
        a2dp.onSet = { _, config -> stack(if (a2dp.setCalls.size == 1) intermediate else config) }
        assertEquals(EventType.CODEC_VERIFIED, apply(ldac()))
        assertEquals(2, a2dp.setCalls.size)
        runFor(3_000)
        assertEquals(listOf(sbc().toAndroid()), observed())
    }
}
