package com.pricetracker.search.application.port

import java.time.Duration

/** Distributed lock so only one process refreshes a given (store, postal code, term) at a time (spec 002). */
interface RefreshLockPort {
    /** Never throws: an unreachable lock store is [LockAttempt.Unavailable] (spec 002 RF-5). */
    fun tryAcquire(key: String, ttl: Duration): LockAttempt

    /** Frees the lock only if [token] still owns it; never throws (spec 002 RF-2, RF-6). */
    fun release(token: LockToken)
}

data class LockToken(val key: String, val value: String)

sealed interface LockAttempt {
    data class Acquired(val token: LockToken) : LockAttempt
    data object Busy : LockAttempt
    data object Unavailable : LockAttempt
}
