package com.druk.llamacpp

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Cross-process parcelable mirroring the C++ `SamplerParams` struct.
 *
 * Sent over AIDL when the app creates a generation session in the
 * `:llama` process. Field names and meanings match the JNI signature
 * of `NativeLlamaModel.createSession`.
 */
@Parcelize
data class SamplerParams(
    val contextSize: Int,
    val temperature: Float,
    val topP: Float,
    val repetitionPenalty: Float,
    val topK: Int,
    val minP: Float,
    val seed: Int,
    val thinkingBudget: Int,
    val systemPrompt: String,
) : Parcelable
