package com.zaghloul.masarif

import com.zaghloul.masarif.analysis.Analytics
import com.zaghloul.masarif.analysis.InsightEngine
import com.zaghloul.masarif.data.Cat
import com.zaghloul.masarif.data.Categorizer
import com.zaghloul.masarif.data.Dir
import com.zaghloul.masarif.data.MerchantRule
import com.zaghloul.masarif.data.ParseEngine
import com.zaghloul.masarif.data.ParseRule
import com.zaghloul.masarif.data.RuleBuilder
import com.zaghloul.masarif.data.SeedData
import com.zaghloul.masarif.data.Src
import com.zaghloul.masarif.data.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Runs on the JVM with no emulator: every class under test is pure Kotlin.
 *
 * The parser fixtures below are structural shapes that bank alerts commonly take.
 * They are test data, not claims about any particular bank's wording — the app
 * deliberately does not ship guessed bank templates.
 */
class ParseEngineTest {

    private fun parse(body: String, rules: List<ParseRule> = emptyList()) =
        ParseEngine.parse(body, "ADCB", System.currentTimeMillis(), rules, Src.SMS)

    @Test
    fun `reads a card purchase`() {
        val t = parse(
            "Purchase of AED 245.50 with Card ending 4471 at CARREFOUR HYPERMARKET DUBAI " +
                "on 12/08/2026. Avail Bal AED 12,430.75"
        ).txn
        assertNotNull(t)
        assertEquals(245.50, t!!.amount, 0.001)
        assertEquals(Dir.DEBIT, t.direction)
        assertEquals("4471", t.account)
        assertEquals(12430.75, t.balance!!, 0.001)
        assertEquals("Carrefour Hypermarket", t.merchant)
        assertEquals("AED", t.currency)
    }

    @Test
    fun `separates the transaction amount from the available balance`() {
        val t = parse(
            "AED 1,250.00 has been debited from your account XXXX8823 for payment to DEWA " +
                "on 03-08-2026. Available balance AED 9,120.40"
        ).txn!!
        assertEquals(1250.00, t.amount, 0.001)
        assertEquals(9120.40, t.balance!!, 0.001)
    }

    @Test
    fun `a balance keyword after the amount does not steal it`() {
        // Regression: "AED 250 spent. Available balance AED 5000" must not read 250 as a balance.
        val t = parse("AED 250.00 spent at NOON. Available balance AED 5,000.00").txn!!
        assertEquals(250.00, t.amount, 0.001)
        assertEquals(5000.00, t.balance!!, 0.001)
    }

    @Test
    fun `credit card is an instrument, not a direction`() {
        val t = parse("AED 320.00 spent on your Credit Card ending 4471 at SPINNEYS").txn!!
        assertEquals(Dir.DEBIT, t.direction)
        assertEquals("Spinneys", t.merchant)
    }

    @Test
    fun `reads a salary credit and names it`() {
        // Regression: the all-caps fallback used to return the merchant "AED 18".
        val t = parse(
            "Salary of AED 18,500.00 credited to your account ending 8823 on 25/08/2026. " +
                "Available balance AED 27,620.15"
        ).txn!!
        assertEquals(18500.00, t.amount, 0.001)
        assertEquals(Dir.CREDIT, t.direction)
        assertEquals("Salary", t.merchant)
    }

    @Test
    fun `handles an amount glued to its currency`() {
        // Regression: \b between "D" and "8" does not exist, so AED89.00 once failed.
        val t = parse("AED89.00 spent at TALABAT with card 4471").txn!!
        assertEquals(89.00, t.amount, 0.001)
        assertEquals("Talabat", t.merchant)
    }

    @Test
    fun `handles a trailing currency and a foreign currency`() {
        assertEquals(2400.00, parse("Payment of 2,400.00AED made to ETISALAT").txn!!.amount, 0.001)
        val usd = parse("Purchase of USD 12.99 at NETFLIX COM on card ending 4471").txn!!
        assertEquals("USD", usd.currency)
        assertEquals(12.99, usd.amount, 0.001)
    }

    @Test
    fun `normalises dirham abbreviations`() {
        assertEquals("AED", parse("Dhs. 45.00 spent at ADNOC using card 4471").txn!!.currency)
    }

    @Test
    fun `reads a multi-line notification`() {
        val t = parse(
            "ADCB Alert\nAED 1,899.00 debited from card ending 4471\nat APPLE STORE DUBAI\n" +
                "Avail Bal AED 8,100.20"
        ).txn!!
        assertEquals(1899.00, t.amount, 0.001)
        assertEquals("4471", t.account)
    }

