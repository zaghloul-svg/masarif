package com.zaghloul.masarif.data

import java.util.Locale

/**
 * Assigns a category to a transaction from editable keyword rules.
 *
 * Matching is whole-word against the normalized merchant name, so "DU" matches the
 * telecom operator but never the "du" inside "DUBAI". User rules beat built-in
 * ones, and longer keywords beat shorter ones, so "CAREEM FOOD" wins over "CAREEM".
 */
class Categorizer(private val rules: List<MerchantRule>) {

    private data class Prepared(val key: String, val category: String, val builtin: Boolean)

    private val prepared: List<Prepared> = rules
        .map { Prepared(ParseEngine.normalizeMerchant(it.keyword), it.category, it.builtin) }
        .filter { it.key.isNotBlank() }
        // User rules first, then longest keyword first.
        .sortedWith(compareBy({ it.builtin }, { -it.key.length }))

    fun categorize(merchantNorm: String, raw: String, direction: String): String {
        val haystack = " ${merchantNorm.trim()} "
        for (p in prepared) {
            if (haystack.contains(" ${p.key} ")) {
                // An income keyword on a debit is a false positive, e.g. a merchant
                // literally called "Refund". Fall through to the next rule.
                if (p.category == Cat.INCOME && direction == Dir.DEBIT) continue
                return p.category
            }
        }

        // The merchant told us nothing; try the message body for the same keywords.
        val rawNorm = " ${ParseEngine.normalizeMerchant(raw)} "
        for (p in prepared) {
            if (p.key.length < 4) continue // too short to be safe against free text
            if (rawNorm.contains(" ${p.key} ")) {
                if (p.category == Cat.INCOME && direction == Dir.DEBIT) continue
                return p.category
            }
        }

        return if (direction == Dir.CREDIT) Cat.INCOME else Cat.OTHER
    }

    companion object {
        /** Category shown before the user has told us otherwise. */
        fun fallback(direction: String) = if (direction == Dir.CREDIT) Cat.INCOME else Cat.OTHER

        fun titleOf(category: String): String = category

        /** Stable colour index so a category keeps its colour across every chart. */
        fun colorIndex(category: String): Int {
            val i = Cat.ALL.indexOf(category)
            return if (i >= 0) i else Cat.ALL.size - 1
        }

        fun normalizeKeyword(k: String): String = k.trim().uppercase(Locale.US)
    }
}
