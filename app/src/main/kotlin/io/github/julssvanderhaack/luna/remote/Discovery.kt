package io.github.julssvanderhaack.luna.remote

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A server announced on the Wi-Fi (mDNS), not saved yet. */
data class Found(val name: String, val protocol: Protocol, val host: String, val port: Int, val user: String) {
    val key get() = "${protocol.name}:$name"
}

/**
 * Finds SFTP, SMB and FTP servers on the local network the way desktop Luna
 * does with avahi: a PC with sshd announced, a NAS, another phone running
 * Luna's server. Runs only while Luna is on screen.
 */
class Discovery(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found
    private val byName = ConcurrentHashMap<String, Found>()
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    // before Android 14 NsdManager resolves one service at a time
    private val resolver = Executors.newSingleThreadExecutor()
    private val callbacks = Executors.newSingleThreadExecutor()
    /** Our own announcement ("Luna en <este móvil>"), left out. */
    var ownName: String? = null

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private val types = mapOf(
        "_sftp-ssh._tcp" to Protocol.SFTP,
        "_smb._tcp" to Protocol.SMB,
        "_ftp._tcp" to Protocol.FTP,
    )

    /**
     * From Android 17 looking at the local network needs a permission; without it Android
     * puts up its own device picker every time Luna opens, so Luna only looks once it's given.
     */
    fun allowed() = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        if (listeners.isNotEmpty() || !allowed()) return
        for ((type, proto) in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName == ownName) return
                    resolver.execute { resolve(info, proto) }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    byName.remove("${proto.name}:${info.serviceName}")
                    publish()
                }
            }
            if (runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.isSuccess) listeners += l
        }
    }

    @Synchronized
    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        byName.clear()
        publish()
    }

    private fun publish() {
        _found.value = byName.values.sortedBy { it.name.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, proto: Protocol) {
        val done = java.util.concurrent.CountDownLatch(1)
        fun got(r: NsdServiceInfo) {
            val host = if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: r.hostAddresses.firstOrNull() else r.host
            if (host != null) {
                val user = r.attributes["user"]?.decodeToString().orEmpty()
                val f = Found(r.serviceName, proto, host.hostAddress ?: return, r.port, user)
                byName[f.key] = f
                publish()
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = done.countDown()
                override fun onServiceUpdated(r: NsdServiceInfo) {
                    got(r)
                    done.countDown()
                }
                override fun onServiceLost() = done.countDown()
                override fun onServiceInfoCallbackUnregistered() {}
            }
            runCatching { nsd.registerServiceInfoCallback(info, callbacks, cb) }.onFailure { done.countDown() }
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { nsd.unregisterServiceInfoCallback(cb) }
        } else {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) = done.countDown()
                override fun onServiceResolved(r: NsdServiceInfo) {
                    got(r)
                    done.countDown()
                }
            })
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
