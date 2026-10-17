package com.druk.llamacpp.tools

interface Tool {
    val name: String
    val description: String
    val parametersSchema: String
    fun execute(arguments: String): String

    /**
     * Abort any in-flight work (e.g. a blocking network request) so a Stop
     * during tool execution returns promptly. Default no-op for tools with
     * nothing to cancel (e.g. JavaScript).
     */
    fun cancelInFlight() {}
}
