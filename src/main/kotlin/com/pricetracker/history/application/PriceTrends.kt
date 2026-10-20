package com.pricetracker.history.application

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** A product's last price change: the price before the current one, and when the current one started (spec 011 RF-1). */
data class PriceTrend(val previousPrice: BigDecimal, val changedAt: Instant)

@Component
class PriceTrends(private val jdbc: NamedParameterJdbcTemplate) {

    /** One query for every product: history only gets a row on a price change, so its last two rows are that change. */
    fun of(ids: Collection<UUID>): Map<UUID, PriceTrend> {
        if (ids.isEmpty()) return emptyMap()
        val rows = jdbc.query(
            """
            select store_product_id, price, recorded_at from (
                select store_product_id, price, recorded_at,
                       row_number() over (partition by store_product_id order by recorded_at desc) as rn
                from price_history
                where store_product_id in (:ids)
            ) h
            where rn <= 2
            order by store_product_id, rn
            """,
            mapOf("ids" to ids),
        ) { rs, _ ->
            Triple(
                rs.getObject("store_product_id", UUID::class.java),
                rs.getBigDecimal("price"),
                rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
            )
        }
        return rows.groupBy { it.first }
            .filterValues { it.size == 2 && it[0].second.compareTo(it[1].second) != 0 }
            .mapValues { (_, latest) -> PriceTrend(previousPrice = latest[1].second, changedAt = latest[0].third) }
    }
}
