package com.pricetracker.search.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoreNameTest {

    @Test
    fun `lists stores in response order with their database codes`() {
        assertEquals(listOf("mercadona", "alcampo", "dia"), StoreName.entries.map { it.code })
    }

    @Test
    fun `selection is every store when none is given`() {
        assertEquals(StoreName.entries.toSet(), StoreName.selection(null))
        assertEquals(StoreName.entries.toSet(), StoreName.selection(listOf("", " ")))
    }

    @Test
    fun `selection keeps the given stores, ignoring blanks and repeats`() {
        assertEquals(setOf(StoreName.DIA, StoreName.MERCADONA), StoreName.selection(listOf(" dia", "mercadona", "dia", "")))
    }

    @Test
    fun `selection rejects unknown stores`() {
        val error = assertFailsWith<InvalidSearchQueryException> { StoreName.selection(listOf("dia", "lidl")) }
        assertEquals("stores must be a comma-separated list of: mercadona, alcampo, dia", error.message)
    }
}