    @Test
    fun `ignores one-time codes, balance enquiries, marketing and chat`() {
        assertNull(parse("Your OTP is 483920. Do not share this code. AED 500 at NOON").txn)
        assertNull(parse("Your available balance is AED 12,430.75").txn)
        assertNull(parse("Congratulations! You are pre-approved for a loan of AED 150,000.").txn)
        assertNull(parse("Hey, are we still on for dinner tomorrow?").txn)
    }

    @Test
    fun `queues a bank-shaped message it cannot read, but not chat`() {
        // No amount the parser recognises, but clearly a card alert: worth teaching.
        val bankish = parse("Your card ending 4471 was debited. Ref 88213.")
        assertTrue(bankish.queueForReview)
        assertNull(bankish.txn)

        assertTrue(!parse("Lunch at 1pm?").queueForReview)
    }

    @Test
    fun `hash is whitespace-insensitive and sender-specific`() {
        assertEquals(
            ParseEngine.hash("AED 10 at X", "ADCB"),
            ParseEngine.hash("AED  10   at X ", "ADCB")
        )
        assertTrue(ParseEngine.hash("AED 10 at X", "ADCB") != ParseEngine.hash("AED 10 at X", "FAB"))
    }
}

class RuleBuilderTest {

    private val learned =
        "Purchase of AED 245.50 with Card ending 4471 at CARREFOUR HYPERMARKET on 12/08/2026. " +
            "Avail Bal AED 12,430.75"

    private fun sel(field: RuleBuilder.Field, needle: String): RuleBuilder.Selection {
        val i = learned.indexOf(needle)
        return RuleBuilder.Selection(field, i, i + needle.length)
    }

    @Test
    fun `a rule learned from one message reads the next one of the same shape`() {
        val built = RuleBuilder.build(
            learned,
            listOf(
                sel(RuleBuilder.Field.AMOUNT, "245.50"),
                sel(RuleBuilder.Field.ACCOUNT, "4471"),
                sel(RuleBuilder.Field.MERCHANT, "CARREFOUR HYPERMARKET"),
                sel(RuleBuilder.Field.BALANCE, "12,430.75")
            )
        )
        assertNotNull(built)

        val rule = ParseRule(
            id = 7, name = "test", pattern = built!!.pattern,
            gAmount = built.gAmount, gMerchant = built.gMerchant,
            gAccount = built.gAccount, gBalance = built.gBalance, priority = 900
        )

        val next = "Purchase of AED 89.00 with Card ending 4471 at TALABAT FOOD on 14/09/2026. " +
            "Avail Bal AED 12,341.75"
        val t = ParseEngine.parse(next, "ADCB", System.currentTimeMillis(), listOf(rule), Src.SMS).txn
        assertNotNull(t)
        assertEquals(89.00, t!!.amount, 0.001)
        assertEquals("Talabat Food", t.merchant)
        assertEquals("4471", t.account)
        assertEquals(12341.75, t.balance!!, 0.001)
        assertEquals(7L, t.ruleId)
    }

    @Test
    fun `a rule without an amount is refused`() {
        assertNull(
            RuleBuilder.build(learned, listOf(sel(RuleBuilder.Field.MERCHANT, "CARREFOUR HYPERMARKET")))
        )
    }

    @Test
    fun `a merchant at the end of the message still captures`() {
        val body = "Card 4471 used for AED 320.00 at SPINNEYS"
        val built = RuleBuilder.build(
            body,
            listOf(
                RuleBuilder.Selection(RuleBuilder.Field.AMOUNT, body.indexOf("320.00"), body.indexOf("320.00") + 6),
                RuleBuilder.Selection(RuleBuilder.Field.MERCHANT, body.indexOf("SPINNEYS"), body.length)
            )
        )
        assertNotNull(built)
        val rule = ParseRule(
            id = 3, name = "t", pattern = built!!.pattern,
            gAmount = built.gAmount, gMerchant = built.gMerchant, priority = 900
        )
        val t = ParseEngine.parse(
            "Card 4471 used for AED 12.50 at STARBUCKS DUBAI MALL",
            "ADCB", System.currentTimeMillis(), listOf(rule), Src.SMS
        ).txn
        assertNotNull(t)
        assertEquals(12.50, t!!.amount, 0.001)
        assertEquals("Starbucks Dubai Mall", t.merchant)
    }
}

class CategorizerTest {

    private val c = Categorizer(
        SeedData.merchantRules().mapIndexed { i, (k, cat) ->
            MerchantRule(i.toLong(), k, cat, builtin = true)
        }
    )

    private fun cat(merchant: String, dir: String = Dir.DEBIT) =
        c.categorize(ParseEngine.normalizeMerchant(merchant), merchant, dir)

