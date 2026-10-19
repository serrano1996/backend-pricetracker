package com.pricetracker.history.web

import com.pricetracker.history.application.PriceHistoryService
import com.pricetracker.history.application.ProductHistory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/products")
class PriceHistoryController(private val service: PriceHistoryService) {

    /** A non-UUID id is rejected by Spring's conversion and answered 400 by ApiExceptionHandler (spec 005 RF-3). */
    @GetMapping("/{id}/price-history")
    fun priceHistory(@PathVariable id: UUID): PriceHistoryResponse = PriceHistoryResponse.from(service.get(id))
}

// Public contract of GET /api/v1/products/{id}/price-history (spec 005 "Formato de respuesta").

data class PriceHistoryResponse(val product: ProductResponse, val history: List<PricePointResponse>) {
    companion object {
        fun from(h: ProductHistory) = PriceHistoryResponse(
            product = with(h.product) {
                ProductResponse(
                    id = requireNotNull(id), store = storeName.code, externalId = externalId, name = name,
                    currentPrice = currentPrice, priceFormat = priceFormat, unitPrice = unitPrice,
                    unitPriceUnit = unitPriceUnit, imageUrl = imageUrl, category = category, lastCheckedAt = lastCheckedAt,
                )
            },
            history = h.history.map { PricePointResponse(it.price, it.unitPrice, it.recordedAt) },
        )
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ProductResponse(
    val id: UUID,
    val store: String,
    val externalId: String,
    val name: String,
    val currentPrice: BigDecimal,
    val priceFormat: String?,
    val unitPrice: BigDecimal?,
    val unitPriceUnit: String?,
    val imageUrl: String?,
    val category: String?,
    val lastCheckedAt: Instant,
)

/** Start of a price: the price at a given date is the last point before it. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PricePointResponse(val price: BigDecimal, val unitPrice: BigDecimal?, val recordedAt: Instant)
