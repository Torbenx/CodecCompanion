package com.tt.autobluetoothcodec

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.sbc
import com.tt.autobluetoothcodec.TestSupport.setUserUnlocked
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * K9 of the test concept: what the app writes to device protected storage before the user unlocks the phone, encrypted
 * by [LockedStorageCrypto], and the saved devices' info written then. The log's part is in [DirectBootTest].
 */
@RunWith(AndroidJUnit4::class)
class LockedStorageTest {
    private val deviceContext get() = context.createDeviceProtectedStorageContext()
    private val lockedInfoFile get() = SavedDeviceStore.lockedFile(context)
    private val masks = listOf(
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
    )

    @Before
    fun setUp() {
        setUserUnlocked(true)
        TestSupport.resetKeys()
        EventLog.resetForTest(context)
        SavedDeviceStore.clearForTest(context)
    }

    /** Starts the process again before the user unlocks, as after a reboot. */
    private fun restartLocked() {
        setUserUnlocked(false)
        EventLog.restartForTest()
        SavedDeviceStore.restartForTest()
    }

    private fun keyset(context: Context, name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("keyset", null)

    // The records

    @Test
    fun recordsArePaddedToTheSameSize() {
        assertEquals(LockedStorageCrypto.MIN_PADDED_SIZE, LockedStorageCrypto.paddedSize(0))
        assertEquals(LockedStorageCrypto.MIN_PADDED_SIZE, LockedStorageCrypto.paddedSize(LockedStorageCrypto.MIN_PADDED_SIZE - 1))
        // Longer ones to the next multiple of the block, with room for the mark
        assertEquals(LockedStorageCrypto.MIN_PADDED_SIZE + 256, LockedStorageCrypto.paddedSize(LockedStorageCrypto.MIN_PADDED_SIZE))
        assertEquals(2048, LockedStorageCrypto.paddedSize(2047))
        assertEquals(2304, LockedStorageCrypto.paddedSize(2048))
    }

    @Test
    fun paddingRoundTrips() {
        for (plaintext in listOf(ByteArray(0), "{}".toByteArray(), ByteArray(767) { 1 }, byteArrayOf(5, 0, 0))) {
            val padded = LockedStorageCrypto.pad(plaintext)
            assertEquals(LockedStorageCrypto.paddedSize(plaintext.size), padded.size)
            assertArrayEquals(plaintext, LockedStorageCrypto.unpad(padded))
        }
    }

    @Test
    fun anInvalidPaddingIsRejected() {
        assertThrows(java.security.GeneralSecurityException::class.java) { LockedStorageCrypto.unpad(ByteArray(768)) }
        assertThrows(java.security.GeneralSecurityException::class.java) {
            LockedStorageCrypto.unpad(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun recordsOnlyDecryptWithTheSameContextInfo() {
        val line = LockedStorageCrypto.encrypt(context, "secret".toByteArray(), "a")!!
        assertEquals("secret", LockedStorageCrypto.decrypt(context, line, "a")!!.decodeToString())
        assertNull(LockedStorageCrypto.decrypt(context, line, "b"))
        assertNull(LockedStorageCrypto.decrypt(context, "not base64!", "a"))
        assertNull(LockedStorageCrypto.decrypt(context, line.substring(0, line.length / 2), "a"))
    }

    @Test
    fun recordsOfTheSameLengthClassHaveTheSameLength() {
        val short = LockedStorageCrypto.encrypt(context, ByteArray(1), "a")!!
        val long = LockedStorageCrypto.encrypt(context, ByteArray(LockedStorageCrypto.MIN_PADDED_SIZE - 1), "a")!!
        assertEquals(short.length, long.length)
        // Encrypting twice gives different lines
        assertNotEquals(short, LockedStorageCrypto.encrypt(context, ByteArray(1), "a"))
    }

    @Test
    fun aDeletedFileIsCreatedAgainWithItsDirectory() {
        val file = LockedFile(context, java.io.File(java.io.File(deviceContext.filesDir, "test"), "records"), "test", 10)
        file.append("a".toByteArray())
        file.delete()
        file.file.parentFile!!.deleteRecursively()
        file.append("b".toByteArray())
        assertEquals(listOf("b"), file.read().map { it.decodeToString() })
    }

    // The keys

    @Test
    fun thePrivateKeyIsOnlyInCredentialProtectedStorage() {
        assertNotNull(keyset(context, LockedStorageCrypto.PRIVATE_PREFERENCES))
        assertNull(keyset(deviceContext, LockedStorageCrypto.PRIVATE_PREFERENCES))
        assertNotNull(keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
        assertNull(keyset(context, LockedStorageCrypto.PUBLIC_PREFERENCES))
        assertNotEquals(keyset(context, LockedStorageCrypto.PRIVATE_PREFERENCES),
            keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
    }

    @Test
    fun theKeysAreKeptAcrossProcesses() {
        val public = keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES)
        val line = LockedStorageCrypto.encrypt(context, "a".toByteArray(), "a")!!
        LockedStorageCrypto.restartForTest()
        LockedStorageCrypto.ensureKeys(context)
        assertEquals(public, keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
        assertEquals("a", LockedStorageCrypto.decrypt(context, line, "a")!!.decodeToString())
    }

    @Test
    fun aMissingPublicKeyIsWrittenAgain() {
        val public = keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES)
        deviceContext.getSharedPreferences(LockedStorageCrypto.PUBLIC_PREFERENCES, Context.MODE_PRIVATE).edit()
            .clear().commit()
        LockedStorageCrypto.restartForTest()
        LockedStorageCrypto.ensureKeys(context)
        assertEquals(public, keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
    }

    @Test
    fun aLostPrivateKeyReplacesThePublicKey() {
        val public = keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES)
        context.getSharedPreferences(LockedStorageCrypto.PRIVATE_PREFERENCES, Context.MODE_PRIVATE).edit()
            .clear().commit()
        LockedStorageCrypto.restartForTest()
        LockedStorageCrypto.ensureKeys(context)
        assertNotEquals(public, keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
        val line = LockedStorageCrypto.encrypt(context, "a".toByteArray(), "a")!!
        assertEquals("a", LockedStorageCrypto.decrypt(context, line, "a")!!.decodeToString())
    }

    @Test
    fun theKeysAreMadeOnlyOnceTheUserUnlocked() {
        LockedStorageCrypto.deleteKeysForTest(context)
        setUserUnlocked(false)
        assertNull(LockedStorageCrypto.encrypt(context, "a".toByteArray(), "a"))
        assertThrows(IllegalStateException::class.java) { LockedStorageCrypto.ensureKeys(context) }
        // The log makes them once it notices the unlock
        setUserUnlocked(true)
        EventLog.get(context)
        assertNotNull(keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
    }

    @Test
    fun aProcessStartedWhileUnlockedMakesTheKeys() {
        // Whatever starts it, like the test snippet, which saves a codec without the log
        LockedStorageCrypto.deleteKeysForTest(context)
        (context as CodecApplication).onCreate()
        assertNotNull(keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
        assertNotNull(keyset(context, LockedStorageCrypto.PRIVATE_PREFERENCES))
    }

    @Test
    fun aProcessStartedWhileLockedDoesntMakeTheKeys() {
        LockedStorageCrypto.deleteKeysForTest(context)
        setUserUnlocked(false)
        (context as CodecApplication).onCreate()
        assertNull(keyset(deviceContext, LockedStorageCrypto.PUBLIC_PREFERENCES))
    }

    @Test
    fun theKeysAndTheLockedFilesArentBackedUp() {
        val expected = setOf(
            "file" to "logs",
            "device_file" to "logs",
            "device_file" to SavedDeviceStore.LOCKED_DIRECTORY,
            "sharedpref" to LockedStorageCrypto.PRIVATE_PREFERENCES + ".xml",
            "device_sharedpref" to LockedStorageCrypto.PUBLIC_PREFERENCES + ".xml",
        )
        assertEquals(listOf(expected), excludes(R.xml.backup_rules, "full-backup-content"))
        assertEquals(listOf(expected, expected), excludes(R.xml.data_extraction_rules, "cloud-backup", "device-transfer"))
    }

    /** The excludes of each of the [sections] of the rules in [resource]. */
    private fun excludes(resource: Int, vararg sections: String): List<Set<Pair<String, String>>> {
        val result = ArrayList<Set<Pair<String, String>>>()
        val parser = context.resources.getXml(resource)
        var current: MutableSet<Pair<String, String>>? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name in sections)
                current = HashSet<Pair<String, String>>().also { result.add(it) }
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude")
                current!!.add(parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path"))
        }
        return result
    }

    // The saved devices' info

    @Test
    fun theInfoIsInCredentialProtectedStorage() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        SavedDeviceStore.updateInfo(context, device(), name = "Qudelix-5K")
        assertNotNull(context.getSharedPreferences("saved_device_info", Context.MODE_PRIVATE).getString(ADDRESS, null))
        assertNull(deviceContext.getSharedPreferences("saved_device_info", Context.MODE_PRIVATE).getString(ADDRESS, null))
        assertFalse(lockedInfoFile.exists())
    }

    @Test
    fun theInfoWrittenWhileLockedIsEncrypted() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        restartLocked()
        SavedDeviceStore.updateInfo(context, device(), name = "Qudelix-5K", lastConnected = 1000, capabilities = masks)
        SavedDeviceStore.updateInfo(context, device(), lastConnected = 2000)
        val lines = lockedInfoFile.readLines()
        assertEquals(2, lines.size)
        assertEquals(1, lines.map { it.length }.toSet().size)
        for (text in listOf(ADDRESS, "Qudelix"))
            assertFalse(text, lockedInfoFile.readText().contains(text))
        // As long as the event log's lines
        EventLog.get(context).log(EventType.TEST_LABEL, message = "a")
        TestSupport.flushLog()
        assertEquals(lines[0].length, EventLog.lockedFile(context).file.readLines().single().length)
    }

    @Test
    fun theInfoWrittenWhileLockedIsMergedInOrder() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        SavedDeviceStore.updateInfo(context, device(), name = "Old name", lastConnected = 500, capabilities = masks)
        restartLocked()
        SavedDeviceStore.updateInfo(context, device(), name = "Qudelix-5K", lastConnected = 1000)
        SavedDeviceStore.updateInfo(context, device(), lastConnected = 2000)
        setUserUnlocked(true)
        assertEquals(SavedDeviceStore.Info("Qudelix-5K", 2000, masks), SavedDeviceStore.getInfo(context, device()))
        assertFalse(lockedInfoFile.exists())
    }

    @Test
    fun theLogMergesTheInfoWhenItNoticesTheUnlock() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        restartLocked()
        SavedDeviceStore.updateInfo(context, device(), lastConnected = 1000)
        setUserUnlocked(true)
        // A process started after the unlock, which doesn't read the info
        EventLog.restartForTest()
        SavedDeviceStore.restartForTest()
        EventLog.get(context)
        assertFalse(lockedInfoFile.exists())
        val stored = context.getSharedPreferences("saved_device_info", Context.MODE_PRIVATE).getString(ADDRESS, null)
        assertEquals(1000L, SavedDeviceStore.json.decodeFromString<SavedDeviceStore.Info>(stored!!).lastConnected)
    }

    @Test
    fun aWriteAfterTheUnlockFollowsTheLockedOnes() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        restartLocked()
        SavedDeviceStore.updateInfo(context, device(), lastConnected = 1000)
        setUserUnlocked(true)
        SavedDeviceStore.updateInfo(context, device(), name = "Qudelix-5K")
        assertEquals(SavedDeviceStore.Info("Qudelix-5K", 1000), SavedDeviceStore.getInfo(context, device()))
    }

    @Test
    fun theLockedInfoKeepsTheNewestChanges() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        restartLocked()
        repeat(2 * SavedDeviceStore.MAX_LOCKED_UPDATES) {
            SavedDeviceStore.updateInfo(context, device(), lastConnected = it.toLong())
        }
        assertTrue(lockedInfoFile.readLines().size < 2 * SavedDeviceStore.MAX_LOCKED_UPDATES)
        setUserUnlocked(true)
        assertEquals(2L * SavedDeviceStore.MAX_LOCKED_UPDATES - 1, SavedDeviceStore.getInfo(context, device())!!.lastConnected)
    }

    @Test
    fun withoutAPublicKeyTheLockedInfoIsDropped() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        LockedStorageCrypto.deleteKeysForTest(context)
        restartLocked()
        SavedDeviceStore.updateInfo(context, device(), lastConnected = 1000)
        assertFalse(lockedInfoFile.exists())
        setUserUnlocked(true)
        assertEquals(SavedDeviceStore.Info(), SavedDeviceStore.getInfo(context, device()))
    }
}
