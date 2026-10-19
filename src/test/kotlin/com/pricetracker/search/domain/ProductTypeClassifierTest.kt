package com.pricetracker.search.domain

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductTypeClassifierTest {

    private fun type(name: String, unit: String, all: List<String>, none: List<String> = emptyList()) =
        ProductType(UUID.randomUUID(), name, unit, all, none)

    private val entera = type("Leche entera", "L", listOf("leche", "entera"), listOf("lactosa"))
    private val desnatada = type("Leche desnatada", "L", listOf("leche", "desnatada"))
    private val oliva = type("Aceite de oliva", "L", listOf("aceite", "oliva"), listOf("virgen"))
    private val oliveVe = type("Aceite de oliva virgen extra", "L", listOf("aceite", "oliva", "virgen", "extra"))
    private val basmati = type("Arroz basmati", "kg", listOf("arroz", "basmati"))
    private val integral = type("Arroz integral", "kg", listOf("arroz", "integral"))
    private val classifier = ProductTypeClassifier(listOf(entera, desnatada, oliva, oliveVe, basmati, integral))

    @Test
    fun `normalizes case, accents and punctuation`() {
        assertEquals("leche entera de origen espanol 1 l", ProductTypeClassifier.normalize("LECHE Entera, de origen español 1 l."))
    }

    @Test
    fun `matches when every required word is present, in any order`() {
        assertEquals(entera, classifier.classify("CENTRAL LECHERA ASTURIANA Leche entera botella 6 x 1 l.", "L"))
        assertEquals(entera, classifier.classify("Leche de vaca entera de origen español", "L"))
    }

    @Test
    fun `matches whole words only`() {
        assertNull(classifier.classify("Leche semidesnatada Hacendado", "L"))
    }

    @Test
    fun `an excluded word rules a type out`() {
        assertNull(classifier.classify("Leche entera sin lactosa", "L"))
    }

    @Test
    fun `the most specific matching type wins`() {
        assertEquals(oliveVe, classifier.classify("Aceite de oliva virgen extra Carbonell 1 L", "L"))
        assertEquals(oliva, classifier.classify("CARBONELL Aceite de oliva suave botella 1 l.", "L"))
    }

    @Test
    fun `a tie between equally specific types gives no type`() {
        assertNull(classifier.classify("TILDA Arroz basmati integral 1 kg", "kg"))
    }

    @Test
    fun `the product unit must match the type unit`() {
        assertNull(classifier.classify("Polvorones con aceite de oliva 310 g", "kg"))
        assertNull(classifier.classify("Leche entera Hacendado", null))
    }
}