    @Test
    fun `maps well-known UAE merchants`() {
        assertEquals(Cat.GROCERIES, cat("Carrefour Hypermarket"))
        assertEquals(Cat.DINING, cat("Talabat"))
        assertEquals(Cat.TRANSPORT, cat("ADNOC Station 42"))
        assertEquals(Cat.TRANSPORT, cat("Salik"))
        assertEquals(Cat.UTILITIES, cat("DEWA"))
        assertEquals(Cat.SUBSCRIPTIONS, cat("Netflix"))
        assertEquals(Cat.SHOPPING, cat("Noon Com"))
        assertEquals(Cat.TRAVEL, cat("Emirates Airline"))
    }

    @Test
    fun `a longer keyword beats a shorter one`() {
        assertEquals(Cat.DINING, cat("Careem Food"))
        assertEquals(Cat.TRANSPORT, cat("Careem"))
    }

    @Test
    fun `short keywords match whole words only`() {
        // "DU" the telecom must not match inside "DUBAI".
        assertTrue(cat("Dubai Mall Parking") != Cat.UTILITIES)
    }

    @Test
    fun `income keywords do not classify debits`() {
        assertTrue(cat("Refund Store") != Cat.INCOME)
        assertEquals(Cat.INCOME, cat("Unknown Employer", Dir.CREDIT))
        assertEquals(Cat.OTHER, cat("Zzz Unknown Merchant"))
    }
}

class AnalyticsTest {

    private fun txn(
        daysAgo: Int, amount: Double, merchant: String,
        dir: String = Dir.DEBIT, category: String = Cat.OTHER
    ): Txn {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return Txn(
            id = daysAgo * 1000L + amount.toLong(),
            ts = cal.timeInMillis, amount = amount, direction = dir, merchant = merchant,
            merchantNorm = ParseEngine.normalizeMerchant(merchant), category = category,
            account = "4471", source = Src.SMS, raw = "$merchant $amount",
            rawHash = "$daysAgo-$amount-$merchant", confidence = "MEDIUM"
        )
    }

    @Test
    fun `cycles are contiguous and anchored to the chosen day`() {
        val six = Analytics.recentCycles(6, 1)
        assertEquals(6, six.size)
        assertTrue(six.zipWithNext().all { (a, b) -> a.endTs == b.startTs })
        assertTrue(six.last().isCurrent)

        val payday = Analytics.cycleFor(System.currentTimeMillis(), 25)
        val cal = Calendar.getInstance().apply { timeInMillis = payday.startTs }
        assertEquals(25, cal.get(Calendar.DAY_OF_MONTH))
        assertTrue(payday.contains(System.currentTimeMillis()))
    }

    @Test
    fun `totals exclude transfers and excluded rows`() {
        val t = Analytics.totals(
            listOf(
                txn(2, 100.0, "A", category = Cat.GROCERIES),
                txn(3, 50.0, "B", category = Cat.DINING),
                txn(4, 5000.0, "Salary", dir = Dir.CREDIT, category = Cat.INCOME),
                txn(5, 200.0, "Move").copy(isTransfer = true),
                txn(6, 75.0, "Skip").copy(excluded = true)
            )
        )
        assertEquals(150.0, t.spend, 0.001)
        assertEquals(5000.0, t.income, 0.001)
        assertEquals(200.0, t.transfers, 0.001)
    }

    @Test
    fun `categories with identical totals both survive`() {
        // Regression: a sorted map keyed on value collapses ties into one entry.
        val t = Analytics.totals(
            listOf(
                txn(2, 250.0, "A", category = Cat.GROCERIES),
                txn(3, 250.0, "B", category = Cat.DINING),
                txn(4, 250.0, "C", category = Cat.SHOPPING),
                txn(5, 10.0, "D", category = Cat.TRAVEL)
            )
        )
        assertEquals(4, t.byCategory.size)
        assertEquals(760.0, t.spend, 0.001)
        assertEquals(t.byCategory.values.sortedDescending(), t.byCategory.values.toList())
    }

    @Test
    fun `detects a monthly subscription but not an irregular shop`() {
        val subs = Analytics.detectSubscriptions(
            listOf(
                txn(5, 56.0, "Netflix", category = Cat.SUBSCRIPTIONS),
                txn(35, 56.0, "Netflix", category = Cat.SUBSCRIPTIONS),
                txn(65, 56.0, "Netflix", category = Cat.SUBSCRIPTIONS),
                txn(95, 56.0, "Netflix", category = Cat.SUBSCRIPTIONS),
                txn(3, 210.0, "Carrefour", category = Cat.GROCERIES),
                txn(9, 65.0, "Carrefour", category = Cat.GROCERIES),
                txn(11, 430.0, "Carrefour", category = Cat.GROCERIES),
                txn(40, 120.0, "Carrefour", category = Cat.GROCERIES)
            )
        )
        assertEquals(1, subs.size)
        assertEquals("Netflix", subs[0].merchant)
        assertEquals(56.0, subs[0].typicalAmount, 0.001)
        assertTrue(Analytics.monthlyCost(subs[0]) in 52.0..60.0)
    }

