package com.zaghloul.masarif.data

/**
 * Turns "this bit is the amount, that bit is the merchant" into a reusable regex.
 *
 * The message is rebuilt as a template: selected spans become capture groups, and
 * the literal text between them is generalized so the rule survives the parts that
 * change every message — whitespace, dates, reference numbers. Leading and trailing
 * context is capped so the rule anchors without over-fitting to one message.
 */
object RuleBuilder {

    enum class Field { AMOUNT, MERCHANT, ACCOUNT, BALANCE }

    data class Selection(val field: Field, val start: Int, val end: Int)

    data class Built(
        val pattern: String,
        val gAmount: Int,
        val gMerchant: Int,
        val gAccount: Int,
        val gBalance: Int
    )

    private const val AMOUNT_PAT = "([0-9][0-9,\\s]*(?:\\.[0-9]{1,2})?)"
    private const val ACCOUNT_PAT = "([0-9]{3,6})"
    private const val MERCHANT_LAZY = "([^\\n]{1,45}?)"
    private const val MERCHANT_GREEDY = "([^\\n]{1,45})"

    private const val LEAD_CONTEXT = 34
    private const val TAIL_CONTEXT = 28

    /** @return null when no amount was selected — a rule without one cannot work. */
    fun build(body: String, selections: List<Selection>): Built? {
        val ordered = selections
            .filter { it.start >= 0 && it.end <= body.length && it.start < it.end }
            .sortedBy { it.start }

        // Drop overlaps, keeping the earliest.
        val clean = mutableListOf<Selection>()
        var lastEnd = 0
        for (s in ordered) {
            if (s.start >= lastEnd) {
                clean += s
                lastEnd = s.end
            }
        }
        if (clean.none { it.field == Field.AMOUNT }) return null

        val sb = StringBuilder()
        var gAmount = -1
        var gMerchant = -1
        var gAccount = -1
        var gBalance = -1
        var group = 0
        var cursor = 0

        clean.forEachIndexed { index, sel ->
            var gap = body.substring(cursor, sel.start)
            if (index == 0 && gap.length > LEAD_CONTEXT) gap = gap.takeLast(LEAD_CONTEXT)
            sb.append(literal(gap))

            group++
            when (sel.field) {
                Field.AMOUNT -> {
                    gAmount = group; sb.append(AMOUNT_PAT)
                }
                Field.BALANCE -> {
                    gBalance = group; sb.append(AMOUNT_PAT)
                }
                Field.ACCOUNT -> {
                    gAccount = group; sb.append(ACCOUNT_PAT)
                }
                Field.MERCHANT -> {
                    gMerchant = group
                    val tail = body.substring(sel.end)
                    // A lazy group needs something after it to stop at.
                    sb.append(if (index == clean.lastIndex && tail.isBlank()) MERCHANT_GREEDY else MERCHANT_LAZY)
                }
            }
            cursor = sel.end
        }

        var tail = body.substring(cursor)
        if (tail.length > TAIL_CONTEXT) tail = tail.take(TAIL_CONTEXT)
        sb.append(literal(tail))

        val pattern = sb.toString().trimEnd()
        if (ParseEngine.compiled(pattern) == null) return null
        return Built(pattern, gAmount, gMerchant, gAccount, gBalance)
    }

    /**
     * Escapes literal text while generalizing the parts that differ between messages:
     * runs of whitespace become \s+, runs of digits become [0-9,]+ so dates and
     * reference numbers do not pin the rule to one transaction.
     */
    private fun literal(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> {
                    while (i < s.length && s[i].isWhitespace()) i++
                    out.append("\\s+")
                }
                c.isDigit() -> {
                    while (i < s.length && (s[i].isDigit() || s[i] == ',')) i++
                    out.append("[0-9,]+")
                }
                else -> {
                    out.append(esc(c))
                    i++
                }
            }
        }
        return out.toString()
    }

    private const val SPECIAL = "\\^$.|?*+()[]{}/"

    private fun esc(c: Char): String = if (c in SPECIAL) "\\$c" else c.toString()

    /** Runs a candidate rule against a message so the Rule Lab can preview it. */
    fun preview(rule: ParseRule, body: String, sender: String): ParseEngine.Outcome =
        ParseEngine.parse(
            body = body,
            sender = sender,
            arrivalTs = System.currentTimeMillis(),
            rules = listOf(rule),
            source = Src.SMS
        )
}
