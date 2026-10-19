package com.pricetracker.search.infrastructure.lock

import com.pricetracker.search.application.port.LockAttempt
import com.pricetracker.search.application.port.LockToken
import com.pricetracker.search.application.port.RefreshLockPort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/** `SET key token NX PX ttl` to acquire, compare-and-delete Lua script to release (plan 002 D1). */
@Component
class RedisRefreshLock(private val redis: StringRedisTemplate) : RefreshLockPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun tryAcquire(key: String, ttl: Duration): LockAttempt = try {
        val token = LockToken(key, UUID.randomUUID().toString())
        when (redis.opsForValue().setIfAbsent(key, token.value, ttl)) {
            true -> LockAttempt.Acquired(token)
            else -> LockAttempt.Busy
        }
    } catch (e: RuntimeException) {
        log.warn("refresh lock unavailable key={}: {}", key, e.javaClass.simpleName)
        LockAttempt.Unavailable
    }

    override fun release(token: LockToken) {
        try {
            redis.execute(RELEASE, listOf(token.key), token.value)
        } catch (e: RuntimeException) {
            // The TTL frees it anyway (spec 002 RF-6)
            log.warn("refresh lock not released key={}: {}", token.key, e.javaClass.simpleName)
        }
    }

    private companion object {
        // Only the owner deletes: an expired-and-retaken lock is left to its new holder
        val RELEASE: RedisScript<Long> = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }
}
