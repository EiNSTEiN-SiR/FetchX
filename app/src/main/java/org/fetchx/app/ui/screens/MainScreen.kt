package org.fetchx.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fetchx.app.data.AppSettings
import org.fetchx.app.ui.AppViewModel

private enum class Tab(val label: String) { Active("Active"), Completed("Completed"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, settings: AppSettings) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var browserOpen by rememberSaveable { mutableStateOf(false) }
    val tab = Tab.entries[tabIndex]
    val snackbar = remember { SnackbarHostState() }
    val add by vm.add.collectAsState()
    val offer by vm.clipboardOffer.collectAsState()

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(offer) {
        val link = offer ?: return@LaunchedEffect
        val shown = if (link.length > 60) link.take(57) + "…" else link
        val result = snackbar.showSnackbar("Link in clipboard: $shown", actionLabel = "Add")
        vm.clearClipboardOffer()
        if (result == SnackbarResult.ActionPerformed) vm.showAdd(link)
    }

    if (browserOpen) {
        BrowserScreen(vm, onClose = { browserOpen = false })
    } else Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    when (tab) {
                        Tab.Active -> {
                            IconButton(onClick = { browserOpen = true }) { Icon(Icons.Default.Public, "Browser") }
                            IconButton(onClick = vm::resumeAll) { Icon(Icons.Default.PlayArrow, "Resume all") }
                            IconButton(onClick = vm::pauseAll) { Icon(Icons.Default.Pause, "Pause all") }
                        }
                        Tab.Completed -> IconButton(onClick = vm::clearFinished) {
                            Icon(Icons.Default.DeleteSweep, "Clear list")
                        }
                        Tab.Settings -> {}
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Active -> Icons.Default.Download
                                    Tab.Completed -> Icons.Default.CheckCircle
                                    Tab.Settings -> Icons.Default.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab != Tab.Settings) {
                FloatingActionButton(
                    onClick = { vm.showAdd() },
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                ) { Icon(Icons.Default.Add, "Add download") }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Active -> ActiveScreen(vm)
                Tab.Completed -> CompletedScreen(vm)
                Tab.Settings -> SettingsScreen(vm, settings)
            }
        }
    }

    if (add != null) AddSheet(vm, add!!)
}
