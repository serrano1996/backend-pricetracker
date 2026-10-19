package com.pricetracker.search.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class UnitPrice(val amount: BigDecimal, val unit: String)

/**
 * Parses the scrapers' `price_format` ("<amount> €/<unit>", e.g. "1.05 €/L", "0.25 €/100 g").
 * Per-100 units are converted to kg / L so the same product compares equally across stores.
 */
object UnitPriceParser {
    private val FORMAT = Regex("""\s*(\d+(?:[.,]\d+)?)\s*€/\s*(.+?)\s*""")
    private val PER_HUNDRED_TO_BASE = mapOf("100 g" to "kg", "100 ml" to "L")

    fun parse(text: String?): UnitPrice? {
        val match = FORMAT.matchEntire(text ?: return null) ?: return null
        val (number, unit) = match.destructured
        val amount = BigDecimal(number.replace(',', '.'))
        val base = PER_HUNDRED_TO_BASE[unit.lowercase()]
        return if (base == null) {
            UnitPrice(amount.setScale(2, RoundingMode.HALF_UP), unit)
        } else {
            UnitPrice(amount.multiply(BigDecimal.TEN).setScale(2, RoundingMode.HALF_UP), base)
        }
    }
}
