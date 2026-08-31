package com.zaghloul.masarif.data

import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

/**
 * Turns a raw bank alert (SMS body or notification text) into a [Txn].
 *
 * Two stages:
 *  1. Explicit [ParseRule]s, highest priority first. These give HIGH confidence.
 *  2. A heuristic extractor that finds amount, direction, merchant, card and balance
 *     independently. This is what makes the app work on a bank whose exact alert
 *     template we have never seen.
 *
 * Anything that looks financial but yields no amount is queued for the Rule Lab.
 */
object ParseEngine {

    // ---------------------------------------------------------------- currency

    private const val CUR =
        "AED|USD|EUR|GBP|SAR|QAR|KWD|BHD|OMR|JOD|EGP|INR|PKR|TRY|THB|CHF|JPY|CNY|CAD|AUD|SGD|MYR|LKR|PHP|ZAR|RUB|DHS|DH"

    private const val NUM = "[0-9]{1,3}(?:[,\\s][0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?"

    // NOTE: a trailing \b after the currency would break "AED89.00" — there is no
    // word boundary between "D" and "8". Guard with a letter lookaround instead.

    /** "AED 1,234.56" / "AED1,234.56" / "Dhs. 90" */
    private val AMOUNT_CUR_FIRST = Regex("(?i)\\b($CUR)(?![A-Za-z])[\\s.:]{0,3}($NUM)(?![0-9])")

    /** "1,234.56 AED" / "2400AED" */
    private val AMOUNT_CUR_LAST =
        Regex("(?i)(?<![0-9])($NUM)\\s{0,3}(?<![A-Za-z])($CUR)(?![A-Za-z])")

    /** Last resort: a labelled amount with no currency token. */
    private val AMOUNT_LABELLED =
        Regex("(?i)\\b(?:amount|amt|value|total|sum)\\b[^0-9]{0,12}($NUM)(?![0-9])")

    private val BALANCE_KW = Regex(
        "(?i)\\b(?:avail(?:able)?|remaining|outstanding|current|closing|new|running)?[\\s.]{0,3}" +
            "(?:bal(?:ance)?|limit|funds)\\b"
    )

    // --------------------------------------------------------------- direction

    private val DEBIT_KW = Regex(
        "(?i)\\b(purchase[ds]?|spent|spend|debit(?:ed)?|withdraw(?:n|al|als)?|paid|payment|pos|" +
            "charged?|deduct(?:ed|ion)?|billed|utilis(?:ed|ed)|utilized?|used\\s+(?:for|at|on)|" +
            "transfer(?:red)?\\s+to|sent\\s+to|remitted|atm|purchase\\s+at)\\b"
    )

    private val CREDIT_KW = Regex(
        "(?i)\\b(credit(?:ed)?|received|salary|deposit(?:ed)?|refund(?:ed)?|revers(?:al|ed)|" +
            "cash\\s?back|added\\s+to|transfer(?:red)?\\s+from|incoming|inward|payroll)\\b"
    )

    // ---------------------------------------------------------------- merchant

    /** Words that end a merchant name rather than belonging to it. */
    private const val STOP =
        "on|at|for|from|with|using|avail|available|bal|balance|ref|reference|dated?|your|the|" +
            "card|acct|account|a/c|txn|transaction|approved|declined|info|call|visit|is|was|has"

    private const val MERCH_BODY = "[A-Za-z0-9][A-Za-z0-9 &'`./*\\-_+()]{1,44}?"

    private val MERCHANT_AT =
        Regex("(?i)\\b(?:at|@)\\s+($MERCH_BODY)(?=\\s+(?:$STOP)\\b|[.,;!\\n]|\\s*$)")
    private val MERCHANT_TO =
        Regex("(?i)\\bto\\s+($MERCH_BODY)(?=\\s+(?:$STOP)\\b|[.,;!\\n]|\\s*$)")
    private val MERCHANT_FROM =
        Regex("(?i)\\bfrom\\s+($MERCH_BODY)(?=\\s+(?:$STOP)\\b|[.,;!\\n]|\\s*$)")
    // Every token must start with a letter. Allowing a digit-initial continuation
    // let this swallow the amount itself: "AED 18,500.00" yielded the merchant "AED 18".
    private val MERCHANT_CAPS =
        Regex("\\b([A-Z][A-Z0-9&'./\\-]{2,}(?:\\s+[A-Z][A-Z0-9&'./\\-]{1,}){0,4})\\b")

