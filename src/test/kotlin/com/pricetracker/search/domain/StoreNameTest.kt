package com.pricetracker.search.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class StoreNameTest {

    @Test
    fun `lists stores in response order with their database codes`() {
        assertEquals(listOf("mercadona", "alcampo", "dia"), StoreName.entries.map { it.code })
    }
}
