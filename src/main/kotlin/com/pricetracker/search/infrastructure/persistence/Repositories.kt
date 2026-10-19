package com.pricetracker.search.infrastructure.persistence

import com.pricetracker.search.domain.StoreName
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Query methods are added by the task that first needs them (T9, T10, T11).

interface StoreProductRepository : JpaRepository<StoreProductEntity, UUID> {
    fun findByStoreNameAndWarehouseAndExternalIdIn(
        storeName: StoreName,
        warehouse: String,
        externalIds: Collection<String>,
    ): List<StoreProductEntity>
}

interface PriceHistoryRepository : JpaRepository<PriceHistoryEntity, Long>

interface SearchSnapshotRepository : JpaRepository<SearchSnapshotEntity, UUID> {
    // Items fetched in the same query: callers read them outside any transaction (open-in-view is off)
    @EntityGraph(attributePaths = ["items"])
    fun findByQueryTermAndPostalCodeAndStoreName(queryTerm: String, postalCode: String, storeName: StoreName): SearchSnapshotEntity?
}

interface SearchMetricRepository : JpaRepository<SearchMetricEntity, Long>
