package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSearchServiceTest {
    @Test
    fun parseResponsePrefersSummaryAndFiltersInvalidUrls() {
        val result = DeepSeekSearchService.parseResponse(
            """
            {
              "results": [
                {
                  "title": "First result",
                  "url": "https://example.com/first",
                  "snippet": "Short snippet",
                  "summary": "Long summary",
                  "content": "Full content"
                },
                {
                  "title": "",
                  "url": "https://example.com/second",
                  "snippet": "Second snippet",
                  "summary": "",
                  "content": "Second content"
                },
                {
                  "title": "Duplicate",
                  "url": "https://example.com/first",
                  "snippet": "Duplicate",
                  "summary": "Duplicate",
                  "content": ""
                },
                {
                  "title": "No URL",
                  "url": "",
                  "snippet": "No URL",
                  "summary": "No URL",
                  "content": ""
                }
              ]
            }
            """.trimIndent(),
            limit = 10,
        )

        assertEquals(2, result.items.size)
        assertEquals("First result", result.items[0].title)
        assertEquals("Long summary", result.items[0].text)
        assertEquals("https://example.com/second", result.items[1].title)
        assertEquals("Second snippet", result.items[1].text)
    }

    @Test
    fun parseResponseRespectsLimit() {
        val result = DeepSeekSearchService.parseResponse(
            """
            {
              "results": [
                {"title": "A", "url": "https://example.com/a", "summary": "A"},
                {"title": "B", "url": "https://example.com/b", "summary": "B"}
              ]
            }
            """.trimIndent(),
            limit = 1,
        )

        assertEquals(1, result.items.size)
        assertEquals("https://example.com/a", result.items.single().url)
    }

    @Test
    fun parsesEmptyResults() {
        val result = DeepSeekSearchService.parseResponse("""{"results":[]}""", limit = 10)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun normalizesSearchEndpoint() {
        val expected = "https://api.deepseek.com/v1/tools/search"
        assertEquals(expected, DeepSeekSearchService.searchEndpoint(""))
        assertEquals(expected, DeepSeekSearchService.searchEndpoint("https://api.deepseek.com"))
        assertEquals(expected, DeepSeekSearchService.searchEndpoint("https://api.deepseek.com/v1"))
        assertEquals(expected, DeepSeekSearchService.searchEndpoint("https://api.deepseek.com/anthropic/v1"))
        assertEquals(expected, DeepSeekSearchService.searchEndpoint("https://api.deepseek.com/v1/tools/search"))
        assertEquals(
            "https://proxy.example.com/v1/tools/search",
            DeepSeekSearchService.searchEndpoint("https://proxy.example.com/"),
        )
    }
}
