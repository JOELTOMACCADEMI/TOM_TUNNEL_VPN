package com.tomtunnel.app.engine

import android.content.Context
import java.io.File

/**
 * Les binaires (xray, dnstt, hysteria) sont empaquetés comme bibliothèques natives (lib*.so)
 * pour qu'Android les extraie dans nativeLibraryDir, seul endroit d'où on peut les exécuter.
 */
object EngineAssets {
    private val libs = mapOf(
        "xray" to "libxray.so",
        "dnstt" to "libdnstt.so",
        "hysteria" to "libhysteria.so"
    )

    fun materialize(ctx: Context, family: String): File {
        val name = libs[family] ?: error("Moteur inconnu: $family")
        val f = File(ctx.applicationInfo.nativeLibraryDir, name)
        check(f.exists() && f.length() > 0) { "Binaire natif introuvable: ${f.absolutePath}. Lance scripts/bootstrap-engines.sh puis recompile." }
        return f
    }

    fun available(ctx: Context): List<String> = libs.filter { (_, n) ->
        File(ctx.applicationInfo.nativeLibraryDir, n).let { it.exists() && it.length() > 0 }
    }.keys.toList()
}
