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
        assertEquals(100, SearchTerm.of(" " + "ab".repeat(50) + " ").value.length)
        assertFailsWith<InvalidSearchQueryException> { SearchTerm.of("ab".repeat(50) + "a") }
    }

    @Test
    fun `counts code points, not UTF-16 units, like the database check`() {
        val letters = "𝒜𝒷" // mathematical script letters, 2 UTF-16 units each
        assertEquals(100, SearchTerm.of(letters.repeat(50)).value.codePointCount(0, 200))
    }

    @Test
    fun `accepts real grocery terms`() {
        listOf(
            "leche", "té", "7up", "coca-cola 2l", "aceite de oliva 0,4%", "m&m's", "schweppes", "construcción",
            "rhythm", "xxl", "café molido 250 g", "ñoquis", "agua 1.5/l", "zumo + fibra",
        ).forEach { SearchTerm.of(it) }
    }

    @Test
    fun `rejects nonsense terms before they reach any scraper`() {
        listOf(
            "a", "1", "12345", "!!", "🥛🥛", "<script>", "leche;drop", "http://x.com", "aaaa", "lecheeee",
            "qwrtzp", "xkcdbbq",
        ).forEach { raw ->
            assertFailsWith<InvalidSearchQueryException>(raw) { SearchTerm.of(raw) }
        }
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
