package io.github.julssvanderhaack.luna

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.theme.LunaTheme
import io.github.julssvanderhaack.luna.ui.AppRoot
import io.github.julssvanderhaack.luna.ui.MainViewModel
import io.github.julssvanderhaack.luna.ui.Route

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val palette by vm.palette.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            // status/navigation bar icons follow the theme, not the system's dark mode
            LaunchedEffect(palette.dark) {
                val style = if (palette.dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            LunaTheme(palette, settings.dynamicColor) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The server notification opens its screen. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SERVER, false) == true && vm.stack.lastOrNull() != Route.PhoneServer) vm.open(Route.PhoneServer)
    }

    companion object {
        const val EXTRA_SERVER = "server"
    }
}
