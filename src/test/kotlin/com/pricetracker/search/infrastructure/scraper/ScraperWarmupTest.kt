package com.pricetracker.search.infrastructure.scraper

import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.pricetracker.search.domain.PostalCode
import com.pricetracker.search.domain.SearchTerm
import com.pricetracker.search.domain.StoreName
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ScraperWarmupTest {

    /** Test clock moved by hand. */
    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T08:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    companion object {
        // One WireMock per store so pings can be counted per store
        @JvmField @RegisterExtension val mercadona: WireMockExtension = server()
        @JvmField @RegisterExtension val alcampo: WireMockExtension = server()
        @JvmField @RegisterExtension val dia: WireMockExtension = server()

        private fun server() = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build()
    }

    private val servers get() = listOf(mercadona, alcampo, dia)
    private val clock = MutableClock()

    private fun client(): ScraperClient {
        fun store(wm: WireMockExtension) = ScraperProperties.Store(url = URI(wm.baseUrl()), apiKey = "k")
        return ScraperClient(
            ScraperProperties(store(mercadona), store(alcampo), store(dia), warmupIdle = Duration.ofMinutes(10)),
            clock,
        )
    }

    @BeforeTest
    fun stubs() = servers.forEach {
        it.stubFor(get(urlPathEqualTo("/health")).willReturn(ok()))
        it.stubFor(get(urlPathEqualTo("/api/v1/products")).willReturn(okJson("""{"search": {"warehouse": "w"}, "products": []}""")))
    }

    private fun healthPings(wm: WireMockExtension) = wm.findAll(getRequestedFor(urlPathEqualTo("/health"))).size

    /** Pings run in the background: wait until the expected count shows up, or fail after 5 s. */
    private fun awaitPings(vararg expected: Pair<WireMockExtension, Int>) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (expected.any { (wm, n) -> healthPings(wm) != n } && System.nanoTime() < deadline) Thread.sleep(20)
        expected.forEach { (wm, n) -> kotlin.test.assertEquals(n, healthPings(wm)) }
    }

    @Test
    fun `first call pings every scraper, a second one 5 minutes later pings none`() {
        val client = client()

        client.wakeIdleScrapers()
        awaitPings(mercadona to 1, alcampo to 1, dia to 1)

        clock.now = clock.now.plus(Duration.ofMinutes(5))
        client.wakeIdleScrapers()
        Thread.sleep(200)
        awaitPings(mercadona to 1, alcampo to 1, dia to 1)
    }

    @Test
    fun `pings again only the scrapers idle for longer than the threshold`() {
        val client = client()
        client.wakeIdleScrapers()
        awaitPings(mercadona to 1, alcampo to 1, dia to 1)

        clock.now = clock.now.plus(Duration.ofMinutes(8))
        client.search(StoreName.DIA, SearchTerm.of("leche"), PostalCode.of("28001")) // a real call counts as contact
        clock.now = clock.now.plus(Duration.ofMinutes(3))
        client.wakeIdleScrapers()

        awaitPings(mercadona to 2, alcampo to 2, dia to 1)
    }

    @Test
    fun `concurrent calls ping each scraper once`() {
        val client = client()

        (1..20).map { Thread.ofVirtual().start { client.wakeIdleScrapers() } }.forEach { it.join() }

        Thread.sleep(200)
        awaitPings(mercadona to 1, alcampo to 1, dia to 1)
    }

    @Test
    fun `a slow health check does not delay the caller`() {
        servers.forEach { it.stubFor(get(urlPathEqualTo("/health")).willReturn(ok().withFixedDelay(2000))) }
        val client = client()

        val started = System.nanoTime()
        client.wakeIdleScrapers()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertTrue(elapsed < Duration.ofMillis(500), "took $elapsed")
        // Let the delayed pings land here, not in the journal of the next test (servers are shared)
        awaitPings(mercadona to 1, alcampo to 1, dia to 1)
    }
}