    @Test
    fun `normalises a weekly charge to its monthly cost`() {
        val weekly = Analytics.detectSubscriptions(
            (0..5).map { txn(3 + it * 7, 25.0, "Gym Class") }
        )
        assertEquals(1, weekly.size)
        assertTrue(Analytics.monthlyCost(weekly[0]) in 95.0..115.0)
    }

    @Test
    fun `detects a recurring salary`() {
        val salary = Analytics.detectSalary(
            listOf(
                txn(5, 18500.0, "Salary", dir = Dir.CREDIT, category = Cat.INCOME),
                txn(35, 18500.0, "Salary", dir = Dir.CREDIT, category = Cat.INCOME),
                txn(65, 18500.0, "Salary", dir = Dir.CREDIT, category = Cat.INCOME),
                txn(20, 300.0, "Refund", dir = Dir.CREDIT, category = Cat.INCOME)
            )
        )
        assertNotNull(salary)
        assertEquals(18500.0, salary!!, 0.001)
    }

    @Test
    fun `median handles odd and even lengths`() {
        assertEquals(3.0, Analytics.median(listOf(1.0, 3.0, 5.0)), 0.001)
        assertEquals(3.0, Analytics.median(listOf(1.0, 2.0, 4.0, 5.0)), 0.001)
        assertEquals(0.0, Analytics.median(emptyList()), 0.001)
    }
}

class InsightEngineTest {

    private fun txn(daysAgo: Int, amount: Double, merchant: String, category: String, dir: String = Dir.DEBIT): Txn {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return Txn(
            id = daysAgo * 10000L + amount.toLong() + merchant.hashCode(),
            ts = cal.timeInMillis, amount = amount, direction = dir, merchant = merchant,
            merchantNorm = ParseEngine.normalizeMerchant(merchant), category = category,
            account = "4471", source = Src.SMS, raw = merchant,
            rawHash = "$daysAgo-$amount-$merchant", confidence = "MEDIUM"
        )
    }

    private fun dataset(): List<Txn> = buildList {
        repeat(3) { m -> repeat(8) { i -> add(txn(m * 30 + i * 3 + 2, 65.0, "Talabat", Cat.DINING)) } }
        repeat(10) { i -> add(txn(i * 2 + 1, 95.0, "Talabat", Cat.DINING)) }
        repeat(4) { m -> add(txn(m * 30 + 4, 56.0, "Netflix", Cat.SUBSCRIPTIONS)) }
        add(txn(6, 105.0, "Annual Fee", Cat.FEES))
        repeat(3) { m -> add(txn(m * 30 + 5, 18500.0, "Salary", Cat.INCOME, Dir.CREDIT)) }
        repeat(15) { i -> add(txn(i + 1, 22.0, "Costa Coffee $i", Cat.DINING)) }
    }

    @Test
    fun `flags budget breaches, fees and subscriptions`() {
        val cycle = Analytics.cycleFor(System.currentTimeMillis(), 1)
        val insights = InsightEngine.generate(
            all = dataset(), cycle = cycle, history = Analytics.recentCycles(6, 1),
            budgets = mapOf(Cat.DINING to 500.0),
            config = InsightEngine.Config(18500.0, 5000.0, 1)
        )
        assertTrue(insights.isNotEmpty())
        assertTrue(insights.any { it.id.startsWith("budget_") && it.category == Cat.DINING })
        assertTrue(insights.any { it.id == "bank_fees" })
        assertTrue(insights.any { it.id == "subs_total" })
        assertTrue(insights.all { it.title.isNotBlank() && it.detail.isNotBlank() })
        assertTrue(insights.all { (it.potentialSaving ?: 0.0) >= 0.0 })
        assertTrue(InsightEngine.totalOpportunity(insights) > 0)
    }

    @Test
    fun `severities are ordered most serious first`() {
        val cycle = Analytics.cycleFor(System.currentTimeMillis(), 1)
        val insights = InsightEngine.generate(
            all = dataset(), cycle = cycle, history = Analytics.recentCycles(6, 1),
            budgets = mapOf(Cat.DINING to 500.0),
            config = InsightEngine.Config(18500.0, 5000.0, 1)
        )
        assertTrue(insights.zipWithNext().all { (a, b) -> a.severity.ordinal <= b.severity.ordinal })
    }

    @Test
    fun `stays quiet with no data rather than inventing advice`() {
        val cycle = Analytics.cycleFor(System.currentTimeMillis(), 1)
        val quiet = InsightEngine.generate(
            all = emptyList(), cycle = cycle, history = Analytics.recentCycles(6, 1),
            budgets = emptyMap(), config = InsightEngine.Config(0.0, 0.0, 1)
        )
        assertTrue("expected silence, got ${quiet.map { it.id }}", quiet.size <= 1)
    }
}
