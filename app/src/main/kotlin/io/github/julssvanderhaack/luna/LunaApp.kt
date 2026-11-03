package io.github.julssvanderhaack.luna

import android.app.Application
import io.github.julssvanderhaack.luna.fs.Volumes
import io.github.julssvanderhaack.luna.remote.ServerStore
import io.github.julssvanderhaack.luna.fs.Keyring
import io.github.julssvanderhaack.luna.fs.Visits
import io.github.julssvanderhaack.luna.remote.Discovery
import io.github.julssvanderhaack.luna.remote.Remotes
import io.github.julssvanderhaack.luna.settings.Settings
import io.github.julssvanderhaack.luna.theme.ThemeRepository
import io.github.julssvanderhaack.luna.ui.Thumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils

class LunaApp : Application() {
    /** Outlives screens: copies keep going when you navigate away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var settings: Settings
    lateinit var themes: ThemeRepository
    lateinit var volumes: Volumes
    lateinit var servers: ServerStore
    lateinit var remotes: Remotes
    lateinit var visits: Visits
    lateinit var keyring: Keyring
    lateinit var discovery: Discovery

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        // MINA SSHD looks for ~ and the OS; on Android neither is what it expects
        OsUtils.setAndroid(true)
        System.setProperty("user.home", filesDir.path)
        PathUtils.setUserHomeFolderResolver { filesDir.toPath() }
        Thumbs.init(this)
        settings = Settings(this)
        themes = ThemeRepository(this)
        volumes = Volumes(this)
        servers = ServerStore(filesDir)
        remotes = Remotes(servers)
        visits = Visits(filesDir)
        keyring = Keyring(filesDir)
        discovery = Discovery(this)
        // the phone's own SFTP server announces itself under this name: not something to connect to
        val device = android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        discovery.ownName = "Luna en ${device?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL}"
    }
}
