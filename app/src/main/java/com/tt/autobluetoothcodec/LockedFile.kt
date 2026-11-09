package com.tt.autobluetoothcodec

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A file in device protected storage of records written before the user unlocks the phone, each encrypted by
 * [LockedStorageCrypto] on a line of its own. Keeps at least the newest [maxRecords]: once it has twice as many, the
 * older ones are dropped, which doesn't need to decrypt them.
 *
 * Not thread safe, each file must be used by one thread at a time.
 */
internal class LockedFile(
    private val context: Context,
    val file: File,
    // Binds the records to this file, see LockedStorageCrypto.encrypt
    private val contextInfo: String,
    private val maxRecords: Int,
) {
    companion object {
        private const val TAG = "LockedFile"
    }

    private val temporaryFile = File(file.path + ".new")
    // The lines in the file, null until the first append counts them
    private var lineCount: Int? = null

    fun exists() = file.exists()

    /** Encrypts and appends [record]. Drops it if there's no public key yet. */
    fun append(record: ByteArray) {
        val line = LockedStorageCrypto.encrypt(context, record, contextInfo) ?: return
        val count = lineCount ?: prepare()
        if (count + 1 >= 2 * maxRecords) {
            rewrite(keep = maxRecords - 1)
        }
        try {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
            lineCount = (lineCount ?: 0) + 1
        } catch (e: IOException) {
            Log.w(TAG, "appending to ${file.name} failed", e)
            // A partial line may be left, counted again by the next append
            lineCount = null
        }
    }

    /**
     * The records, oldest first, without the lines that can't be decrypted: a partial line or one encrypted with a key
     * that was lost. Only once the user unlocked the phone.
     */
    fun read(): List<ByteArray> {
        val lines = try {
            file.bufferedReader().use { it.readLines() }
        } catch (e: FileNotFoundException) {
            return emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "reading ${file.name} failed", e)
            return emptyList()
        }
        val nonBlank = lines.filter { it.isNotBlank() }
        val records = nonBlank.mapNotNull { LockedStorageCrypto.decrypt(context, it, contextInfo) }
        if (records.size != nonBlank.size)
            Log.w(TAG, "dropped ${nonBlank.size - records.size} lines of ${file.name} that can't be decrypted")
        return records
    }

    fun delete() {
        file.delete()
        temporaryFile.delete()
        // The next append creates the directory, if it's missing too
        lineCount = null
    }

    /** Repairs what an interrupted append or rewrite left and returns the lines. */
    private fun prepare(): Int {
        file.parentFile?.mkdirs()
        // An interrupted rewrite, the file still has all lines
        temporaryFile.delete()
        val count = try {
            RandomAccessFile(file, "rw").use { access ->
                // Ends a line left partial by an interrupted append, so the next record starts a line of its own
                if (access.length() > 0) {
                    access.seek(access.length() - 1)
                    if (access.read() != '\n'.code)
                        access.write('\n'.code)
                }
                access.seek(0)
                val buffer = ByteArray(8192)
                var count = 0
                while (true) {
                    val read = access.read(buffer)
                    if (read < 0)
                        break
                    for (i in 0 until read) {
                        if (buffer[i] == '\n'.code.toByte())
                            count++
                    }
                }
                count
            }
        } catch (e: IOException) {
            Log.w(TAG, "repairing ${file.name} failed", e)
            0
        }
        lineCount = count
        return count
    }

    /** Keeps the newest [keep] lines, replacing the file at once so a crash leaves either version. */
    private fun rewrite(keep: Int) {
        try {
            val kept = file.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }.takeLast(keep)
            FileOutputStream(temporaryFile).use { stream ->
                stream.write(kept.joinToString("") { it + "\n" }.toByteArray())
                // On the disk before it replaces the file
                stream.fd.sync()
            }
            if (!temporaryFile.renameTo(file))
                throw IOException("renaming ${temporaryFile.name} failed")
            lineCount = kept.size
        } catch (e: IOException) {
            Log.w(TAG, "dropping the oldest records of ${file.name} failed", e)
            temporaryFile.delete()
        }
    }
}
