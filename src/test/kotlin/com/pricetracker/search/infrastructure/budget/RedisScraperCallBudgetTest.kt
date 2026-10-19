package com.pricetracker.search.infrastructure.budget

import com.pricetracker.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RedisScraperCallBudgetTest(@Autowired private val redis: StringRedisTemplate) {

    private fun budget(limit: Int, window: Duration = Duration.ofHours(1), key: String = "pt:test:${UUID.randomUUID()}") =
        RedisScraperCallBudget(redis, limit, window, key)

    @Test
    fun `allows exactly the limit within a window, shared by every instance`() {
        val key = "pt:test:${UUID.randomUUID()}"
        val a = budget(3, key = key)
        val b = budget(3, key = key)

        assertEquals(listOf(true, true, true, false), listOf(a.tryConsume(), b.tryConsume(), a.tryConsume(), b.tryConsume()))
    }

    @Test
    fun `a new window starts once the previous one expires`() {
        val budget = budget(1, Duration.ofMillis(200))
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())

        Thread.sleep(400)

        assertTrue(budget.tryConsume())
    }

    @Test
    fun `the counter always carries the window as ttl, so it can never block forever`() {
        val key = "pt:test:${UUID.randomUUID()}"
        budget(5, key = key).tryConsume()

        val remaining = redis.getExpire(key)
        assertTrue(remaining in 1..3600, "ttl was $remaining")
    }

    @Test
    fun `unreachable redis lets the call through, quickly`() {
        val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration("localhost", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build(),
        ).apply {
            afterPropertiesSet()
            start()
        }
        val broken = RedisScraperCallBudget(StringRedisTemplate(factory), 1, Duration.ofHours(1), "pt:test:x")

        val started = System.nanoTime()
        assertTrue(broken.tryConsume())
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
        factory.destroy()
    }
}
