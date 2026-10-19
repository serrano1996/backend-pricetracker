package com.pricetracker.search.application

import com.pricetracker.search.domain.ProductType
import com.pricetracker.search.domain.StoreName
import java.util.UUID

data class TypeComparison(val type: ProductType, val offers: List<ComparedOffer>)

data class ComparedOffer(val store: StoreName, val product: ProductView)

/** "Where is it cheapest?" per product type, over the products of one search response (spec 004 RF-4, RF-5). */
object PriceComparator {

    fun compare(result: SearchResult, types: Map<UUID, ProductType>): List<TypeComparison> =
        result.stores
            .flatMap { store -> store.products.map { ComparedOffer(store.store, it) } }
            .filter { it.product.productTypeId != null && it.product.unitPrice != null }
            .groupBy { it.product.productTypeId }
            .mapNotNull { (typeId, offers) ->
                val type = types[typeId] ?: return@mapNotNull null
                val cheapestPerStore = offers
                    .filter { it.product.unitPriceUnit == type.unit }
                    .groupBy { it.store }
                    .map { (_, storeOffers) -> storeOffers.minBy { it.product.unitPrice!! } }
                    .sortedBy { it.product.unitPrice }
                if (cheapestPerStore.isEmpty()) null else TypeComparison(type, cheapestPerStore)
            }
            .sortedWith(compareByDescending<TypeComparison> { it.offers.size }.thenBy { it.type.name })
}
