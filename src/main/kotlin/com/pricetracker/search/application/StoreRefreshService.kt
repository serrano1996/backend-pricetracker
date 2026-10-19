package com.pricetracker.search.application

import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import com.pricetracker.search.domain.UnitPriceParser
import com.pricetracker.search.infrastructure.persistence.PriceHistoryEntity
import com.pricetracker.search.infrastructure.persistence.PriceHistoryRepository
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotEntity
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import com.pricetracker.search.infrastructure.persistence.SnapshotStatus
import com.pricetracker.search.infrastructure.persistence.StoreProductEntity
import com.pricetracker.search.infrastructure.persistence.StoreProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

/** Persists one store's scraper answer: products, price history and the search snapshot (spec 001 RF-6, RF-7, RF-10). */
@Service
class StoreRefreshService(
    private val storeProducts: StoreProductRepository,
    private val priceHistory: PriceHistoryRepository,
    private val snapshots: SearchSnapshotRepository,
    private val clock: Clock,
) {

    /** One transaction per store (plan 001 D4). [result] must be Found or NotServed: a failure never overwrites data. */
    @Transactional
    fun persist(store: StoreName, term: SearchTerm, postalCode: PostalCode, result: StoreSearchResult): SearchSnapshotEntity {
        require(result !is StoreSearchResult.Unavailable) { "unavailable results are never persisted" }
        val now = clock.instant()
        val (status, items) = when (result) {
            is StoreSearchResult.Found -> SnapshotStatus.OK to upsert(store, result, now)
            else -> SnapshotStatus.NOT_SERVED to emptyList()
        }
        val snapshot = snapshots.findByQueryTermAndPostalCodeAndStoreName(term.value, postalCode.value, store)
            ?: SearchSnapshotEntity(term.value, postalCode.value, store, status, now)
        snapshot.status = status
        snapshot.fetchedAt = now
        snapshot.items = items.toMutableList()
        return snapshots.save(snapshot)
    }

    /** Returns the products in the scraper's order, each once. */
    private fun upsert(store: StoreName, result: StoreSearchResult.Found, now: Instant): List<StoreProductEntity> {
        val offers = result.offers.distinctBy { it.externalId }
        val existing = storeProducts
            .findByStoreNameAndWarehouseAndExternalIdIn(store, result.warehouse, offers.map { it.externalId })
            .associateBy { it.externalId }
        val history = mutableListOf<PriceHistoryEntity>()
        val products = offers.map { offer ->
            val price = offer.price.setScale(2, RoundingMode.HALF_UP)
            val unitPrice = UnitPriceParser.parse(offer.priceFormat)
            val product = existing[offer.externalId]
            val changed = product == null || product.currentPrice.compareTo(price) != 0
            val saved = (product ?: newProduct(store, result.warehouse, offer, now)).apply {
                name = offer.name
                category = offer.category
                imageUrl = offer.imageUrl
                currentPrice = price
                priceFormat = offer.priceFormat
                this.unitPrice = unitPrice?.amount
                unitPriceUnit = unitPrice?.unit
                lastCheckedAt = now
            }.let(storeProducts::save)
            if (changed) history += PriceHistoryEntity(saved, price, unitPrice?.amount, now)
            saved
        }
        priceHistory.saveAll(history)
        return products
    }

    private fun newProduct(store: StoreName, warehouse: String, offer: StoreOffer, now: Instant) = StoreProductEntity(
        storeName = store, warehouse = warehouse, externalId = offer.externalId,
        name = offer.name, category = null, imageUrl = null, currentPrice = offer.price,
        priceFormat = null, unitPrice = null, unitPriceUnit = null, lastCheckedAt = now,
    )
}
