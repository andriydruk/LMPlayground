package com.druk.lmplayground.inference

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.druk.lmplayground.R

/**
 * Invisible, no-history activity launched by the "Copy" / "Share" actions
 * on the inference foreground-service notification. It runs in the main
 * app process (unlike LlamaService) so it has a foreground context — which
 * the clipboard write and the share chooser both require — then finishes
 * immediately. The response text is carried in the launching intent's
 * extra; nothing is read from disk.
 */
class ResponseActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (!text.isNullOrEmpty()) {
            when (intent?.action) {
                ACTION_COPY -> copy(text)
                ACTION_SHARE -> share(text)
            }
        }
        finish()
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("response", text))
        // Android 13+ shows its own copy confirmation, so a toast there
        // would be a duplicate. Only toast on older versions.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.response_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(
            Intent.createChooser(send, getString(R.string.share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        const val ACTION_COPY = "com.druk.lmplayground.action.COPY_RESPONSE"
        const val ACTION_SHARE = "com.druk.lmplayground.action.SHARE_RESPONSE"
        const val EXTRA_TEXT = "com.druk.lmplayground.extra.RESPONSE_TEXT"
    }
}
