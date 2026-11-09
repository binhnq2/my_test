package com.ts8.core.search

import java.text.Normalizer
import java.util.Locale

/**
 * Vietnamese text normalisation used on both sides of the search index.
 *
 * The pack builder (`tools/mapdata/build_search_db.py`) calls the exact same
 * rules in Python; if one side changes, cached packs must be rebuilt. The two
 * implementations are kept in sync by
 * `PlaceSearchTest.testNormalizeMatchesBuilderContract`.
 */
object VietnameseText {

    private val DIACRITIC_STRIP = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Cultural note: search must work whether or not the driver's keyboard
     * emits diacritics, so `cau rong`, `cầu rồng` and `CẦU RỒNG` all fold to
     * `cau rong`.
     */
    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        // NFKD decomposes "ồ" into "o" + combining marks, which are then dropped.
        var s = Normalizer.normalize(input, Normalizer.Form.NFKD)
        s = s.replace('đ', 'd').replace('Đ', 'D')
        s = DIACRITIC_STRIP.replace(s, "")
        s = s.lowercase(Locale.ROOT)
        // Fold the Vietnamese "i" forms that survive NFKD in some fonts.
        s = s.replace('ı', 'i')
        s = WHITESPACE.replace(s, " ")
        return s.trim()
    }

    /** Splits a normalised string into FTS terms. */
    fun tokens(input: String?): List<String> =
        normalize(input).split(' ').filter { it.isNotEmpty() }

    /**
     * True when every query token is a prefix of some indexed token, which is
     * what makes `cau r` match `cau rong`.
     */
    fun prefixMatch(queryNorm: String, candidateNorm: String): Boolean {
        if (queryNorm.isEmpty()) return false
        val q = queryNorm.split(' ')
        val c = candidateNorm.split(' ')
        return q.all { qt -> c.any { ct -> ct.startsWith(qt) } }
    }

    /** Rewrites a user query into an FTS4 MATCH expression. */
    fun toFtsMatchExpression(query: String): String? {
        val toks = tokens(query)
        if (toks.isEmpty()) return null
        // Each token becomes a prefix term; FTS4 requires the '*' to directly
        // follow the term with no space.
        return toks.joinToString(" ") { "$it*" }
    }
}
