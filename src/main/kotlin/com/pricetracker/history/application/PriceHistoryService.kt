package com.pricetracker.history.application

import com.pricetracker.search.infrastructure.persistence.PriceHistoryEntity
import com.pricetracker.search.infrastructure.persistence.PriceHistoryRepository
import com.pricetracker.search.infrastructure.persistence.StoreProductEntity
import com.pricetracker.search.infrastructure.persistence.StoreProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class ProductNotFoundException(id: UUID) : RuntimeException("Product $id not found")

data class ProductHistory(val product: StoreProductEntity, val history: List<PriceHistoryEntity>)

/** Read-only, database-only view of one product's price changes (spec 005 RF-1, RF-2, RF-4). */
@Service
class PriceHistoryService(
    private val storeProducts: StoreProductRepository,
    private val priceHistory: PriceHistoryRepository,
) {
    @Transactional(readOnly = true)
    fun get(id: UUID): ProductHistory {
        val product = storeProducts.findById(id).orElseThrow { ProductNotFoundException(id) }
        return ProductHistory(product, priceHistory.findByStoreProductIdOrderByRecordedAtAsc(id))
    }
}
