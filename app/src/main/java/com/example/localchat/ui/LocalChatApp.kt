package com.example.localchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.localchat.ChatViewModel

private enum class Screen { CHAT, MODELS, SETTINGS }

/**
 * Root composable: owns the ViewModel, the snackbar and the (tiny) navigation state.
 * Three screens are enough, so a plain enum is used instead of a navigation library.
 */
@Composable
fun LocalChatApp(vm: ChatViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    // Kept here so the half-typed message survives opening the settings or models screen.
    var draft by rememberSaveable { mutableStateOf("") }

    val generating by vm.generating.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()

    // One-shot messages from the ViewModel (download finished, errors, ...). They queue up in the snackbar.
    LaunchedEffect(vm) {
        vm.notices.collect { message ->
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }

    // A multi-GB download, a model load or a long answer must not be interrupted by the screen timing out.
    val keepScreenOn = generating || busy || downloads.isNotEmpty() || importing != null
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }

    when (screen) {
        Screen.CHAT -> ChatScreen(
            vm = vm,
            snackbar = snackbar,
            draft = draft,
            onDraftChange = { draft = it },
            onOpenModels = { screen = Screen.MODELS },
            onOpenSettings = { screen = Screen.SETTINGS },
        )

        Screen.MODELS -> ModelsScreen(
            vm = vm,
            snackbar = snackbar,
            onBack = { screen = Screen.CHAT },
        )

        Screen.SETTINGS -> SettingsScreen(
            vm = vm,
            onBack = { screen = Screen.CHAT },
            onApply = {
                vm.newChat()
                screen = Screen.CHAT
            },
        )
    }
}
