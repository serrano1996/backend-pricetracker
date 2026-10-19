package com.pricetracker.search.domain

import java.util.Locale

/** Invalid user input for a search; the web layer answers 400 (spec 001 RF-2). */
class InvalidSearchQueryException(message: String) : IllegalArgumentException(message)

/** Normalized search term: the key for freshness and metrics (spec 001 RF-3). Same limits as the scrapers. */
@JvmInline
value class SearchTerm private constructor(val value: String) {
    companion object {
        const val MAX_LENGTH = 100

        // \p{Z} adds Unicode separators such as the non-breaking space, which \s misses
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")

        fun of(raw: String): SearchTerm {
            val normalized = raw.replace(WHITESPACE, " ").trim().lowercase(Locale.ROOT)
            // Code points, like the scrapers (Python len) and the database check (char_length)
            val length = normalized.codePointCount(0, normalized.length)
            if (length !in 1..MAX_LENGTH) {
                throw InvalidSearchQueryException("term must be 1 to $MAX_LENGTH characters")
            }
            return SearchTerm(normalized)
        }
    }
}

@JvmInline
value class PostalCode private constructor(val value: String) {
    companion object {
        // [0-9], not \d: only ASCII digits, as in the scrapers
        private val FORMAT = Regex("[0-9]{5}")

        fun of(raw: String): PostalCode {
            val trimmed = raw.trim()
            if (!FORMAT.matches(trimmed)) throw InvalidSearchQueryException("postal_code must be 5 digits")
            return PostalCode(trimmed)
        }
    }
}
