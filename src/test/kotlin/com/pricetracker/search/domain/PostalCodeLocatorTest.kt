package com.pricetracker.search.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostalCodeLocatorTest {
    private val locator = PostalCodeLocator(
        mapOf(
            "50500" to Coordinates(41.9037, -1.7272), // Tarazona
            "42001" to Coordinates(41.7670, -2.4727), // Soria
            "50001" to Coordinates(41.6516, -0.8771), // Zaragoza
            "28001" to Coordinates(40.4255, -3.6834), // Madrid
        ),
    )

    @Test
    fun `orders candidates by distance, without the origin nor unknown postal codes`() {
        val nearest = locator.nearest("50500", listOf("28001", "50001", "99999", "50500", "42001", "50001"))

        assertEquals(listOf(NearbyPostalCode("42001", 64), NearbyPostalCode("50001", 76), NearbyPostalCode("28001", 232)), nearest)
    }

    @Test
    fun `an origin without coordinates has no neighbours`() {
        assertTrue(locator.nearest("99999", listOf("50001")).isEmpty())
    }

    @Test
    fun `seeds are the provincial capitals`() {
        assertEquals(setOf("42001", "50001", "28001"), locator.seeds)
    }
}
