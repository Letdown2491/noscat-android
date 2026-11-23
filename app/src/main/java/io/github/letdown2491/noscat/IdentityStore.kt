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
    private const val KEY_TAILNET = "tailnet_dir"

    /** Path of the imported tailnet device dir, or null when not joined. */
    fun tailnetDir(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAILNET, null)

    fun setTailnetDir(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TAILNET, path).commit()
    }
}
