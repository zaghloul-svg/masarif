package com.zaghloul.masarif.data

/** Direction of money movement. */
object Dir {
    const val DEBIT = "DEBIT"
    const val CREDIT = "CREDIT"
    const val AUTO = "AUTO"
}

/** Where a captured message came from. */
object Src {
    const val SMS = "SMS"
    const val NOTIFICATION = "NOTIFICATION"
    const val MANUAL = "MANUAL"
}

/** How sure the parser is about a transaction. */
object Conf {
    const val HIGH = "HIGH"      // matched an explicit rule
    const val MEDIUM = "MEDIUM"  // heuristics found amount + direction + merchant
    const val LOW = "LOW"        // heuristics found amount only; needs review
}

object Cat {
    const val GROCERIES = "Groceries"
    const val DINING = "Dining & Delivery"
    const val TRANSPORT = "Transport & Fuel"
    const val UTILITIES = "Utilities & Telecom"
    const val SHOPPING = "Shopping"
    const val HEALTH = "Health & Pharmacy"
    const val ENTERTAINMENT = "Entertainment"
    const val SUBSCRIPTIONS = "Subscriptions"
    const val TRAVEL = "Travel"
    const val CASH = "Cash & Transfers"
    const val BILLS = "Bills & Rent"
    const val EDUCATION = "Education"
    const val FEES = "Bank Fees"
    const val INCOME = "Income"
    const val OTHER = "Other"

    /** Every category, income included. */
    val ALL = listOf(
        GROCERIES, DINING, TRANSPORT, UTILITIES, SHOPPING, HEALTH,
        ENTERTAINMENT, SUBSCRIPTIONS, TRAVEL, CASH, BILLS, EDUCATION,
        FEES, INCOME, OTHER
    )

    /** Categories that count as spending and can carry a budget. */
    val SPENDING = ALL.filter { it != INCOME }
}

data class Txn(
    val id: Long = 0L,
    val ts: Long,
    val amount: Double,
    val currency: String = "AED",
    val direction: String,
    val merchant: String,
    val merchantNorm: String,
    val category: String,
    val account: String,
    val balance: Double? = null,
    val source: String,
    val sourcePkg: String = "",
    val raw: String,
    val rawHash: String,
    val confidence: String,
    val ruleId: Long = -1L,
    /** Internal movement (own-account transfer, card payment). Kept, but never counted as spend. */
    val isTransfer: Boolean = false,
    /** Manually excluded from all totals. */
    val excluded: Boolean = false,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** True when this row should count toward spending totals. */
    val countsAsSpend: Boolean
        get() = direction == Dir.DEBIT && !isTransfer && !excluded

    /** True when this row should count toward income totals. */
    val countsAsIncome: Boolean
        get() = direction == Dir.CREDIT && !isTransfer && !excluded
}

/** A message that looked financial but the parser could not turn into a transaction. */
data class Unmatched(
    val id: Long = 0L,
    val ts: Long,
    val body: String,
    val source: String,
    val sourcePkg: String,
    val sender: String,
    val rawHash: String,
    val dismissed: Boolean = false
)

/**
 * A regex rule used to pull fields out of an alert.
 * Group indices are 1-based; -1 means "this rule does not supply that field".
 */
data class ParseRule(
    val id: Long = 0L,
    val name: String,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val pattern: String,
    val gAmount: Int = -1,
    val gMerchant: Int = -1,
    val gAccount: Int = -1,
    val gBalance: Int = -1,
    val gCurrency: Int = -1,
    /** DEBIT, CREDIT, or AUTO to infer from keywords. */
    val direction: String = Dir.AUTO,
    /** Substring matched against SMS sender or notification package. Blank = any source. */
    val senderFilter: String = "",
    val builtin: Boolean = false,
    val hits: Int = 0
)

/** Maps a keyword found in a merchant name to a category. */
data class MerchantRule(
    val id: Long = 0L,
    val keyword: String,
    val category: String,
    val builtin: Boolean = false
)

data class Budget(
    val category: String,
    val monthlyLimit: Double
)

/** A merchant charging a similar amount on a monthly cadence. */
data class Subscription(
    val merchant: String,
    val typicalAmount: Double,
    val occurrences: Int,
    val lastSeen: Long,
    val avgIntervalDays: Double,
    val category: String
)

/** One finding from the on-device insight engine. */
data class Insight(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    /** Estimated monthly AED this insight could free up, when quantifiable. */
    val potentialSaving: Double? = null,
    val category: String? = null
) {
    enum class Severity { CRITICAL, WARNING, INFO, WIN }
}

/** Spending rolled up for one calendar month. */
data class MonthSummary(
    val year: Int,
    val month: Int,
    val spend: Double,
    val income: Double,
    val byCategory: Map<String, Double>,
    val txnCount: Int
) {
    val net: Double get() = income - spend
    val savingsRate: Double get() = if (income > 0.0) (income - spend) / income else 0.0
}
