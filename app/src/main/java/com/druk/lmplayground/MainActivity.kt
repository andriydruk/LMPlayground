package com.druk.lmplayground

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidViewBinding
import com.druk.lmplayground.databinding.ContentMainBinding

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContentView(
            ComposeView(this).apply {
                consumeWindowInsets = false
                setContent {
                    AndroidViewBinding(ContentMainBinding::inflate)
                }
            }
        )
    }
}
