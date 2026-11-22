package org.fetchx.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import org.fetchx.app.engine.LinkDetector
import org.fetchx.app.ui.AppViewModel
import org.fetchx.app.ui.screens.MainScreen
import org.fetchx.app.ui.theme.FetchXTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by vm.settings.collectAsState()
            settings?.let { s ->
                FetchXTheme(s) { MainScreen(vm, s) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Clipboard access is only allowed while the app has focus.
        if (hasFocus) {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
            vm.checkClipboard(text)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_HANDLED, false)) return
        val link = when (intent.action) {
            Intent.ACTION_SEND -> LinkDetector.extractFirstLink(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        intent.putExtra(EXTRA_HANDLED, true)
        vm.showAdd(link)
    }

    private companion object {
        const val EXTRA_HANDLED = "fetchx.handled"
    }
}
