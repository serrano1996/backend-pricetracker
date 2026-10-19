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
            // Nonsense terms never reach a scraper: each one costs a call that cannot be served from cache
            if (!ALLOWED.matches(normalized)) throw InvalidSearchQueryException("term contains unsupported characters")
            if (LETTER.findAll(normalized).take(2).count() < 2) throw InvalidSearchQueryException("term must contain at least 2 letters")
            if (REPEATED.containsMatchIn(normalized) || CONSONANT_RUN.containsMatchIn(normalized)) {
                throw InvalidSearchQueryException("term does not look like a product")
            }
            return SearchTerm(normalized)
        }

        // Letters (any script, with accents), digits, spaces and the punctuation found in product names
        private val ALLOWED = Regex("[\\p{L}\\p{M}\\p{Nd} .,'&%/+-]+")
        private val LETTER = Regex("\\p{L}")
        private val REPEATED = Regex("(.)\\1{3,}")
        // 'y' counts as a vowel ("rhythm"); Spanish and brand names stay under 5 in a row ("schweppes", "construcción")
        private val CONSONANT_RUN = Regex("[bcdfghjklmnñpqrstvwxzç]{5,}")
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
