package com.pricetracker.search.infrastructure.persistence

import com.pricetracker.search.domain.StoreName
import org.springframework.data.jpa.repository.EntityGraph
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import java.util.UUID

// Query methods are added by the task that first needs them (T9, T10, T11).

interface StoreProductRepository : JpaRepository<StoreProductEntity, UUID> {
    // SELECT ... FOR UPDATE: concurrent refreshes compare prices one after another, so history is never duplicated (spec 002 RF-7)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
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
