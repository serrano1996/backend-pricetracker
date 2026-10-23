package com.pricetracker.search.application

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import com.pricetracker.search.domain.StoreOffer
import com.pricetracker.search.domain.StoreSearchResult
import com.pricetracker.search.infrastructure.persistence.PriceHistoryRepository
import com.pricetracker.search.infrastructure.persistence.SearchSnapshotRepository
import com.pricetracker.search.infrastructure.persistence.SnapshotStatus
import com.pricetracker.search.infrastructure.persistence.StoreProductRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class StoreRefreshServiceTest(
    @Autowired private val service: StoreRefreshService,
    @Autowired private val storeProducts: StoreProductRepository,
    @Autowired private val priceHistory: PriceHistoryRepository,
    @Autowired private val snapshots: SearchSnapshotRepository,
    @Autowired private val em: EntityManager,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val term = SearchTerm.of("leche")
    private val cp = PostalCode.of("28001")

    private fun offer(id: String, price: String, format: String? = "$price €/L") =
        StoreOffer(id, "Leche $id", BigDecimal(price), format, "https://img/$id.jpg", "Leche")

    private fun persist(vararg offers: StoreOffer, store: StoreName = StoreName.MERCADONA, warehouse: String = "mad1") {
        service.persist(store, term, cp, StoreSearchResult.Found(warehouse, offers.toList()))
        em.flush()
        em.clear()
    }

    private fun historyPrices() = priceHistory.findAll().sortedBy { it.id }.map { it.price.toPlainString() }

    @Test
    fun `new product is stored with parsed unit price and one history entry`() {
        persist(offer("1", "0.95", "0.25 €/100 g"))

        val product = storeProducts.findAll().single()
        assertEquals(StoreName.MERCADONA, product.storeName)
        assertEquals("mad1", product.warehouse)
        assertEquals(BigDecimal("0.95"), product.currentPrice)
        assertEquals(BigDecimal("2.50"), product.unitPrice)
        assertEquals("kg", product.unitPriceUnit)
        assertEquals(listOf("0.95"), historyPrices())
    }

    @Test
    fun `same price adds no history, a different price adds one`() {
        persist(offer("1", "0.95"))
        persist(offer("1", "0.95"))
        assertEquals(listOf("0.95"), historyPrices())

        persist(offer("1", "0.99"))

        assertEquals(listOf("0.95", "0.99"), historyPrices())
        assertEquals(BigDecimal("0.99"), storeProducts.findAll().single().currentPrice)
    }

    @Test
    fun `prices are rounded half up to cents before comparing`() {
        persist(offer("1", "1.055"))
        persist(offer("1", "1.0551"))

        assertEquals(listOf("1.06"), historyPrices())
    }

    @Test
    fun `same external id in another warehouse or store is another product`() {
        persist(offer("1", "0.95"))
        persist(offer("1", "0.97"), warehouse = "bcn1")
        persist(offer("1", "0.99"), store = StoreName.DIA)

        assertEquals(3, storeProducts.count())
    }

    @Test
    fun `duplicate offers in one response are stored once, at their first position`() {
        persist(offer("b", "2.00"), offer("a", "1.00"), offer("b", "2.00"))

        assertEquals(2, storeProducts.count())
        assertEquals(listOf("b", "a"), snapshots.findAll().single().items.map { it.externalId })
    }

    @Test
    fun `snapshot is replaced in place with the new order and fetch time`() {
        persist(offer("a", "1.00"), offer("b", "2.00"))
        // Copied out: the shared test transaction hands persist() this same managed instance
        val (firstId, firstFetchedAt) = snapshots.findAll().single().let { it.id to it.fetchedAt }

        persist(offer("c", "3.00"), offer("a", "1.00"))

        val second = snapshots.findAll().single()
        assertEquals(firstId, second.id)
        assertEquals(SnapshotStatus.OK, second.status)
        assertEquals(listOf("c", "a"), second.items.map { it.externalId })
        assertTrue(!second.fetchedAt.isBefore(firstFetchedAt))
    }

    @Test
    fun `empty result is stored as a fresh empty snapshot`() {
        persist()

        val snapshot = snapshots.findAll().single()
        assertEquals(SnapshotStatus.OK, snapshot.status)
        assertEquals(emptyList(), snapshot.items)
    }

    @Test
    fun `not served is stored as a not_served snapshot without items`() {
        service.persist(StoreName.ALCAMPO, term, cp, StoreSearchResult.NotServed)
        em.flush()
        em.clear()

        val snapshot = snapshots.findAll().single()
        assertEquals(StoreName.ALCAMPO, snapshot.storeName)
        assertEquals(SnapshotStatus.NOT_SERVED, snapshot.status)
        assertEquals(emptyList(), snapshot.items)
    }

    private fun typeOf(externalId: String): String? = jdbc.queryForList(
        "select p.canonical_name from store_products s left join products p on p.id = s.product_id where s.external_id = ?",
        String::class.java, externalId,
    ).single()

    @Test
    fun `assigns product types to real product names (spec 004 edge cases)`() {
        val expected = linkedMapOf(
            StoreOffer("1", "CENTRAL LECHERA ASTURIANA Leche entera botella 6 x 1 l.", BigDecimal("7.56"), "1.26 €/L", "i", "c") to "Leche entera",
            StoreOffer("2", "Leche semidesnatada Hacendado", BigDecimal("4.98"), "0.83 €/L", "i", "c") to "Leche semidesnatada",
            StoreOffer("3", "Galletas cubiertas de chocolate con leche Galleteca de Dia 150 g", BigDecimal("1.20"), "8.00 €/kg", "i", "c") to null,
            StoreOffer("4", "Leche desnatada sin lactosa Dia Láctea 1 L", BigDecimal("0.99"), "0.99 €/L", "i", "c") to null,
            StoreOffer("5", "Aceite de oliva virgen extra en spray Carbonell 200 ml", BigDecimal("3.29"), "16.45 €/L", "i", "c") to null,
            StoreOffer("6", "CARBONELL Aceite de oliva suave botella 1 l.", BigDecimal("5.24"), "5.24 €/L", "i", "c") to "Aceite de oliva",
            StoreOffer("7", "Aceite de oliva virgen extra Carbonell 1 L", BigDecimal("7.69"), "7.69 €/L", "i", "c") to "Aceite de oliva virgen extra",
            StoreOffer("8", "TILDA Arroz basmati integral 1 kg", BigDecimal("3.10"), "3.10 €/kg", "i", "c") to null,
            StoreOffer("9", "1880 Polvorones de almendra con aceite de oliva y harina integral, vegan 310 g.", BigDecimal("3.00"), "9.68 €/kg", "i", "c") to null,
            StoreOffer("10", "LA FALLERA Arroz redondo paquete de 1 kg.", BigDecimal("1.74"), "1.74 €/kg", "i", "c") to "Arroz redondo",
            StoreOffer("11", "Aceite de girasol Koipe 1 L", BigDecimal("2.85"), null, "i", "c") to null, // no unit price
            // False positives found with real data after V2, fixed in V3
            StoreOffer("12", "Vasos de arroz redondo Brillante 2 x 200 g", BigDecimal("1.99"), "4.98 €/kg", "i", "c") to null,
            StoreOffer("13", "Arroz cocido redondo Sabroz", BigDecimal("1.60"), "4.00 €/kg", "i", "c") to null,
            // V4 basic groceries: live names (2026-10-20, CP 28001), price fields simplified
            StoreOffer("14", "Pan de molde blanco Hacendado", BigDecimal("1.74"), "1.74 €/kg", "i", "c") to "Pan de molde",
            StoreOffer("15", "PRODUCTO ALCAMPO Pan de molde blanco con corteza 1000 g.", BigDecimal("1.40"), "1.40 €/kg", "i", "c") to "Pan de molde",
            StoreOffer("16", "Pan de molde blanco sin corteza El Molino de Dia 450 g", BigDecimal("1.05"), "2.33 €/kg", "i", "c") to null,
            StoreOffer("17", "Pan de molde semillas y pipas de calabaza Hacendado", BigDecimal("2.67"), "2.67 €/kg", "i", "c") to null,
            StoreOffer("18", "Pan de molde trigo integral El Molino de Dia 820 g", BigDecimal("1.29"), "1.57 €/kg", "i", "c") to "Pan de molde integral",
            StoreOffer("19", "Pan de molde espelta integral El Molino de Dia 400 g", BigDecimal("1.29"), "3.23 €/kg", "i", "c") to null,
            StoreOffer("20", "Azúcar blanco Dia 1 Kg", BigDecimal("0.90"), "0.90 €/kg", "i", "c") to "Azúcar blanco",
            StoreOffer("21", "Azúcar blanco en sobres Hacendado", BigDecimal("4.43"), "4.43 €/kg", "i", "c") to null,
            StoreOffer("22", "Panela azúcar moreno de caña integral Hacendado", BigDecimal("4.30"), "4.30 €/kg", "i", "c") to null,
            StoreOffer("23", "Macarrón Hacendado", BigDecimal("1.15"), "1.15 €/kg", "i", "c") to "Macarrones",
            StoreOffer("24", "Macarrones Dia Al Diante 1 Kg", BigDecimal("1.15"), "1.15 €/kg", "i", "c") to "Macarrones",
            StoreOffer("25", "Macarrones Mac & Cheese Bacon Hacendado gratinados", BigDecimal("8.75"), "8.75 €/kg", "i", "c") to null,
            StoreOffer("26", "Spaghetti Hacendado", BigDecimal("1.15"), "1.15 €/kg", "i", "c") to "Espaguetis",
            StoreOffer("27", "Espagueti Dia Al Diante 1 Kg", BigDecimal("1.15"), "1.15 €/kg", "i", "c") to "Espaguetis",
            StoreOffer("28", "Spaghetti carbonara Hacendado", BigDecimal("7.86"), "7.86 €/kg", "i", "c") to null,
            StoreOffer("29", "Garbanzo Hacendado", BigDecimal("2.15"), "2.15 €/kg", "i", "c") to "Garbanzos",
            StoreOffer("30", "Garbanzos Dia Vegecampo 1 Kg", BigDecimal("2.15"), "2.15 €/kg", "i", "c") to "Garbanzos",
            StoreOffer("31", "LUENGO Garbanzo cocido extra bote 620 g.", BigDecimal("1.83"), "2.95 €/kg", "i", "c") to "Garbanzos cocidos", // V6
            StoreOffer("32", "Lenteja pardina Hacendado", BigDecimal("1.85"), "1.85 €/kg", "i", "c") to "Lentejas",
            StoreOffer("33", "Lentejas con chorizo Dia Al Punto 430 g", BigDecimal("1.35"), "3.14 €/kg", "i", "c") to null,
            StoreOffer("34", "Yogur natural Dia Láctea 6 x 125 g", BigDecimal("1.00"), "1.33 €/kg", "i", "c") to "Yogur natural",
            StoreOffer("35", "Yogur natural con azúcar de caña Hacendado", BigDecimal("1.40"), "1.40 €/kg", "i", "c") to null,
            StoreOffer("36", "Yogur natural Hacendado 0% MG 0% azúcares añadidos", BigDecimal("1.40"), "1.40 €/kg", "i", "c") to null,
            StoreOffer("37", "Tomate frito Hacendado", BigDecimal("1.13"), "1.13 €/kg", "i", "c") to "Tomate frito",
            StoreOffer("38", "Tomate frito con aceite de oliva Dia Vegecampo 3 x 215 g", BigDecimal("1.40"), "2.17 €/kg", "i", "c") to null,
            StoreOffer("39", "Harina de trigo El Molino de Dia 1 Kg", BigDecimal("0.72"), "0.72 €/kg", "i", "c") to "Harina de trigo",
            StoreOffer("40", "Harina de trigo especial frituras El Molino de Dia 1 Kg", BigDecimal("1.19"), "1.19 €/kg", "i", "c") to null,
            StoreOffer("41", "Café molido natural Hacendado", BigDecimal("2.25"), "9.00 €/kg", "i", "c") to "Café molido natural",
            StoreOffer("42", "Café molido mezcla Hacendado", BigDecimal("2.25"), "9.00 €/kg", "i", "c") to null,
            StoreOffer("43", "Atún claro en aceite de oliva Dia Mari Marinera 6 x 60 g", BigDecimal("4.90"), "13.61 €/kg", "i", "c") to "Atún claro en aceite de oliva",
            StoreOffer("44", "Atún claro en aceite de girasol Hacendado", BigDecimal("2.10"), "8.75 €/kg", "i", "c") to "Atún claro en aceite de girasol",
            StoreOffer("45", "Mantequilla con sal Dia Láctea 250 g", BigDecimal("2.05"), "8.20 €/kg", "i", "c") to "Mantequilla",
            StoreOffer("46", "Rosquillas de mantequilla Hacendado", BigDecimal("2.05"), "6.83 €/kg", "i", "c") to null,
            StoreOffer("47", "Agua mineral Dia 8 L", BigDecimal("0.96"), "0.12 €/L", "i", "c") to "Agua mineral",
            StoreOffer("48", "Agua mineral con gas grande Vichy Catalan", BigDecimal("1.65"), "1.65 €/L", "i", "c") to null,
            // V5 cleaning products: live names of the three stores (2026-10-20, CP 28001)
            StoreOffer("49", "Lejía normal Tradicional Bosque Verde", BigDecimal("0.80"), "0.40 €/L", "i", "c") to "Lejía",
            StoreOffer("50", "PRODUCTO ECONÓMICO ALCAMPO Lejía 5 l.", BigDecimal("1.85"), "0.37 €/L", "i", "c") to "Lejía",
            StoreOffer("51", "Lejía Dia 2 L", BigDecimal("0.80"), "0.40 €/L", "i", "c") to "Lejía",
            StoreOffer("52", "Limpiador WC con lejía Bosque Verde en gel", BigDecimal("0.71"), "0.95 €/L", "i", "c") to null,
            StoreOffer("53", "Lejía Perfumada con detergente Bosque Verde", BigDecimal("1.16"), "0.58 €/L", "i", "c") to "Lejía con detergente",
            StoreOffer("54", "PRODUCTO ALCAMPO Deterlejía limón 2 l.", BigDecimal("1.16"), "0.58 €/L", "i", "c") to "Lejía con detergente",
            StoreOffer("55", "Lejía con detergente limón Dia 2 L", BigDecimal("1.16"), "0.58 €/L", "i", "c") to "Lejía con detergente",
            StoreOffer("56", "Friegasuelos Pino Bosque Verde líquido", BigDecimal("0.95"), "0.63 €/L", "i", "c") to "Friegasuelos",
            StoreOffer("57", "PRODUCTO ALCAMPO Fregasuelos pino 1,5 l.", BigDecimal("0.95"), "0.63 €/L", "i", "c") to "Friegasuelos",
            StoreOffer("58", "Friegasuelos concentrado floral Dia Super Paco 1 L", BigDecimal("1.85"), "1.85 €/L", "i", "c") to null,
            StoreOffer("59", "Lavavajillas a mano Bosque Verde", BigDecimal("1.14"), "0.88 €/L", "i", "c") to "Lavavajillas a mano",
            StoreOffer("60", "PRODUCTO ECONÓMICO ALCAMPO Detergente lavavajillas a mano 1,5 l.", BigDecimal("1.07"), "0.71 €/L", "i", "c") to "Lavavajillas a mano",
            StoreOffer("61", "PRODUCTO ALCAMPO Lavavajillas en gel para máquinas, Todo en 1 720 ml.", BigDecimal("3.65"), "5.07 €/L", "i", "c") to null,
            StoreOffer("62", "Lavavajillas ultra Dia Super Paco 500 ml", BigDecimal("0.92"), "1.84 €/L", "i", "c") to null,
            StoreOffer("63", "Limpiacristales Dia Super Paco 1 L", BigDecimal("1.20"), "1.20 €/L", "i", "c") to "Limpiacristales",
            StoreOffer("64", "Limpiacristales y multiusos Bosque Verde", BigDecimal("1.10"), "1.10 €/L", "i", "c") to null,
            StoreOffer("65", "Limpiador multiusos Dia Super Paco 600 ml", BigDecimal("1.30"), "2.17 €/L", "i", "c") to "Limpiador multiusos",
            StoreOffer("66", "Papel Multiusos Resistente Bosque Verde", BigDecimal("3.75"), "3.75 €/ud", "i", "c") to null,
            StoreOffer("67", "Papel higiénico doble rollo Dia La Llama 12 unidades", BigDecimal("4.56"), "0.38 €/ud", "i", "c") to "Papel higiénico doble rollo",
            StoreOffer("68", "Papel higiénico húmedo WC Bosque Verde", BigDecimal("1.60"), "0.02 €/ud", "i", "c") to null,
            // V6 more types: live names of the three stores (2026-10-23, CP 28001)
            StoreOffer("69", "Cacao soluble Hacendado", BigDecimal("4.90"), "4.90 €/kg", "i", "c") to "Cacao soluble",
            StoreOffer("70", "PRODUCTO ECONÓMICO ALCAMPO Cacao soluble Esencial PRODUCTO ECONÓMICO ALCAMPO 500 g.", BigDecimal("5.98"), "5.98 €/kg", "i", "c") to "Cacao soluble",
            StoreOffer("71", "COLACAO 0% Cacao en polvo soluble sin azúcares añadidos 700 g.", BigDecimal("15.73"), "15.73 €/kg", "i", "c") to null,
            StoreOffer("72", "NESQUIK de NESTLÉ Cacao soluble con tila, melisa y manzanilla buenas noches 400 g.", BigDecimal("17.20"), "17.20 €/kg", "i", "c") to null,
            StoreOffer("73", "Galletas María Hacendado", BigDecimal("1.69"), "1.69 €/kg", "i", "c") to "Galletas María",
            StoreOffer("74", "FAMILY BISCUITS Galleta maria 800 g.", BigDecimal("1.66"), "1.66 €/kg", "i", "c") to "Galletas María",
            StoreOffer("75", "Papilla 8 cereales con galleta María Nestlé +6 meses", BigDecimal("10.94"), "10.94 €/kg", "i", "c") to null,
            StoreOffer("76", "Copos de avena Gran Dia 500 g", BigDecimal("1.64"), "1.64 €/kg", "i", "c") to "Copos de avena",
            StoreOffer("77", "Pan tostado con copos de avena Vitalité Kaura Wasa", BigDecimal("8.75"), "8.75 €/kg", "i", "c") to null,
            StoreOffer("78", "Mermelada de fresa extra Dia Fruticampo 650 g", BigDecimal("3.63"), "3.63 €/kg", "i", "c") to "Mermelada de fresa",
            StoreOffer("79", "Mermelada de fresa monodosis Hacendado", BigDecimal("6.00"), "6.00 €/kg", "i", "c") to null,
            StoreOffer("80", "PRODUCTO ALCAMPO Miel de flores 1 kg.", BigDecimal("4.99"), "4.99 €/kg", "i", "c") to "Miel de flores",
            StoreOffer("81", "AL ANDALUS Miel de romero 500 g.", BigDecimal("13.76"), "13.76 €/kg", "i", "c") to null,
            StoreOffer("82", "Café soluble Classic Hacendado", BigDecimal("19.50"), "19.50 €/kg", "i", "c") to "Café soluble",
            StoreOffer("83", "Café soluble 3 en 1 Nescafé 180 g", BigDecimal("17.17"), "17.17 €/kg", "i", "c") to null,
            StoreOffer("84", "AUCHAN Leche entera de vaca sin lactosa 1 l Producto Alcampo.", BigDecimal("1.05"), "1.05 €/L", "i", "c") to "Leche entera sin lactosa",
            StoreOffer("85", "Leche semidesnatada sin lactosa Dia Láctea 1 L", BigDecimal("0.94"), "0.94 €/L", "i", "c") to "Leche semidesnatada sin lactosa",
            StoreOffer("86", "PULEVA Vita calcio Leche semidesnatada sin lactosa, con alto contenido en calcio 1 l.", BigDecimal("1.36"), "1.36 €/L", "i", "c") to null,
            StoreOffer("87", "Nata para cocinar Asturiana 500 ml", BigDecimal("5.30"), "5.30 €/L", "i", "c") to "Nata para cocinar",
            StoreOffer("88", "Nata ligera para cocinar Hacendado", BigDecimal("3.08"), "3.08 €/L", "i", "c") to null,
            StoreOffer("89", "Queso rallado 4 quesos Hacendado", BigDecimal("8.75"), "8.75 €/kg", "i", "c") to "Queso rallado 4 quesos",
            StoreOffer("90", "Queso mozzarella rallado Dia Láctea 200 g", BigDecimal("8.00"), "8.00 €/kg", "i", "c") to "Queso rallado mozzarella",
            StoreOffer("91", "Queso rallado mozzarella y gouda Hacendado", BigDecimal("6.00"), "6.00 €/kg", "i", "c") to null,
            StoreOffer("92", "Queso rallado emmental gratinar Hacendado", BigDecimal("8.00"), "8.00 €/kg", "i", "c") to "Queso rallado emmental",
            StoreOffer("93", "Yogur griego natural Hacendado", BigDecimal("1.93"), "1.93 €/kg", "i", "c") to "Yogur griego natural",
            StoreOffer("94", "Yogur griego natural con azúcar de caña Dia Fidias 6 x 125 g", BigDecimal("2.00"), "2.00 €/kg", "i", "c") to null,
            StoreOffer("95", "Bebida de avena Dia Vegedia 1 L", BigDecimal("0.95"), "0.95 €/L", "i", "c") to "Bebida de avena",
            StoreOffer("96", "Bebida de avena barista Dia Vegedia 1 L", BigDecimal("0.99"), "0.99 €/L", "i", "c") to null,
            StoreOffer("97", "AUCHAN Bebida de soja sin azúcares añadidos ni gluten 1 l. Producto Alcampo", BigDecimal("0.75"), "0.75 €/L", "i", "c") to "Bebida de soja",
            StoreOffer("98", "Zumo de naranja Don Simón 1 L", BigDecimal("1.93"), "1.93 €/L", "i", "c") to "Zumo de naranja",
            StoreOffer("99", "Bebida de zumo de naranja sin azúcares añadidos Dia Saborfera 1 L", BigDecimal("0.99"), "0.99 €/L", "i", "c") to null,
            StoreOffer("100", "Zumo de naranja recién exprimido Hacendado", BigDecimal("5.95"), "5.95 €/L", "i", "c") to null,
            StoreOffer("101", "PRODUCTO ALCAMPO Sal fina PRODUCTO ALCAMPO 1 kg.", BigDecimal("0.40"), "0.40 €/kg", "i", "c") to "Sal fina",
            StoreOffer("102", "Vinagre de vino blanco Hacendado", BigDecimal("0.65"), "0.65 €/L", "i", "c") to "Vinagre de vino",
            StoreOffer("103", "Fideo grueso Hacendado", BigDecimal("1.70"), "1.70 €/kg", "i", "c") to "Fideos",
            StoreOffer("104", "Fideos de chocolate Hacendado", BigDecimal("17.50"), "17.50 €/kg", "i", "c") to null,
            StoreOffer("105", "Tomate triturado Dia Vegecampo 800 g", BigDecimal("1.25"), "1.25 €/kg", "i", "c") to "Tomate triturado",
            StoreOffer("106", "Mayonesa Hacendado", BigDecimal("2.96"), "2.96 €/L", "i", "c") to "Mayonesa",
            StoreOffer("107", "HEINZ Mayonesa cebolla caramelizada 400 ml.", BigDecimal("8.38"), "8.38 €/L", "i", "c") to null,
            StoreOffer("108", "Ketchup Heinz 460 g", BigDecimal("6.50"), "6.50 €/kg", "i", "c") to "Ketchup",
            StoreOffer("109", "PRIMA Ketchup cero 460 g + 50 g gratis", BigDecimal("5.88"), "5.88 €/kg", "i", "c") to null,
            StoreOffer("110", "Alubia blanca Hacendado", BigDecimal("2.45"), "2.45 €/kg", "i", "c") to "Alubias blancas",
            StoreOffer("111", "Alubias blancas cocidas Dia Vegecampo 400 g", BigDecimal("1.88"), "1.88 €/kg", "i", "c") to null,
            StoreOffer("112", "Garbanzo cocido Hacendado", BigDecimal("1.40"), "1.40 €/kg", "i", "c") to "Garbanzos cocidos",
            StoreOffer("113", "Lentejas cocidas Luengo 400 g", BigDecimal("3.18"), "3.18 €/kg", "i", "c") to "Lentejas cocidas",
            StoreOffer("114", "Atún claro al natural Hacendado", BigDecimal("8.75"), "8.75 €/kg", "i", "c") to "Atún al natural",
            StoreOffer("115", "PRODUCTO ALCAMPO Sardinas en aceite de oliva 84 g.", BigDecimal("14.76"), "14.76 €/kg", "i", "c") to "Sardinas en aceite",
            StoreOffer("116", "Filetes de sardina ahumada Hacendado en aceite de girasol", BigDecimal("25.29"), "25.29 €/kg", "i", "c") to null,
            StoreOffer("117", "PRODUCTO ALCAMPO Maíz dulce 285 g.", BigDecimal("3.75"), "3.75 €/kg", "i", "c") to "Maíz dulce",
            StoreOffer("118", "Maíz dulce Hacendado ultracongelado", BigDecimal("3.10"), "3.10 €/kg", "i", "c") to null,
            StoreOffer("119", "Refresco cola Hacendado", BigDecimal("1.06"), "1.06 €/L", "i", "c") to "Refresco de cola",
            StoreOffer("120", "COCA COLA Sabor Original Refresco de cola botella 2 l.", BigDecimal("1.08"), "1.08 €/L", "i", "c") to "Refresco de cola",
            StoreOffer("121", "Refresco cola Hacendado zero azúcar", BigDecimal("1.06"), "1.06 €/L", "i", "c") to null,
            StoreOffer("122", "Cerveza Clásica Steinburg", BigDecimal("0.91"), "0.91 €/L", "i", "c") to "Cerveza",
            StoreOffer("123", "Cerveza 0,0% alcohol Dia Ramblers 33 cl", BigDecimal("0.91"), "0.91 €/L", "i", "c") to null,
            StoreOffer("124", "Cerveza Suave Steinburg", BigDecimal("0.79"), "0.79 €/L", "i", "c") to null,
            StoreOffer("125", "Gel de ducha avena 1 L", BigDecimal("0.11"), "0.11 €/100 ml", "i", "c") to "Gel de ducha",
            StoreOffer("126", "Gel de baño Frutal Deliplus piel normal", BigDecimal("1.20"), "1.20 €/100 ml", "i", "c") to "Gel de ducha",
            StoreOffer("127", "Champú 1 L", BigDecimal("0.13"), "0.13 €/100 ml", "i", "c") to "Champú",
            StoreOffer("128", "Champú y acondicionador 2 en 1 H&S 250 ml", BigDecimal("1.32"), "1.32 €/100 ml", "i", "c") to null,
        )

        persist(*expected.keys.toTypedArray())

        expected.forEach { (offer, type) -> assertEquals(type, typeOf(offer.externalId), offer.name) }
    }

    @Test
    fun `product type is recomputed on every refresh`() {
        persist(StoreOffer("1", "Leche entera Hacendado", BigDecimal("0.95"), "0.95 €/L", "i", "c"))
        assertEquals("Leche entera", typeOf("1"))

        persist(StoreOffer("1", "Leche desnatada sin lactosa Hacendado", BigDecimal("0.99"), "0.99 €/L", "i", "c"))
        assertEquals(null, typeOf("1"))

        persist(StoreOffer("1", "Leche semidesnatada Hacendado", BigDecimal("0.99"), "0.99 €/L", "i", "c"))
        assertEquals("Leche semidesnatada", typeOf("1"))
    }

    @Test
    fun `unavailable results are never persisted`() {
        assertFailsWith<IllegalArgumentException> {
            service.persist(StoreName.DIA, term, cp, StoreSearchResult.Unavailable("HTTP 502"))
        }
        assertEquals(0, snapshots.count())
    }
}
