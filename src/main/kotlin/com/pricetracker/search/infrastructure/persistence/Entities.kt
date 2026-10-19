package com.pricetracker.search.infrastructure.persistence

import com.pricetracker.search.domain.StoreName
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// Schema: src/main/resources/db/migration/V1__init.sql. Entities are plain classes, never data classes (constitution #12).

@Entity
@Table(name = "store_products")
class StoreProductEntity(
    var storeName: StoreName,
    var warehouse: String,
    var externalId: String,
    @Column(name = "store_product_name") var name: String,
    var category: String?,
    var imageUrl: String?,
    var currentPrice: BigDecimal,
    var priceFormat: String?,
    var unitPrice: BigDecimal?,
    var unitPriceUnit: String?,
    var lastCheckedAt: Instant,
    // Null until cross-store matching exists (future spec); no ProductEntity until then
    var productId: UUID? = null,
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,
)

@Entity
@Table(name = "price_history")
class PriceHistoryEntity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "store_product_id")
    var storeProduct: StoreProductEntity,
    var price: BigDecimal,
    var unitPrice: BigDecimal?,
    var recordedAt: Instant,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

enum class SnapshotStatus(val code: String) { OK("ok"), NOT_SERVED("not_served") }

@Entity
@Table(name = "search_snapshots")
class SearchSnapshotEntity(
    var queryTerm: String,
    var postalCode: String,
    var storeName: StoreName,
    var status: SnapshotStatus,
    var fetchedAt: Instant,
    // Ordered as the scraper returned them; assigning a new list rewrites the rows (spec 001 RF-6)
    @ManyToMany
    @JoinTable(
        name = "search_snapshot_items",
        joinColumns = [JoinColumn(name = "snapshot_id")],
        inverseJoinColumns = [JoinColumn(name = "store_product_id")],
    )
    @OrderColumn(name = "position")
    var items: MutableList<StoreProductEntity> = mutableListOf(),
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,
)

@Entity
@Table(name = "search_metrics")
class SearchMetricEntity(
    var queryTerm: String,
    var postalCode: String,
    var searchCount: Long,
    var lastSearchedAt: Instant,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

@Converter(autoApply = true)
class StoreNameConverter : AttributeConverter<StoreName, String> {
    override fun convertToDatabaseColumn(attribute: StoreName?) = attribute?.code
    override fun convertToEntityAttribute(dbData: String?) = dbData?.let { code -> StoreName.entries.single { it.code == code } }
}

@Converter(autoApply = true)
class SnapshotStatusConverter : AttributeConverter<SnapshotStatus, String> {
    override fun convertToDatabaseColumn(attribute: SnapshotStatus?) = attribute?.code
    override fun convertToEntityAttribute(dbData: String?) = dbData?.let { code -> SnapshotStatus.entries.single { it.code == code } }
}
