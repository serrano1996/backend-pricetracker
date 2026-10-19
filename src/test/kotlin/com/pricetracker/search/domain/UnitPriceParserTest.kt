package com.pricetracker.search.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnitPriceParserTest {

    private fun parse(text: String?) = UnitPriceParser.parse(text)

    @Test
    fun `parses the scrapers format with dot or comma decimals`() {
        assertEquals(UnitPrice(BigDecimal("1.05"), "L"), parse("1.05 €/L"))
        assertEquals(UnitPrice(BigDecimal("0.95"), "L"), parse("0,95 €/L"))
        assertEquals(UnitPrice(BigDecimal("2.10"), "kg"), parse("2.1 €/kg"))
        assertEquals(UnitPrice(BigDecimal("0.30"), "ud"), parse("0.3 €/ud"))
    }

    @Test
    fun `normalizes per-100 units to kg and L so prices are comparable`() {
        assertEquals(UnitPrice(BigDecimal("2.50"), "kg"), parse("0.25 €/100 g"))
        assertEquals(UnitPrice(BigDecimal("1.23"), "L"), parse("0.123 €/100 ml"))
    }

    @Test
    fun `keeps other units as given`() {
        assertEquals(UnitPrice(BigDecimal("2.40"), "docena"), parse("2.4 €/docena"))
        assertEquals(UnitPrice(BigDecimal("0.15"), "lavado"), parse("0.15 €/lavado"))
    }

    @Test
    fun `rounds half up to cents`() {
        assertEquals(BigDecimal("1.06"), parse("1.055 €/kg")?.amount)
    }

    @Test
    fun `returns null when the text is missing or not in the expected format`() {
        listOf(null, "", "oferta", "1.05 €", "€/kg", "abc €/kg").forEach { assertNull(parse(it), it) }
    }
}
