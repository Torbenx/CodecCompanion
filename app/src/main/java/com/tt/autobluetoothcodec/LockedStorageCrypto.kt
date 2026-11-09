package com.tt.autobluetoothcodec

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Encrypts what the app writes to device protected storage before the user unlocks the phone after a reboot, so only
 * the app itself can read it, and only once the user unlocked: an HPKE key pair whose public key is in device protected
 * storage and whose private key is only in credential protected storage. Each record is padded to the same size, see
 * [paddedSize], so their lengths don't tell the events apart.
 *
 * The key pair is made by [ensureKeys], when a process starts with the user unlocked ([CodecApplication]) or notices
 * the unlock ([EventLog]), whichever process does that first. Before that, [encrypt] has no key and
 * returns null: the app can only run before the first unlock after it was installed or its data was cleared if it was
 * started since, which needs an unlocked user. Neither key is backed up, see backup_rules.xml and
 * data_extraction_rules.xml: a restored public key without its private key would encrypt records nobody can read.
 *
 * Thread safe.
 */
object LockedStorageCrypto {
    private const val TAG = "LockedStorageCrypto"
    const val TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM"
    // Excluded from backups by backup_rules.xml and data_extraction_rules.xml
    const val PUBLIC_PREFERENCES = "locked_storage_public_key"
    const val PRIVATE_PREFERENCES = "locked_storage_private_key"
    private const val KEY_KEYSET = "keyset"

    /** The size every record is padded to, unless it's longer. The longest events are about 650 bytes of JSON. */
    const val MIN_PADDED_SIZE = 768
    /** Longer records are padded to a multiple of this. */
    const val PADDING_BLOCK = 256
    private const val PADDING_MARK = 0x80.toByte()

    init {
        HybridConfig.register()
    }

    private var encrypter: HybridEncrypt? = null
    private var decrypter: HybridDecrypt? = null

    /**
     * Makes the key pair if there is none, and puts the public key in device protected storage if it isn't there, so
     * it's there before the next reboot. Only once the user unlocked the phone, the private key is in credential
     * protected storage.
     */
    @Synchronized
    fun ensureKeys(context: Context) {
        check(context.getSystemService(UserManager::class.java).isUserUnlocked) {
            "the private key can only be read once the user unlocked the phone"
        }
        if (decrypter != null)
            return
        try {
            loadOrMakeKeys(context)
        } catch (e: GeneralSecurityException) {
            // Then nothing is written before the unlock, and what was can't be read, but the app works otherwise
            Log.e(TAG, "the keys can't be loaded or made", e)
        }
    }

    private fun loadOrMakeKeys(context: Context) {
        val privatePreferences = privatePreferences(context)
        val stored = privatePreferences.getString(KEY_KEYSET, null)?.let(::parsePrivate)
        val config = RegistryConfiguration.get()
        val privateKeyset = stored ?: KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE).toParameters(), config).also {
            Log.i(TAG, "making a new key pair")
            val serialized = TinkProtoKeysetFormat.serializeKeyset(it, InsecureSecretKeyAccess.get(), config)
            // Before the public key, which is useless without it
            privatePreferences.edit(commit = true) { putString(KEY_KEYSET, encode(serialized)) }
        }
        val publicKeyset = privateKeyset.publicKeysetHandle
        val publicText = encode(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(publicKeyset, config))
        val publicPreferences = publicPreferences(context)
        // Missing if its write failed, different if the private key was lost
        if (publicPreferences.getString(KEY_KEYSET, null) != publicText)
            publicPreferences.edit(commit = true) { putString(KEY_KEYSET, publicText) }
        encrypter = publicKeyset.getPrimitive(config, HybridEncrypt::class.java)
        decrypter = privateKeyset.getPrimitive(config, HybridDecrypt::class.java)
    }

    /**
     * Pads and encrypts [plaintext] bound to [contextInfo], which [decrypt] needs to match, as one line of Base64
     * without the line break. Null if there's no public key yet.
     */
    @Synchronized
    fun encrypt(context: Context, plaintext: ByteArray, contextInfo: String): String? {
        val encrypter = encrypter ?: loadEncrypter(context) ?: return null
        return try {
            encode(encrypter.encrypt(pad(plaintext), contextInfo.toByteArray()))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "encrypting failed", e)
            null
        }
    }

    /**
     * Decrypts a line of [encrypt] with the same [contextInfo], null if it can't: a partial line, a line encrypted with
     * a key that was lost, or no line of [encrypt] at all. Only once the user unlocked the phone.
     */
    @Synchronized
    fun decrypt(context: Context, line: String, contextInfo: String): ByteArray? {
        if (decrypter == null)
            ensureKeys(context)
        val decrypter = decrypter ?: return null
        return try {
            unpad(decrypter.decrypt(Base64.getDecoder().decode(line.trim()), contextInfo.toByteArray()))
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // Not Base64
            null
        }
    }

    private fun loadEncrypter(context: Context): HybridEncrypt? {
        val text = publicPreferences(context).getString(KEY_KEYSET, null) ?: run {
            Log.w(TAG, "no public key, the app hasn't run with the user unlocked yet")
            return null
        }
        return try {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(Base64.getDecoder().decode(text), RegistryConfiguration.get())
                .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
                .also { encrypter = it }
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "invalid public key", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid public key", e)
            null
        }
    }

    private fun parsePrivate(text: String): KeysetHandle? {
        return try {
            TinkProtoKeysetFormat.parseKeyset(
                Base64.getDecoder().decode(text), InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "invalid private key, making a new key pair", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid private key, making a new key pair", e)
            null
        }
    }

    /** The size [pad] pads [length] bytes to: [MIN_PADDED_SIZE], or the next multiple of [PADDING_BLOCK] above. */
    internal fun paddedSize(length: Int): Int {
        // At least one byte for the mark
        val blocks = (length + 1 + PADDING_BLOCK - 1) / PADDING_BLOCK
        return maxOf(MIN_PADDED_SIZE, blocks * PADDING_BLOCK)
    }

    /** Pads like ISO/IEC 7816-4: a 0x80 byte, then zeros up to [paddedSize]. */
    internal fun pad(plaintext: ByteArray): ByteArray {
        val padded = plaintext.copyOf(paddedSize(plaintext.size))
        padded[plaintext.size] = PADDING_MARK
        return padded
    }

    /** Reverses [pad], throws [GeneralSecurityException] if [padded] isn't padded that way. */
    internal fun unpad(padded: ByteArray): ByteArray {
        val mark = padded.indexOfLast { it != 0.toByte() }
        if (mark < 0 || padded[mark] != PADDING_MARK)
            throw GeneralSecurityException("invalid padding")
        return padded.copyOf(mark)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun publicPreferences(context: Context): SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PUBLIC_PREFERENCES, Context.MODE_PRIVATE)

    private fun privatePreferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PRIVATE_PREFERENCES, Context.MODE_PRIVATE)

    /** Forgets the keys as if the process restarted, for tests. Each test has its own context and files. */
    @VisibleForTesting
    fun restartForTest() {
        encrypter = null
        decrypter = null
    }

    /** Deletes both keys, for tests. [ensureKeys] makes a new pair. */
    @VisibleForTesting
    fun deleteKeysForTest(context: Context) {
        restartForTest()
        publicPreferences(context).edit(commit = true) { clear() }
        privatePreferences(context).edit(commit = true) { clear() }
    }
}