    /**
     * A merchant candidate made only of these is not a merchant. Without this,
     * "credited to your account" yields the merchant "your".
     */
    private val MERCHANT_REJECT = setOf(
        "your", "you", "the", "a", "an", "my", "our", "this", "that",
        "account", "accounts", "acct", "card", "cards", "a/c", "ac", "wallet",
        "balance", "bal", "avail", "available", "amount", "transaction", "txn",
        "payment", "purchase", "transfer", "atm", "pos", "bank", "customer", "it"
    )

    private val SALARY_KW = Regex("(?i)\\b(salary|payroll|wages|salaries)\\b")
    private val REFUND_KW = Regex("(?i)\\b(refund(?:ed)?|revers(?:al|ed)|cash\\s?back)\\b")

    /** All-caps tokens that are never a merchant. */
    private val CAPS_NOISE = setOf(
        "AED", "USD", "EUR", "GBP", "SAR", "QAR", "KWD", "BHD", "OMR", "JOD", "INR", "DHS", "DH",
        "ATM", "POS", "OTP", "UAE", "PIN", "SMS", "VAT", "IBAN", "AVL", "BAL", "TXN", "REF",
        "CARD", "ACCT", "ACCOUNT", "DEBIT", "CREDIT", "AVAILABLE", "BALANCE", "PURCHASE",
        "PAYMENT", "TRANSACTION", "AMOUNT", "INFO", "CALL", "THANK", "YOU", "NOT", "YOUR",
        "TO", "AT", "ON", "FOR", "THE", "WAS", "HAS", "AND"
    )

    // ------------------------------------------------------------ card/account

    private val CARD_LABELLED = Regex(
        "(?i)\\b(?:card|acc(?:oun)?t|a/c|ac)\\b[^0-9\\n]{0,18}?([0-9]{4})\\b"
    )
    private val CARD_MASKED = Regex("(?i)[X*\\u2022]{2,}[\\s-]?([0-9]{4})\\b")

    // -------------------------------------------------------------- exclusions

    /** Never a transaction, whatever else the message contains. */
    private val HARD_IGNORE = Regex(
        "(?i)\\b(otp|one[\\s-]?time\\s*(?:password|pin|code|passcode)|verification\\s*code|" +
            "security\\s*code|activation\\s*code|do\\s*not\\s*share|never\\s*share|" +
            "password\\s*(?:reset|change)|login\\s*(?:code|attempt|otp)|" +
            "confirmation\\s*code|access\\s*code)\\b"
    )

    /** Marketing. Ignored unless the message also carries a real direction keyword. */
    private val SOFT_IGNORE = Regex(
        "(?i)\\b(congratulations|you\\s*(?:have\\s*)?won|pre[\\s-]?approved|apply\\s*now|" +
            "click\\s*(?:here|below)|unsubscribe|limited\\s*time|special\\s*offer|" +
            "exclusive\\s*offer|terms\\s*(?:and|&)\\s*conditions\\s*apply|t&c\\s*apply|" +
            "eligible\\s*for\\s*(?:a\\s*)?loan|increase\\s*your\\s*limit)\\b"
    )

    // ------------------------------------------------------------------- dates

    private val DATE_PATTERNS = listOf(
        Regex("\\b([0-3]?[0-9])[/\\-.]([0-1]?[0-9])[/\\-.](20[0-9]{2}|[0-9]{2})\\b") to "dmy",
        Regex("\\b(20[0-9]{2})[/\\-.]([0-1][0-9])[/\\-.]([0-3][0-9])\\b") to "ymd",
        Regex("(?i)\\b([0-3]?[0-9])[\\s\\-]([A-Z]{3})[\\s\\-](20[0-9]{2}|[0-9]{2})\\b") to "dMy"
    )

