package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JavaScriptTool(private val context: Context) : Tool {
    override val name = "run_javascript"
    override val description = "Run JavaScript in a sandboxed V8 isolate; returns the last expression's value. Use for math, date and time (`new Date()`), strings, JSON, and regex. No network, filesystem, or DOM."
    override val parametersSchema = """{"type":"object","properties":{"code":{"type":"string","description":"JavaScript source; the last expression's value is returned. E.g. '(2 + 3) * 4', 'new Date().toString()'."}},"required":["code"]}"""

    override fun execute(arguments: String): String {
        return try {
            if (!JavaScriptSandbox.isSupported()) {
                return """{"error":"JavaScript engine not supported on this device"}"""
            }
            val args = JSONObject(arguments)
            val code = args.getString("code")

            val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context)
                .get(5, TimeUnit.SECONDS)
            try {
                val startupParams = IsolateStartupParameters()
                if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                    startupParams.setMaxHeapSizeBytes(16L * 1024 * 1024)
                }
                val isolate = sandbox.createIsolate(startupParams)
                try {
                    // AndroidX JS engine returns empty string unless the code explicitly
                    // produces a String value. Wrap with String() to capture the result
                    // of the last expression, using eval() to handle multi-statement code.
                    // Pass the user code to eval() as a JSON-quoted string literal rather
                    // than a template literal: a template literal would run `${...}`
                    // interpolation against the wrong scope and collide with backticks in
                    // the user code, breaking any code that uses template strings.
                    val wrapped = "String(eval(${JSONObject.quote(code)}))"
                    val result = isolate.evaluateJavaScriptAsync(wrapped)
                        .get(10, TimeUnit.SECONDS)
                    """{"result":${JSONObject.quote(result)}}"""
                } finally {
                    isolate.close()
                }
            } finally {
                sandbox.close()
            }
        } catch (e: Exception) {
            val message = (e.cause?.message ?: e.message ?: "Execution failed")
                .replace("\"", "'")
            """{"error":"$message"}"""
        }
    }
}
