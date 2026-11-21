package com.tomtunnel.app.engine

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import android.util.Base64

/**
 * Remplace StrictHostKeyChecking=no : confiance à la première connexion (TOFU), puis refus si la clé change.
 * L'empreinte SHA-256 de la clé d'hôte est mémorisée par « hôte:port ».
 */
object HostKeyStore : HostKeyRepository {
    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("tomtunnel_hostkeys", Context.MODE_PRIVATE) }
    fun reset() { prefs?.edit()?.clear()?.apply() }

    private fun fp(key: ByteArray) = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP)

    @Synchronized
    override fun check(host: String, key: ByteArray): Int {
        val p = prefs ?: return HostKeyRepository.OK
        val known = p.getString(host, null)
        val now = fp(key)
        return when {
            known == null -> { p.edit().putString(host, now).apply(); HostKeyRepository.OK }
            known == now -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }
    override fun add(hostkey: HostKey, ui: UserInfo?) {}
    override fun remove(host: String, type: String) { prefs?.edit()?.remove(host)?.apply() }
    override fun remove(host: String, type: String, key: ByteArray) { prefs?.edit()?.remove(host)?.apply() }
    override fun getKnownHostsRepositoryID(): String = "tomtunnel"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String, type: String): Array<HostKey> = emptyArray()
}
