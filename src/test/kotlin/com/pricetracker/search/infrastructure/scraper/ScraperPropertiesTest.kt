package com.pricetracker.search.infrastructure.scraper

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScraperPropertiesTest {

    private fun bind(extra: Map<String, String> = emptyMap()): ScraperProperties {
        val base = listOf("mercadona", "alcampo", "dia").flatMap { store ->
            listOf(
                "pricetracker.scrapers.$store.url" to "https://$store.example",
                "pricetracker.scrapers.$store.api-key" to "secret-$store",
            )
        }.toMap()
        return Binder(MapConfigurationPropertySource(base + extra))
            .bind("pricetracker.scrapers", ScraperProperties::class.java)
            .get()
    }

    @Test
    fun `binds every store with default timeouts`() {
        val props = bind()

        assertEquals(URI("https://alcampo.example"), props.alcampo.url)
        assertEquals("secret-dia", props.dia.apiKey)
        assertEquals(Duration.ofSeconds(5), props.mercadona.connectTimeout)
        assertEquals(Duration.ofSeconds(25), props.mercadona.readTimeout)
        assertEquals(Duration.ofMinutes(10), props.warmupIdle)
    }

    @Test
    fun `overrides timeouts per store`() {
        val props = bind(mapOf("pricetracker.scrapers.dia.read-timeout" to "90s"))

        assertEquals(Duration.ofSeconds(90), props.dia.readTimeout)
        assertEquals(Duration.ofSeconds(25), props.alcampo.readTimeout)
    }

    @Test
    fun `toString never exposes api keys`() {
        val text = bind().toString()

        assertFalse("secret-" in text, text)
    }
}
