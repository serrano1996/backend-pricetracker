package com.pricetracker.search.application

import com.pricetracker.search.domain.ProductType
import com.pricetracker.search.domain.ProductTypeClassifier
import com.pricetracker.search.infrastructure.persistence.ProductRepository
import org.springframework.stereotype.Component
import java.util.UUID

/** Product types, read once: they only change through migrations, i.e. with a deploy (plan 004 D5). */
@Component
class ProductTypeCatalog(private val products: ProductRepository) {

    val types: Map<UUID, ProductType> by lazy {
        products.findAll().associate { p ->
            val id = requireNotNull(p.id)
            id to ProductType(id, p.canonicalName, p.unit, p.matchAll, p.matchNone)
        }
    }

    val classifier: ProductTypeClassifier by lazy { ProductTypeClassifier(types.values.toList()) }
}
