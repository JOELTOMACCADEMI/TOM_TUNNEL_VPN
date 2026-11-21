package com.tomtunnel.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identifiant unique d'installation : TOM-XXXX-XXXX-XXXX.
 * - 12 caractères tirés avec SecureRandom (alphabet sans caractères ambigus, ~60 bits d'entropie) ;
 * - aucune donnée matérielle (IMEI, série...) n'est utilisée ;
 * - stocké chiffré (AES-GCM, clé dans l'Android Keystore) ; allowBackup=false => une réinstallation donne un nouvel ID ;
 * - lecture seule : aucune API ne permet de le modifier.
 */
object DeviceId {
    private const val ALIAS = "tom_device_id_key"
    private const val PREFS = "tomtunnel_secure"
    private const val KEY = "did"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val FORMAT = Regex("^TOM-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    @Synchronized
    fun get(ctx: Context): String {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        if (stored != null) {
            val id = try { decrypt(stored) } catch (_: Throwable) { null }
            if (id != null && FORMAT.matches(id)) return id
        }
        val id = generate()
        // commit() : l'ID doit être écrit avant d'être affiché.
        prefs.edit().putString(KEY, encrypt(id)).commit()
        return id
    }

    internal fun generate(rnd: SecureRandom = SecureRandom()): String {
        val c = CharArray(12) { ALPHABET[rnd.nextInt(ALPHABET.length)] }
        val s = String(c)
        return "TOM-${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8, 12)}"
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val (iv, ct) = blob.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}
