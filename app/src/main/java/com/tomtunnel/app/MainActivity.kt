package com.tomtunnel.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ImageView
import android.view.View
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pendingServer: String? = null
    private val statePrefs by lazy { getSharedPreferences("tomtunnel_state", MODE_PRIVATE) }

    private val vpnPermission = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startVpn() else js("window.onVpn('error','Autorisation VPN refusée')")
    }
    private val notifPerm = registerForActivityResult(RequestPermission()) {}
    @Volatile private var ipGen = 0
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    private val udpTestRunning = AtomicBoolean(false)
    private val ipHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ipRefresh = Runnable { refreshIp() }

    /** Envoie l'IP locale tout de suite, puis l'IP publique (via le tunnel si le VPN est connecté). */
    private fun refreshIp() {
        val g = ++ipGen
        val vpn = VpnBus.last == "connected"
        Thread {
            val loc = NetInfo.local(this).put("vpn", vpn)
            if (g != ipGen) return@Thread
            js("window.onIp(${JSONObject.quote(loc.toString())})")
            if (loc.optString("net") == "none") return@Thread
            val pub = NetInfo.publicIp(vpn)
            if (g != ipGen) return@Thread
            js("window.onIp(${JSONObject.quote(loc.put("pub", pub).toString())})")
        }.start()
    }
    private fun scheduleIpRefresh(delay: Long = 600) { ipHandler.removeCallbacks(ipRefresh); ipHandler.postDelayed(ipRefresh, delay) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) Color.parseColor("#070B12") else Color.parseColor("#F7F9FC")
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            setBackgroundColor(bg)
            addJavascriptInterface(Bridge(), "JT")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = loader.shouldInterceptRequest(r.url)
                // tout lien externe s'ouvre dans la WebView intégrée
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.host == "appassets.androidplatform.net") return false
                    openUrl(r.url.toString()); return true
                }
                override fun onPageFinished(v: WebView, u: String) {
                    val state = statePrefs.getString("state", "")
                    if (!state.isNullOrBlank()) js("window.loadNativeState(${JSONObject.quote(state)})")
                    if (VpnBus.last == "connected" || VpnBus.last == "connecting") js("window.onVpn('${VpnBus.last}','')")
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
        // Afficher directement la WebView : évite un second logo natif et le délai artificiel.
        setContentView(web)
        VpnBus.onState = { s, m ->
            js("window.onVpn('$s',${JSONObject.quote(m)})")
            when (s) {
                "connected" -> scheduleIpRefresh(800)
                "idle", "error" -> scheduleIpRefresh(1500)
                else -> {}
            }
        }
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            netCb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: android.net.Network) { scheduleIpRefresh(1000) }
                override fun onLost(n: android.net.Network) { scheduleIpRefresh(1000) }
                override fun onLinkPropertiesChanged(n: android.net.Network, lp: android.net.LinkProperties) { scheduleIpRefresh(1000) }
            }
            cm.registerDefaultNetworkCallback(netCb!!)
        } catch (_: Throwable) {}
        VpnBus.onStats = { dl, ul, dr, ur, ping -> js("window.onStats($dl,$ul,$dr,$ur,$ping)") }
    }

    override fun onDestroy() {
        VpnBus.onState = null; VpnBus.onStats = null
        ipHandler.removeCallbacks(ipRefresh)
        try { netCb?.let { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        super.onDestroy()
    }
    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun openUrl(u: String) {
        val uri = Uri.parse(u)
        if (uri.scheme == "http" || uri.scheme == "https") {
            val chrome = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome")
            try { startActivity(chrome) }
            catch (_: ActivityNotFoundException) {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                catch (_: ActivityNotFoundException) { Toast.makeText(this, "Aucun navigateur disponible", Toast.LENGTH_SHORT).show() }
            }
        } else {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (_: ActivityNotFoundException) { Toast.makeText(this, "Aucune application compatible", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun startVpn() {
        startForegroundService(Intent(this, TomVpnService::class.java).putExtra("server", pendingServer))
    }

    inner class Bridge {
        @JavascriptInterface fun saveState(state: String) {
            statePrefs.edit().putString("state", state).apply()
        }
        @JavascriptInterface fun connect(json: String) = runOnUiThread {
            if (VpnBus.last == "connecting" || VpnBus.last == "connected") return@runOnUiThread  // pas de connexions multiples
            pendingServer = json
            val i = VpnService.prepare(this@MainActivity)
            if (i != null) vpnPermission.launch(i) else startVpn()
        }
        @JavascriptInterface fun disconnect() = runOnUiThread {
            startService(Intent(this@MainActivity, TomVpnService::class.java).setAction(TomVpnService.STOP))
        }
        @JavascriptInterface fun testUdp(json: String) {
            if (!udpTestRunning.compareAndSet(false, true)) {
                js("window.onUdpTest(${JSONObject.quote(JSONObject().put("ok", false).put("message", "Un test UDP est déjà en cours.").toString())})")
                return
            }
            if (VpnBus.last == "connected" || VpnBus.last == "connecting") {
                udpTestRunning.set(false)
                val result = JSONObject().put("ok", false).put("message", "Déconnectez le VPN avant de tester UDP.")
                js("window.onUdpTest(${JSONObject.quote(result.toString())})")
                return
            }
            Thread({
                val result = try {
                    val profile = JSONObject(json).put("type", "UDP")
                    val message = com.tomtunnel.app.engine.UdpEngine().testConnection(this@MainActivity, profile)
                    JSONObject().put("ok", true).put("message", message)
                } catch (e: Throwable) {
                    JSONObject().put("ok", false).put("message", (e.message ?: "Échec du test UDP").take(500))
                } finally {
                    udpTestRunning.set(false)
                }
                js("window.onUdpTest(${JSONObject.quote(result.toString())})")
            }, "tom-udp-connectivity-test").start()
        }
        /** Identifiant d'installation TOM-XXXX-XXXX-XXXX (lecture seule). */
        @JavascriptInterface fun getDeviceId(): String = DeviceId.get(this@MainActivity)
        @JavascriptInterface fun resetHostKeys() {
            com.tomtunnel.app.engine.HostKeyStore.init(this@MainActivity); com.tomtunnel.app.engine.HostKeyStore.reset()
        }
        @JavascriptInterface fun clearCache() {
            try { filesDir.listFiles { f -> f.name.endsWith(".log") }?.forEach { it.delete() } } catch (_: Throwable) {}
            runOnUiThread { try { web.clearCache(true) } catch (_: Throwable) {} }
        }
        @JavascriptInterface fun techInfo(): String = JSONObject()
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.joinToString(",")).put("model", Build.MODEL)
            .put("engines", org.json.JSONArray(listOf("SSH (JSch 0.2.18)", "UDP (Hysteria v1)", "Xray-core"))).toString()
        @JavascriptInterface fun refreshIp() { this@MainActivity.refreshIp() }
        @JavascriptInterface fun openUrl(u: String) = runOnUiThread { this@MainActivity.openUrl(u) }
        @JavascriptInterface fun shareText(txt: String) = runOnUiThread {
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt) }
            startActivity(Intent.createChooser(i, "Partager"))
        }
        @JavascriptInterface fun readClipboard(): String {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            return try { if (cm.hasPrimaryClip()) cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: "" else "" } catch (_: Throwable) { "" }
        }
        @JavascriptInterface fun copyText(t: String) = runOnUiThread {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("tom", t))
            Toast.makeText(this@MainActivity, "Copié", Toast.LENGTH_SHORT).show()
        }
    }
}