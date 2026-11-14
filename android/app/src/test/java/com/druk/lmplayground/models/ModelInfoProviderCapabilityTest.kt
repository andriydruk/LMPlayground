package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoProviderCapabilityTest {

    private fun byName(name: String): ModelInfo =
        requireNotNull(ModelInfoProvider.allModels.find { it.name == name }) {
            "Model not found: $name"
        }

    @Test
    fun qwen3FamilySupportsToolsAndThinking() {
        listOf("Qwen 3 0.6B", "Qwen 3 4B", "Qwen 3.5 4B").forEach { name ->
            val model = byName(name)
            assertTrue("$name should support tools", model.supportsTools)
            assertTrue("$name should support thinking", model.supportsThinking)
        }
    }

    @Test
    fun gemmaInstructSupportsNeither() {
        listOf("Gemma 3 1B", "Gemma 3 4B", "Gemma 3n E2B", "Gemma2 9B").forEach { name ->
            val model = byName(name)
            assertFalse("$name should not support tools", model.supportsTools)
            assertFalse("$name should not support thinking", model.supportsThinking)
        }
    }

    @Test
    fun reasoningVariantsSupportThinking() {
        listOf(
            "DeepSeek R1 Distill 7B",
            "LFM2.5 1.2B Thinking",
            "Ministral 3 3B Reasoning",
        ).forEach { name ->
            assertTrue("$name should support thinking", byName(name).supportsThinking)
        }
    }

    @Test
    fun deepseekDistillSupportsThinkingButNotTools() {
        val model = byName("DeepSeek R1 Distill 1.5B")
        assertTrue(model.supportsThinking)
        assertFalse(model.supportsTools)
    }

    @Test
    fun toolOnlyModelsSupportToolsNotThinking() {
        listOf("Granite 4.0 Micro", "Qwen2.5 0.5B").forEach { name ->
            val model = byName(name)
            assertTrue("$name should support tools", model.supportsTools)
            assertFalse("$name should not support thinking", model.supportsThinking)
        }
    }

    @Test
    fun newReasoningFamiliesSupportToolsAndThinking() {
        listOf(
            "LFM2.5 2.6B", "SmolLM3 3B", "MiniCPM5 1B",
            "MiniCPM5 2B", "Granite 4.2 3B", "Spark-X2.5 4B",
        ).forEach { name ->
            val model = byName(name)
            assertTrue("$name should support tools", model.supportsTools)
            assertTrue("$name should support thinking", model.supportsThinking)
        }
    }

    /**
     * These three were flagged TOOL_CAPABLE, but their GGUF chat templates
     * report supports_tools=false, so the engine never enables tools for them
     * (the badge promised something the runtime would not do). Verified against
     * the shipped GGUFs' embedded templates by :model-harness.
     */
    @Test
    fun modelsWithoutTemplateToolSupportAreNotBadged() {
        listOf("Llama 3.2 1B", "Phi-4 mini", "Mistral 7B").forEach { name ->
            assertFalse("$name should not be badged as tool-capable", byName(name).supportsTools)
        }
    }

    @Test
    fun lfm25VlIsAVisionModelWithToolsButNoThinkingToggle() {
        listOf("LFM2.5 VL 450M", "LFM2.5 VL 1.6B", "LFM2.5 VL 3B").forEach { name ->
            val model = byName(name)
            assertTrue("$name should be a vision model", model.isVision)
            assertTrue("$name should support tools", model.supportsTools)
            // The templates advertise thinking; the models never use it.
            assertEquals(name, ThinkingMode.NONE, model.thinkingMode)
        }
    }

    @Test
    fun customModelHasNoCapabilities() {
        val custom = ModelInfoProvider.createCustomModelInfo(
            filename = "custom.gguf",
            name = "Custom",
            sizeBytes = 1_000L
        )
        assertFalse(custom.supportsTools)
        assertFalse(custom.supportsThinking)
    }
}
