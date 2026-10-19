package com.pricetracker.search.infrastructure.lock

import com.pricetracker.TestcontainersConfiguration
import com.pricetracker.search.application.port.LockAttempt
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RedisRefreshLockTest(
    @Autowired private val lock: RedisRefreshLock,
    @Autowired private val redis: StringRedisTemplate,
) {
    private val ttl = Duration.ofSeconds(30)
    private fun key() = "pt:test:${UUID.randomUUID()}"

    @Test
    fun `second attempt on a held key is busy until the holder releases it`() {
        val key = key()
        val first = assertIs<LockAttempt.Acquired>(lock.tryAcquire(key, ttl))

        assertEquals(LockAttempt.Busy, lock.tryAcquire(key, ttl))

        lock.release(first.token)
        assertIs<LockAttempt.Acquired>(lock.tryAcquire(key, ttl))
    }

    @Test
    fun `release with a stale token does not free someone else's lock`() {
        val key = key()
        val stale = assertIs<LockAttempt.Acquired>(lock.tryAcquire(key, Duration.ofMillis(200))).token
        Thread.sleep(400) // expired
        assertIs<LockAttempt.Acquired>(lock.tryAcquire(key, ttl)) // now held by another owner

        lock.release(stale)

        assertEquals(LockAttempt.Busy, lock.tryAcquire(key, ttl))
    }

    @Test
    fun `lock expires after its ttl`() {
        val key = key()
        lock.tryAcquire(key, Duration.ofMillis(200))

        Thread.sleep(400)

        assertIs<LockAttempt.Acquired>(lock.tryAcquire(key, ttl))
    }

    @Test
    fun `key carries the ttl so a crashed holder cannot block forever`() {
        val key = key()
        lock.tryAcquire(key, ttl)

        val remaining = redis.getExpire(key)
        assertTrue(remaining in 1..30, "ttl was $remaining")
    }

    @Test
    fun `unreachable redis is reported as unavailable, quickly`() {
        val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration("localhost", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build(),
        ).apply {
            afterPropertiesSet()
            start()
        }
        val broken = RedisRefreshLock(StringRedisTemplate(factory))

        val started = System.nanoTime()
        assertEquals(LockAttempt.Unavailable, broken.tryAcquire(key(), ttl))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
        factory.destroy()
    }
}
