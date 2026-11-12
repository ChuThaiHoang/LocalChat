package com.example.localchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.localchat.ui.LocalChatApp
import com.example.localchat.ui.theme.LocalChatTheme

/** Single activity: all screens are Compose, see [LocalChatApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalChatTheme {
                LocalChatApp()
            }
        }
    }
}