    private val MONTHS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
        "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
    )

    // ================================================================= public

    data class Outcome(
        val txn: Txn?,
        /** Set when nothing usable was found but the message still looked financial. */
        val queueForReview: Boolean,
        val reason: String = ""
    )

    private data class AmountHit(
        val value: Double,
        val currency: String,
        val start: Int,
        val end: Int,
        val isBalance: Boolean
    )

    /**
     * Parse one alert.
     *
     * @param body      the message text
     * @param sender    SMS sender id, or the notification's package name
     * @param arrivalTs when the message reached the phone
     */
    fun parse(
        body: String,
        sender: String,
        arrivalTs: Long,
        rules: List<ParseRule>,
        source: String,
        sourcePkg: String = ""
    ): Outcome {
        val text = body.trim()
        if (text.isEmpty()) return Outcome(null, false, "empty")

        if (HARD_IGNORE.containsMatchIn(text)) return Outcome(null, false, "one-time code")

        val hasDirection = directionOf(text) != null
        if (!hasDirection && SOFT_IGNORE.containsMatchIn(text)) {
            return Outcome(null, false, "marketing")
        }

        // ---- stage 1: explicit rules
        for (rule in rules.filter { it.enabled }.sortedByDescending { it.priority }) {
            if (rule.senderFilter.isNotBlank() &&
                !sender.contains(rule.senderFilter, ignoreCase = true) &&
                !sourcePkg.contains(rule.senderFilter, ignoreCase = true)
            ) continue

            val re = compiled(rule.pattern) ?: continue
            val m = re.find(text) ?: continue

            val amount = group(m, rule.gAmount)?.let { toAmount(it) } ?: continue
            if (amount <= 0.0) continue

            val dir = if (rule.direction == Dir.AUTO) directionOf(text) ?: Dir.DEBIT else rule.direction
            val merchant = group(m, rule.gMerchant)
                ?.let { cleanMerchant(it) }?.takeIf { isUsableMerchant(it) }
                ?: heuristicMerchant(text, dir)
                ?: incomeLabel(text, dir)
                ?: fallbackMerchant(sender, sourcePkg)
            val account = group(m, rule.gAccount)?.trim() ?: heuristicAccount(text) ?: ""
            val balance = group(m, rule.gBalance)?.let { toAmount(it) }
            val currency = group(m, rule.gCurrency)?.uppercase(Locale.US)?.let(::normCurrency)
                ?: findCurrency(text) ?: "AED"

            return Outcome(
                build(
                    text, sender, arrivalTs, amount, currency, dir, merchant, account,
                    balance, source, sourcePkg, Conf.HIGH, rule.id
                ),
                false
            )
        }

        // ---- stage 2: heuristics
        val hits = internalHits(text)
        if (hits.isEmpty()) {
            return Outcome(null, looksFinancial(text), "no amount found")
        }

        val txAmountHit = hits.firstOrNull { !it.isBalance }
            ?: return Outcome(null, looksFinancial(text), "only a balance found")

        val dir = directionOf(text)
        val balance = hits.firstOrNull { it.isBalance }?.value
        val account = heuristicAccount(text) ?: ""
        val merchant = heuristicMerchant(text, dir ?: Dir.DEBIT)

        // No direction keyword at all is a weak signal. Require a card reference
        // or a balance before believing it is a transaction.
        if (dir == null && account.isEmpty() && balance == null) {
            return Outcome(null, true, "no direction keyword")
        }

        val resolvedDir = dir ?: Dir.DEBIT
        val named = merchant ?: incomeLabel(text, resolvedDir)
        val confidence = if (dir != null && named != null) Conf.MEDIUM else Conf.LOW

        return Outcome(
            build(
                text, sender, arrivalTs, txAmountHit.value, txAmountHit.currency, resolvedDir,
                named ?: fallbackMerchant(sender, sourcePkg), account, balance,
                source, sourcePkg, confidence, -1L
            ),
            false
        )
    }

    /**
     * Cheap pre-filter so the review queue holds only bank-shaped messages.
     * Deliberately does NOT require a detectable amount: a message whose amount
     * format we failed to read is exactly what the Rule Lab exists to fix.
     */
    fun looksFinancial(text: String): Boolean {
        if (HARD_IGNORE.containsMatchIn(text)) return false
        val dir = directionOf(text)
        val bankish = dir != null ||
            CARD_LABELLED.containsMatchIn(text) ||
            CARD_MASKED.containsMatchIn(text) ||
            BALANCE_KW.containsMatchIn(text)
        if (!bankish) return false
        if (dir == null && SOFT_IGNORE.containsMatchIn(text)) return false
        return true
    }

    // ================================================================ builders

    private fun build(
        raw: String, sender: String, arrivalTs: Long, amount: Double, currency: String,
        dir: String, merchant: String, account: String, balance: Double?,
        source: String, sourcePkg: String, confidence: String, ruleId: Long
    ): Txn {
        val ts = explicitDate(raw, arrivalTs) ?: arrivalTs
        val norm = normalizeMerchant(merchant)
        return Txn(
            ts = ts,
            amount = amount,
            currency = currency,
            direction = dir,
            merchant = merchant,
            merchantNorm = norm,
            category = Cat.OTHER, // assigned by Categorizer before insert
            account = account,
            balance = balance,
            source = source,
            sourcePkg = sourcePkg.ifBlank { sender },
            raw = raw,
            rawHash = hash(raw, sender),
            confidence = confidence,
            ruleId = ruleId,
            isTransfer = looksLikeTransfer(raw, merchant),
            createdAt = System.currentTimeMillis()
        )
    }

    private val TRANSFER_KW = Regex(
        "(?i)\\b(own\\s+account|between\\s+your|card\\s+payment|credit\\s+card\\s+payment|" +
            "self\\s+transfer|to\\s+your\\s+own|internal\\s+transfer|fund\\s+transfer\\s+to\\s+your)\\b"
    )

    private fun looksLikeTransfer(raw: String, merchant: String): Boolean =
        TRANSFER_KW.containsMatchIn(raw) || merchant.equals("own account", ignoreCase = true)

    // ================================================================ extractors

    fun amountHits(text: String): List<AmountHitPublic> = internalHits(text).map {
        AmountHitPublic(it.value, it.currency, it.start, it.end, it.isBalance)
    }

    /** Exposed shape so the UI can highlight what the parser saw. */
    data class AmountHitPublic(
        val value: Double, val currency: String, val start: Int, val end: Int, val isBalance: Boolean
    )

    private fun internalHits(text: String): List<AmountHit> {
        val raw = mutableListOf<Triple<Double, String, IntRange>>()

        AMOUNT_CUR_FIRST.findAll(text).forEach { m ->
            val cur = normCurrency(m.groupValues[1].uppercase(Locale.US))
            toAmount(m.groupValues[2])?.let { raw += Triple(it, cur, m.range) }
        }
        AMOUNT_CUR_LAST.findAll(text).forEach { m ->
            val cur = normCurrency(m.groupValues[2].uppercase(Locale.US))
            toAmount(m.groupValues[1])?.let { raw += Triple(it, cur, m.range) }
        }
        if (raw.isEmpty()) {
            AMOUNT_LABELLED.findAll(text).forEach { m ->
                toAmount(m.groupValues[1])?.let { raw += Triple(it, "AED", m.range) }
            }
        }

        // De-duplicate overlapping matches, keep document order.
        val sorted = raw.sortedBy { it.third.first }
        val kept = mutableListOf<Triple<Double, String, IntRange>>()
        for (h in sorted) {
            if (kept.none { it.third.first <= h.third.first && h.third.first <= it.third.last }) {
                kept += h
            }
        }

        val balanceRanges = BALANCE_KW.findAll(text).map { it.range }.toList()
        return kept.map { (value, cur, range) ->
            // An amount is a balance only when a balance keyword sits immediately
            // before it with nothing but separators in between. Checking the other
            // direction would wrongly flag "AED 250 spent. Available balance AED 5000".
            val isBal = balanceRanges.any { br ->
                val from = br.last + 1
                if (from > range.first) return@any false
                val gap = range.first - from
                if (gap > 18) return@any false
                text.substring(from, range.first).none { it.isDigit() }
            }
            AmountHit(value, cur, range.first, range.last + 1, isBal)
        }
    }

    fun directionOf(text: String): String? {
        // "credit card" / "debit card" describe the instrument, not the direction.
        val t = text
            .replace(Regex("(?i)\\bcredit[\\s-]?card\\b"), "card")
            .replace(Regex("(?i)\\bdebit[\\s-]?card\\b"), "card")
            .replace(Regex("(?i)\\bcredit\\s+limit\\b"), "limit")

        val d = DEBIT_KW.find(t)?.range?.first
        val c = CREDIT_KW.find(t)?.range?.first
        return when {
            d != null && c != null -> if (d <= c) Dir.DEBIT else Dir.CREDIT
            d != null -> Dir.DEBIT
            c != null -> Dir.CREDIT
            else -> null
        }
    }

    private fun heuristicMerchant(text: String, dir: String): String? {
        val order = if (dir == Dir.CREDIT) {
            listOf(MERCHANT_FROM, MERCHANT_AT, MERCHANT_TO)
        } else {
            listOf(MERCHANT_AT, MERCHANT_TO, MERCHANT_FROM)
        }
        for (re in order) {
            // A pattern can match more than once; take the first usable candidate.
            for (m in re.findAll(text)) {
                val c = m.groupValues.getOrNull(1)?.let { cleanMerchant(it) } ?: continue
                if (isUsableMerchant(c)) return c
            }
        }
        // Fall back to a run of capitals that is not a known noise token.
        MERCHANT_CAPS.findAll(text).forEach { m ->
            val v = m.groupValues[1].trim()
            val tokens = v.split(Regex("\\s+"))
            if (tokens.all { it.uppercase(Locale.US) in CAPS_NOISE }) return@forEach
            if (v.length in 3..44) {
                val cleaned = cleanMerchant(v)
                if (isUsableMerchant(cleaned) && cleaned.length >= 3) return cleaned
            }
        }
        return null
    }

    /**
     * A candidate is a merchant only if something survives once filler words,
     * currency codes and bare numbers are stripped out.
     */
    private fun isUsableMerchant(candidate: String): Boolean {
        if (candidate.isBlank() || candidate.length < 2) return false
        val tokens = candidate.split(Regex("[^A-Za-z0-9/]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        return tokens.any { tok ->
            tok.lowercase(Locale.US) !in MERCHANT_REJECT &&
                tok.uppercase(Locale.US) !in CAPS_NOISE &&
                tok.count { it.isLetter() } >= 2
        }
    }

    /** Names a credit that has no counterparty, e.g. a salary or a card refund. */
    private fun incomeLabel(text: String, dir: String): String? {
        if (dir != Dir.CREDIT) return null
        if (SALARY_KW.containsMatchIn(text)) return "Salary"
        if (REFUND_KW.containsMatchIn(text)) return "Refund"
        return null
    }

    private fun heuristicAccount(text: String): String? =
        CARD_MASKED.find(text)?.groupValues?.getOrNull(1)
            ?: CARD_LABELLED.find(text)?.groupValues?.getOrNull(1)

    private fun findCurrency(text: String): String? =
        Regex("(?i)\\b($CUR)\\b").find(text)?.groupValues?.get(1)
            ?.uppercase(Locale.US)?.let(::normCurrency)

    private fun normCurrency(c: String): String =
        when (c.uppercase(Locale.US)) {
            "DH", "DHS" -> "AED"
            else -> c.uppercase(Locale.US)
        }

    private fun fallbackMerchant(sender: String, pkg: String): String {
        val s = sender.ifBlank { pkg }
        if (s.isBlank()) return "Unknown"
        return s.substringAfterLast('.').replace('_', ' ').trim()
            .replaceFirstChar { it.uppercase() }
    }

    // ================================================================ cleaning

    fun cleanMerchant(rawIn: String): String {
        var s = rawIn.trim()
        s = s.replace(Regex("\\s+"), " ")
        s = s.trim(' ', '.', ',', ';', ':', '-', '*', '_', '/', '(', ')', '\'', '`')
        // Strip a trailing country/city code many acquirers append, e.g. "NOON COM DUBAI AE"
        s = s.replace(Regex("(?i)\\s+(AE|UAE|ARE|DUBAI|ABU DHABI|SHARJAH|AJMAN)$"), "")
        s = s.replace(Regex("(?i)^(the|a)\\s+"), "")
        if (s.length > 42) s = s.take(42).trim()
        // ALL CAPS acquirer strings read better in title case.
        if (s == s.uppercase(Locale.US) && s.any { it.isLetter() }) {
            s = s.lowercase(Locale.US).split(' ').joinToString(" ") { w ->
                if (w.length <= 2 && w.all { it.isLetter() }) w.uppercase(Locale.US)
                else w.replaceFirstChar { it.uppercase() }
            }
        }
        return s.trim()
    }

    /** Stable key used for grouping and subscription detection. */
    fun normalizeMerchant(m: String): String {
        var s = m.uppercase(Locale.US)
        s = s.replace(Regex("[^A-Z0-9 ]"), " ")
        s = s.replace(Regex("\\b[0-9]{3,}\\b"), " ")     // store / terminal numbers
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    fun toAmount(s: String): Double? =
        s.replace(",", "").replace(" ", "").trim().toDoubleOrNull()

    // =================================================================== dates

    private fun explicitDate(text: String, arrivalTs: Long): Long? {
        for ((re, kind) in DATE_PATTERNS) {
            val m = re.find(text) ?: continue
            val g = m.groupValues
            val cal = Calendar.getInstance()
            try {
                when (kind) {
                    "dmy" -> {
                        val y = expandYear(g[3].toInt())
                        cal.set(y, g[2].toInt() - 1, g[1].toInt(), 12, 0, 0)
                    }
                    "ymd" -> cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), 12, 0, 0)
                    "dMy" -> {
                        val mo = MONTHS[g[2].lowercase(Locale.US)] ?: continue
                        cal.set(expandYear(g[3].toInt()), mo, g[1].toInt(), 12, 0, 0)
                    }
                }
            } catch (_: Exception) {
                continue
            }
            cal.set(Calendar.MILLISECOND, 0)
            val t = cal.timeInMillis
            // Trust it only if it is near the arrival time; otherwise it is a due date or expiry.
            val thirtyDays = 30L * 24 * 60 * 60 * 1000
            if (t in (arrivalTs - thirtyDays)..(arrivalTs + 2 * 24 * 60 * 60 * 1000L)) return t
        }
        return null
    }

    private fun expandYear(y: Int): Int = if (y < 100) 2000 + y else y

    // ================================================================== utils

    private val cache = HashMap<String, Regex?>()

    fun compiled(pattern: String): Regex? = synchronized(cache) {
        cache.getOrPut(pattern) {
            try {
                Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun group(m: MatchResult, idx: Int): String? {
        if (idx <= 0) return null
        val g = m.groups
        if (idx >= g.size) return null
        return g[idx]?.value?.takeIf { it.isNotBlank() }
    }

    fun hash(body: String, sender: String): String {
        val norm = body.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim() + "|" +
            sender.lowercase(Locale.US)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(norm.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}
