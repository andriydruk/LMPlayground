package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoProviderLanguageTest {

    private fun byName(name: String): ModelInfo =
        requireNotNull(ModelInfoProvider.allModels.find { it.name == name }) {
            "Model not found: $name"
        }

    @Test
    fun allCatalogModelsDeclareSupportedLanguages() {
        val missing = ModelInfoProvider.allModels.filter { it.supportedLanguages.isEmpty() }
        assertTrue(
            "Catalog models without supportedLanguages: ${missing.map { it.name }}",
            missing.isEmpty()
        )
    }

    @Test
    fun llamaFamilyHasEightLanguages() {
        val expected = setOf("en", "de", "fr", "it", "pt", "hi", "es", "th")
        listOf("Llama 3.1 8B", "Llama 3.2 1B", "Llama 3.2 3B").forEach { name ->
            assertEquals(name, expected, byName(name).supportedLanguages.toSet())
        }
    }

    @Test
    fun gemma2IsEnglishOnly() {
        assertEquals(listOf("en"), byName("Gemma2 9B").supportedLanguages)
    }

    @Test
    fun nemotronNanoIsEnglishOnly() {
        assertEquals(listOf("en"), byName("Nemotron 3 Nano 4B").supportedLanguages)
    }

    @Test
    fun deepseekDistillIsEnZh() {
        assertEquals(
            setOf("en", "zh"),
            byName("DeepSeek R1 Distill 7B").supportedLanguages.toSet()
        )
    }

    @Test
    fun qwen3IsBroadlyMultilingual() {
        val qwen3 = byName("Qwen 3 4B").supportedLanguages
        assertTrue(qwen3.contains("en"))
        assertTrue(qwen3.contains("zh"))
        assertTrue(qwen3.contains("hi"))
        assertTrue(qwen3.contains("uk"))
    }

    @Test
    fun smolLm3DeclaresSixNativeLanguages() {
        assertEquals(
            setOf("en", "fr", "es", "de", "it", "pt"),
            byName("SmolLM3 3B").supportedLanguages.toSet()
        )
    }

    @Test
    fun miniCpm5IsEnZh() {
        assertEquals(setOf("en", "zh"), byName("MiniCPM5 1B").supportedLanguages.toSet())
        assertEquals(setOf("en", "zh"), byName("MiniCPM5 2B").supportedLanguages.toSet())
    }

    @Test
    fun lfm25LargeIsBroaderThanEarlierSizes() {
        val large = byName("LFM2.5 2.6B").supportedLanguages
        val small = byName("LFM2.5 350M").supportedLanguages
        assertTrue(large.containsAll(small))
        assertTrue(large.contains("hi"))
        assertTrue(large.contains("vi"))
        assertFalse(small.contains("hi"))
    }

    @Test
    fun supportsLanguageReturnsTrueForEmptyList() {
        val custom = ModelInfoProvider.createCustomModelInfo(
            filename = "custom.gguf",
            name = "Custom",
            sizeBytes = 1_000L
        )
        assertTrue(custom.supportedLanguages.isEmpty())
        assertTrue(custom.supportsLanguage("de"))
        assertTrue(custom.supportsLanguage("xx"))
    }

    @Test
    fun supportsLanguageReturnsFalseWhenNotListed() {
        val gemma2 = byName("Gemma2 9B")
        assertTrue(gemma2.supportsLanguage("en"))
        assertFalse(gemma2.supportsLanguage("de"))
    }
}
