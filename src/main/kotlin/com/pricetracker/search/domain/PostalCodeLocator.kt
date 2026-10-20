package com.pricetracker.search.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Coordinates(val latitude: Double, val longitude: Double)

/** A postal code and its distance to the searched one, in whole kilometres (spec 006). */
data class NearbyPostalCode(val postalCode: String, val distanceKm: Int)

/** Great-circle distances between Spanish postal codes (spec 006 RF-2). */
class PostalCodeLocator(private val coordinates: Map<String, Coordinates>) {

    /** Provincial capitals (`XX001`): tried before any served postal code is known nearby (spec 006 RF-3). */
    val seeds: Set<String> = coordinates.keys.filterTo(mutableSetOf()) { SEED.matches(it) }

    /** [candidates] nearest first, without [origin] nor postal codes without coordinates; empty when [origin] has none. */
    fun nearest(origin: String, candidates: Collection<String>): List<NearbyPostalCode> {
        val from = coordinates[origin] ?: return emptyList()
        return candidates.asSequence()
            .filter { it != origin }
            .distinct()
            .mapNotNull { code -> coordinates[code]?.let { code to distanceKm(from, it) } }
            .sortedBy { it.second }
            .map { (code, km) -> NearbyPostalCode(code, km.roundToInt()) }
            .toList()
    }

    private companion object {
        val SEED = Regex("[0-9]{2}001")
        const val EARTH_RADIUS_KM = 6371.0

        // Haversine
        fun distanceKm(a: Coordinates, b: Coordinates): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
        }
    }
}
