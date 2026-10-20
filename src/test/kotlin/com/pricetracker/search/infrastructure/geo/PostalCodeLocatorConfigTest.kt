package com.pricetracker.search.infrastructure.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class PostalCodeLocatorConfigTest {
    private val locator = PostalCodeLocatorConfig().postalCodeLocator()

    @Test
    fun `loads every province and finds the capitals nearest to a postal code`() {
        assertEquals(52, locator.seeds.size)
        assertEquals(listOf("42001", "50001", "26001"), locator.nearest("50500", locator.seeds).take(3).map { it.postalCode })
    }
}
