package io.github.julssvanderhaack.luna.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import io.github.julssvanderhaack.luna.LunaApp
import io.github.julssvanderhaack.luna.MainActivity
import io.github.julssvanderhaack.luna.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

data class ServerState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val port: Int = 0,
    val user: String = "",
    val addresses: List<String> = emptyList(),
    val fingerprint: String = "",
    val clients: Int = 0,
    val error: String? = null,
)

/**
 * The phone as an SFTP server, so the PC can open it in Nautilus or desktop
 * Luna (sftp://luna@<ip>:2222/) or with `sftp -P 2222`. SFTP only: no shell,
 * no port forwarding. Runs in the foreground with a Detener action.
 */
class SftpServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: SshServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var announcement: NsdManager.RegistrationListener? = null
    private val clients = AtomicInteger()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(getString(R.string.server_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (server == null) scope.launch { start() }
        return START_NOT_STICKY
    }

    private suspend fun start() {
        _state.value = ServerState(starting = true)
        val cfg = (application as LunaApp).settings.serverConfig()
        try {
            val sshd = buildSshServer(cfg, File(filesDir, "host_key"), object : SessionListener {
                override fun sessionCreated(session: Session) = update(clients.incrementAndGet())
                override fun sessionClosed(session: Session) = update(clients.decrementAndGet())
            })
            sshd.start()
            server = sshd
            val fp = sshd.keyPairProvider.loadKeys(null).firstOrNull()?.public?.let { KeyUtils.getFingerPrint(it) }.orEmpty()
            holdLocks()
            announce(cfg.port, cfg.user)
            _state.value = ServerState(
                running = true, port = cfg.port, user = cfg.user, addresses = addresses(), fingerprint = fp,
            )
            notify(getString(R.string.server_running_on, _state.value.addresses.firstOrNull() ?: "?", cfg.port))
        } catch (e: Exception) {
            _state.value = ServerState(error = e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
    }

    private fun update(n: Int) {
        _state.value = _state.value.copy(clients = n.coerceAtLeast(0))
    }

    @Suppress("DEPRECATION")
    private fun holdLocks() {
        // without these, Wi-Fi naps with the screen off and transfers crawl
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "luna:sftp").apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luna:sftp").apply { acquire() }
    }

    /**
     * Tells the local network the server is here (mDNS, _sftp-ssh._tcp), so
     * Luna on the PC lists the phone under Red by itself. "luna=1" marks it
     * as ours and "user" saves typing it; gone again when the server stops.
     */
    private fun announce(port: Int, user: String) {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val info = NsdServiceInfo().apply {
            serviceName = "Luna en $name"
            serviceType = "_sftp-ssh._tcp"
            setPort(port)
            setAttribute("luna", "1")
            setAttribute("user", user)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        // not being found on the network is no reason to fail the server
        runCatching {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            announcement = listener
        }
    }

    override fun onDestroy() {
        announcement?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        announcement = null
        runCatching { server?.stop(true) }
        server = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        if (_state.value.error == null) _state.value = ServerState()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SERVER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SftpServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.server_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.server_stop), stop).build())
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "sftp_server"
        private const val NOTIFICATION_ID = 1
        private val SKIP = listOf("rmnet", "ccmni", "v4-", "dummy", "tun", "ipsec", "p2p")
        const val ACTION_STOP = "io.github.julssvanderhaack.luna.STOP_SERVER"

        private val _state = MutableStateFlow(ServerState())
        val state: StateFlow<ServerState> = _state

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SftpServerService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SftpServerService::class.java).setAction(ACTION_STOP))
        }

        /** IPv4 addresses the PC can reach: Wi-Fi, Ethernet, the phone's own hotspot. */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                // not mobile data, VPNs or virtual interfaces: the PC can't reach those
                .filter { i -> i.isUp && !i.isLoopback && SKIP.none { i.name.startsWith(it) } }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
                .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() } }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}

