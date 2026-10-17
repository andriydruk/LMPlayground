package com.druk.lmplayground.conversation

import com.druk.llamacpp.chat.ToolCallPairing

/**
 * Adapts the engine's ToolCallRecords to the Compose [ToolCallInfo] shown on
 * an assistant message. The call/result pairing itself lives in :llamacpp so
 * the harness exercises the same logic the app does.
 */
object ToolCallInfoMapper {

    fun buildToolCallInfoList(
        toolCallsJson: String,
        toolResultsJson: String,
        totalDurationMs: Long,
    ): List<ToolCallInfo> =
        ToolCallPairing.pair(toolCallsJson, toolResultsJson, totalDurationMs).map { record ->
            ToolCallInfo(
                name = record.name,
                arguments = record.arguments,
                result = record.result,
                durationMs = record.durationMs,
            )
        }
}
