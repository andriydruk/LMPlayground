package com.druk.llamacpp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinkStoreTest {

    @Test
    fun referencesAreCompactAndSequential() {
        val store = WebLinkStore()
        assertEquals("ddg:1", store.reference("https://example.com/a"))
        assertEquals("ddg:2", store.reference("https://example.com/b"))
    }

    @Test
    fun resolveReturnsRealUrlAndTrims() {
        val store = WebLinkStore()
        val ref = store.reference("https://example.com/long/path?utm=duckduckgo&rut=abc")
        assertEquals("https://example.com/long/path?utm=duckduckgo&rut=abc", store.resolve(ref))
        assertEquals("https://example.com/long/path?utm=duckduckgo&rut=abc", store.resolve(" $ref "))
    }

    @Test
    fun resolveUnknownIsNull() {
        assertNull(WebLinkStore().resolve("ddg:99"))
    }

    @Test
    fun isReferenceDetectsTokens() {
        val store = WebLinkStore()
        assertTrue(store.isReference("ddg:3"))
        assertTrue(store.isReference("  ddg:3  "))
        assertFalse(store.isReference("https://duckduckgo.com/page"))
    }

    @Test
    fun oldestEvictedBeyondCapacity() {
        val store = WebLinkStore(maxEntries = 2)
        val r1 = store.reference("https://a")
        store.reference("https://b")
        store.reference("https://c") // exceeds capacity -> evicts r1
        assertNull(store.resolve(r1))
        assertEquals("https://c", store.resolve("ddg:3"))
    }
}
