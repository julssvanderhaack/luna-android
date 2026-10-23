package io.github.julssvanderhaack.luna

import android.app.Application
import io.github.julssvanderhaack.luna.fs.Volumes
import io.github.julssvanderhaack.luna.remote.ServerStore
import io.github.julssvanderhaack.luna.remote.Sftp
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
    lateinit var sftp: Sftp

    override fun onCreate() {
        super.onCreate()
        // MINA SSHD looks for ~ and the OS; on Android neither is what it expects
        OsUtils.setAndroid(true)
        System.setProperty("user.home", filesDir.path)
        PathUtils.setUserHomeFolderResolver { filesDir.toPath() }
        Thumbs.init(this)
        settings = Settings(this)
        themes = ThemeRepository(this)
        volumes = Volumes(this)
        servers = ServerStore(filesDir)
        sftp = Sftp(servers)
    }
}
