package com.pricetracker.search.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchInputTest {

    @Test
    fun `normalizes term by trimming, lowercasing and collapsing inner whitespace`() {
        assertEquals("leche entera", SearchTerm.of(" Leche  Entera ").value)
        assertEquals("leche entera", SearchTerm.of("leche \tentera").value)
        assertEquals(SearchTerm.of("leche entera"), SearchTerm.of(" LECHE   entera"))
    }

    @Test
    fun `keeps accents in term`() {
        assertEquals("plátano de canarias", SearchTerm.of("Plátano de Canarias").value)
    }

    @Test
    fun `rejects blank term`() {
        assertFailsWith<InvalidSearchQueryException> { SearchTerm.of("   ") }
    }

    @Test
    fun `accepts 100 characters and rejects 101 after normalization`() {
        assertEquals(100, SearchTerm.of(" " + "a".repeat(100) + " ").value.length)
        assertFailsWith<InvalidSearchQueryException> { SearchTerm.of("a".repeat(101)) }
    }

    @Test
    fun `counts code points, not UTF-16 units, like the database check`() {
        val emoji = "🥛" // glass of milk, 2 UTF-16 units
        assertEquals(100, SearchTerm.of(emoji.repeat(100)).value.codePointCount(0, 200))
    }

    @Test
    fun `accepts five ascii digits as postal code, trimmed`() {
        assertEquals("28001", PostalCode.of(" 28001 ").value)
    }

    @Test
    fun `rejects malformed postal codes`() {
        listOf("2800", "280011", "28a01", "", "２８００１").forEach { raw ->
            assertFailsWith<InvalidSearchQueryException>(raw) { PostalCode.of(raw) }
        }
    }
}
