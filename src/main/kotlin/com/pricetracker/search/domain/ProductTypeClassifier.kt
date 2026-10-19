package com.pricetracker.search.domain

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** A comparable kind of product, e.g. "Leche entera" per L (spec 004 RF-1). Words are stored normalized. */
data class ProductType(
    val id: UUID,
    val name: String,
    val unit: String,
    val matchAll: List<String>,
    val matchNone: List<String>,
)

/** Deterministic keyword classifier (spec 004 RF-2, plan D2, D3). */
class ProductTypeClassifier(private val types: List<ProductType>) {

    /** The most specific type whose words all appear and whose unit matches; null on no match or a tie. */
    fun classify(name: String, unit: String?): ProductType? {
        if (unit == null) return null
        val words = normalize(name).split(' ').toSet()
        val candidates = types.filter { type ->
            type.unit == unit && words.containsAll(type.matchAll) && type.matchNone.none { it in words }
        }
        val best = candidates.maxOfOrNull { it.matchAll.size } ?: return null
        return candidates.singleOrNull { it.matchAll.size == best }
    }

    companion object {
        private val MARKS = Regex("\\p{M}+")
        private val NON_WORD = Regex("[^a-z0-9]+")

        /** Lowercase, no accents, words separated by single spaces: "Español, 1 l." → "espanol 1 l". */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
                .lowercase(Locale.ROOT).replace(NON_WORD, " ").trim()
    }
}
