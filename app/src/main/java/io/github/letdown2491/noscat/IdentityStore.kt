package io.github.letdown2491.noscat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the identity secret hex encrypted with an Android Keystore AES key.
 * The ciphertext lives in SharedPreferences; the key never leaves the keystore,
 * so a copied prefs file alone does not yield the identity.
 */
object IdentityStore {
    private const val PREFS = "noscat-identity"
    private const val KEY = "noscat-identity-secret"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** Returns the stored secret hex, or null on first run. */
    fun load(context: Context): String? = runCatching {
        val blob = prefs(context).getString(KEY, null) ?: return null
        val all = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    /** Encrypts and stores the secret hex. Throws if the keystore is unhappy. */
    fun save(context: Context, secretHex: String) {
        // Keystore keys forbid caller-provided IVs on encrypt: init without a
        // spec and persist the generated IV next to the ciphertext.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(secretHex.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv ?: error("keystore did not produce an IV")
        val blob = android.util.Base64.encodeToString(iv + ct, android.util.Base64.NO_WRAP)
        prefs(context).edit().putString(KEY, blob).commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val STORAGE_KEY = "storage-key"

    /** What [storageKey] found. */
    sealed interface StorageKeyResult {
        data class Ok(val key: ByteArray) : StorageKeyResult
        /** A wrapped key exists but the keystore cannot open it: the device
         * files stay sealed, and only Forget mesh recovers. */
        data class Unreadable(val error: Throwable) : StorageKeyResult
    }

    /**
     * The 32-byte key noscat seals the device files with (identity and
     * mesh state), wrapped by the Keystore AES key and kept in prefs.
     * Created on first use. Never replaced when it cannot be read: a new
     * key would not open the files the old one sealed.
     */
    fun storageKey(context: Context): StorageKeyResult {
        val blob = prefs(context).getString(STORAGE_KEY, null)
        if (blob != null) {
            return runCatching {
                val all = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    keystoreKey(),
                    GCMParameterSpec(GCM_TAG_BITS, all.copyOfRange(0, IV_LEN)),
                )
                StorageKeyResult.Ok(cipher.doFinal(all.copyOfRange(IV_LEN, all.size)))
            }.getOrElse { StorageKeyResult.Unreadable(it) }
        }
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(key)
        val iv = cipher.iv ?: error("keystore did not produce an IV")
        prefs(context).edit()
            .putString(STORAGE_KEY, android.util.Base64.encodeToString(iv + ct, android.util.Base64.NO_WRAP))
            .commit()
        return StorageKeyResult.Ok(key)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }
}

/** Plain (non-secret) app settings. */
object AppPrefs {
    private const val PREFS = "noscat-app"
    // noscat 0.5 starts fresh: a 0.4 mesh dir (stored under "mesh_dir")
    // is never read, so an upgraded phone joins the new mesh again.
    private const val KEY_MESH = "device_dir"

    /** Path of the imported mesh device dir, or null when not joined. */
    fun meshDir(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MESH, null)
    }

    fun setMeshDir(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESH, path).commit()
    }

    private const val KEY_DISCONNECTED = "disconnected"

    /** The user disconnected: stay a member but do not run the client
     * until they reconnect (survives app restarts). */
    fun disconnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCONNECTED, false)

    fun setDisconnected(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISCONNECTED, value).commit()
    }

    private const val KEY_DEVICE_NAME = "device_name"

    /** The user-chosen device name, or null (fall back to the model). */
    fun deviceName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_NAME, null)

    fun setDeviceName(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_NAME, name).commit()
    }

    private const val KEY_THEME = "theme"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    const val THEME_SYSTEM = "system"

    /** The UI theme. Dark is the default: the app is a terminal-like mesh
     * tool used at night, and dark saves the battery on OLED. */
    fun theme(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, THEME_DARK) ?: THEME_DARK

    fun setTheme(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value).commit()
    }
}
