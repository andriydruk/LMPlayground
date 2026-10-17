package com.druk.lmplayground.conversation

import android.app.Application
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.R

/**
 * Updates the foreground-service notification to reflect the current
 * inference state (loaded / generating / ready).
 *
 * [modelLine] / [modelName] are captured at load time and reused across
 * load -> generating -> ready transitions, so the silent notification
 * reflects what the model is currently doing without ever re-posting
 * noisily. The "loaded" state shows the size line ("<name> - <size>");
 * generating/ready swap in a live token count ("<name> · <N> tokens"),
 * so the model name is kept too.
 */
class InferenceNotificationUpdater(
    private val app: Application,
    private val llamaCpp: LlamaCpp?,
) {
    var modelLine: String? = null
    var modelName: String? = null

    /**
     * Silently flip the foreground-service notification. [text] defaults to
     * the cached "<name> - <size>" line used by the loaded state;
     * generating/ready pass a token-count line. No-op until a model has been
     * loaded. Safe to call from any thread — the underlying AIDL call
     * swallows binder failures.
     */
    fun update(
        titleRes: Int,
        text: String? = modelLine,
        actionBody: String? = null,
    ) {
        if (text == null) return
        llamaCpp?.setForegroundContent(app.getString(titleRes), text, actionBody)
    }

    /** "<name> · <N> tokens" for the generating/ready notification line. */
    fun tokensLine(tokens: Int): String? {
        val name = modelName ?: return null
        val tokenStr = app.resources.getQuantityString(
            R.plurals.inference_notification_tokens, tokens, tokens
        )
        return app.getString(
            R.string.inference_notification_tokens_line, name, tokenStr
        )
    }
}
