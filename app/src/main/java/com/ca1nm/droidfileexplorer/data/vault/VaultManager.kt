package com.ca1nm.droidfileexplorer.data.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class VaultEntry(val id: String, val name: String, val size: Long, val added: Long)

class VaultLockedException : IOException("The vault is locked. Unlock it with your fingerprint, face or screen lock first.")

/**
 * Files are encrypted with AES-256-GCM using a key that lives in the Android Keystore and can
 * only be used within [UNLOCK_SECONDS] of a successful biometric or screen-lock check. File
 * names are encrypted too (they live in an encrypted index). Encrypted blobs are stored in the
 * app's private storage, so other apps and file managers can't see them.
 */
@Singleton
class VaultManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile get() = File(dir, "index.bin")
    private val cacheDir get() = File(context.cacheDir, "vault-open").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    /** The system rejects key use until the user has authenticated; this checks without throwing. */
    fun isUnlocked(): Boolean = try { Cipher.getInstance(TRANSFORM).init(Cipher.ENCRYPT_MODE, key()); true }
        catch (_: UserNotAuthenticatedException) { false }
        catch (_: KeyPermanentlyInvalidatedException) { false }
        catch (_: Exception) { false }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false) // adding a fingerprint must not destroy the vault
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(UNLOCK_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(UNLOCK_SECONDS)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    private fun encryptTo(out: OutputStream): CipherOutputStream {
        val c = try { Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) } }
                catch (e: UserNotAuthenticatedException) { throw VaultLockedException() }
        out.write(c.iv)
        return CipherOutputStream(out, c)
    }

    private fun decryptFrom(input: InputStream): CipherInputStream {
        val iv = ByteArray(12)
        var read = 0
        while (read < 12) { val n = input.read(iv, read, 12 - read); if (n < 0) throw IOException("Corrupt vault file"); read += n }
        val c = try { Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) } }
                catch (e: UserNotAuthenticatedException) { throw VaultLockedException() }
        return CipherInputStream(input, c)
    }

    @Synchronized
    fun list(): List<VaultEntry> {
        if (!indexFile.exists()) return emptyList()
        return indexFile.inputStream().buffered().use { decryptFrom(it).readBytes() }
            .let { json.decodeFromString<List<VaultEntry>>(String(it)) }.sortedByDescending { it.added }
    }

    private fun writeIndex(entries: List<VaultEntry>) {
        val tmp = File(dir, "index.tmp")
        tmp.outputStream().buffered().use { raw -> encryptTo(raw).use { it.write(json.encodeToString(entries).toByteArray()) } }
        if (!tmp.renameTo(indexFile)) { tmp.copyTo(indexFile, overwrite = true); tmp.delete() }
    }

    /** Encrypts [input] into the vault under [name]. */
    @Synchronized
    fun add(name: String, input: InputStream): VaultEntry {
        val existing = list()
        val id = java.util.UUID.randomUUID().toString()
        val blob = File(dir, "$id.bin")
        var size = 0L
        try {
            blob.outputStream().buffered().use { raw ->
                encryptTo(raw).use { enc ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; enc.write(buf, 0, n); size += n }
                }
            }
            val entry = VaultEntry(id, name, size, System.currentTimeMillis())
            writeIndex(existing + entry)
            return entry
        } catch (e: Throwable) { blob.delete(); throw e }
    }

    /** Decrypts into [dest]. */
    @Synchronized
    fun extract(entry: VaultEntry, dest: File) {
        File(dir, "${entry.id}.bin").inputStream().buffered().use { raw ->
            decryptFrom(raw).use { dec -> dest.outputStream().buffered().use { dec.copyTo(it) } }
        }
    }

    /** Decrypts to the cache so another app can show it. Cleared by [clearCache] when the vault locks. */
    fun openTemp(entry: VaultEntry): File {
        val out = File(File(cacheDir, entry.id).apply { mkdirs() }, entry.name.replace('/', '_'))
        extract(entry, out)
        return out
    }

    @Synchronized
    fun delete(entry: VaultEntry) {
        val rest = list().filter { it.id != entry.id }
        File(dir, "${entry.id}.bin").delete()
        writeIndex(rest)
    }

    fun clearCache() { cacheDir.deleteRecursively() }

    companion object {
        // Name of the key in the Android Keystore (created on first use).
        private const val ALIAS = "dfe_vault"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val UNLOCK_SECONDS = 120
    }
}
