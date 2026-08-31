package com.zaghloul.masarif.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Single point of contact between capture, analysis and UI.
 *
 * Everything here blocks; callers run it off the main thread. UI observes
 * [revision], which ticks whenever stored data changes, and re-queries.
 */
class Repo private constructor(context: Context) {

    val db = MasarifDb.get(context)
    val prefs = Prefs(context)

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun bump() {
        _revision.value = _revision.value + 1
    }

    // Parsing runs on every incoming message; cache the rule set and categorizer.
    @Volatile
    private var rulesCache: List<ParseRule>? = null

    @Volatile
    private var categorizerCache: Categorizer? = null

    fun rules(): List<ParseRule> =
        rulesCache ?: synchronized(this) {
            rulesCache ?: db.rules().also { rulesCache = it }
        }

    fun categorizer(): Categorizer =
        categorizerCache ?: synchronized(this) {
            categorizerCache ?: Categorizer(db.merchantRules()).also { categorizerCache = it }
        }

    fun invalidateCaches() {
        synchronized(this) {
            rulesCache = null
            categorizerCache = null
        }
    }

    // ================================================================== intake

    sealed interface Intake {
        data class Stored(val txn: Txn) : Intake
        data object Duplicate : Intake
        data object Queued : Intake
        data class Ignored(val reason: String) : Intake
    }

    /**
     * The single entry point for every captured message, whatever its source.
     */
    fun intake(
        body: String,
        sender: String,
        ts: Long,
        source: String,
        sourcePkg: String = ""
    ): Intake {
        if (body.isBlank()) return Intake.Ignored("empty")
        if (sourcePkg.isNotBlank() && sourcePkg in prefs.mutedPackages) {
            return Intake.Ignored("muted app")
        }

        val outcome = ParseEngine.parse(
            body = body,
            sender = sender,
            arrivalTs = ts,
            rules = rules(),
            source = source,
            sourcePkg = sourcePkg
        )

        val parsed = outcome.txn
        if (parsed != null) {
            val category = categorizer().categorize(parsed.merchantNorm, parsed.raw, parsed.direction)
            val withCategory = parsed.copy(category = category)
            val id = db.insertTxn(withCategory)
            return if (id > 0) {
                db.bumpRuleHits(parsed.ruleId)
                bump()
                Intake.Stored(withCategory.copy(id = id))
            } else {
                Intake.Duplicate
            }
        }

        if (outcome.queueForReview) {
            val inserted = db.insertUnmatched(
                Unmatched(
                    ts = ts,
                    body = body,
                    source = source,
                    sourcePkg = sourcePkg,
                    sender = sender,
                    rawHash = ParseEngine.hash(body, sender)
                )
            )
            if (inserted > 0) bump()
            return Intake.Queued
        }

        return Intake.Ignored(outcome.reason)
    }

    // =============================================================== reprocess

    data class Reprocess(val recovered: Int, val stillUnmatched: Int)

    /** Re-runs everything in the review queue, usually after a new rule is saved. */
    fun reprocessUnmatched(): Reprocess {
        invalidateCaches()
        var recovered = 0
        val queue = db.unmatched(includeDismissed = false)
        for (u in queue) {
            val outcome = ParseEngine.parse(
                body = u.body,
                sender = u.sender,
                arrivalTs = u.ts,
                rules = rules(),
                source = u.source,
                sourcePkg = u.sourcePkg
            )
            val parsed = outcome.txn ?: continue
            val category = categorizer().categorize(parsed.merchantNorm, parsed.raw, parsed.direction)
            if (db.insertTxn(parsed.copy(category = category)) > 0) {
                db.bumpRuleHits(parsed.ruleId)
                db.deleteUnmatched(u.id)
                recovered++
            }
        }
        bump()
        return Reprocess(recovered, db.unmatchedCount())
    }

    /** Re-applies category rules to every stored transaction. */
    fun recategorizeAll(): Int {
        invalidateCaches()
        val c = categorizer()
        var changed = 0
        for (t in db.allTxns()) {
            val next = c.categorize(t.merchantNorm, t.raw, t.direction)
            if (next != t.category) {
                db.setCategory(t.id, next)
                changed++
            }
        }
        bump()
        return changed
    }

    // =================================================================== edits

    fun setCategory(txn: Txn, category: String, applyToMerchant: Boolean) {
        if (applyToMerchant && txn.merchantNorm.isNotBlank()) {
            db.setCategoryForMerchant(txn.merchantNorm, category)
            // Remember the choice so future transactions land in the right place.
            db.upsertMerchantRule(txn.merchantNorm, category, builtin = false)
            invalidateCaches()
        } else {
            db.setCategory(txn.id, category)
        }
        bump()
    }

    fun setFlags(id: Long, isTransfer: Boolean? = null, excluded: Boolean? = null) {
        db.setFlags(id, isTransfer, excluded)
        bump()
    }

    fun deleteTxn(id: Long) {
        db.deleteTxn(id)
        bump()
    }

    fun addManual(txn: Txn): Long {
        val category = categorizer().categorize(txn.merchantNorm, txn.raw, txn.direction)
        val id = db.insertTxn(txn.copy(category = category))
        bump()
        return id
    }

    fun saveRule(rule: ParseRule): Long {
        val id = db.upsertRule(rule)
        invalidateCaches()
        bump()
        return id
    }

    fun deleteRule(id: Long) {
        db.deleteRule(id)
        invalidateCaches()
        bump()
    }

    fun setBudget(category: String, limit: Double) {
        db.setBudget(category, limit)
        bump()
    }

    fun addMerchantRule(keyword: String, category: String) {
        db.upsertMerchantRule(keyword, category)
        invalidateCaches()
        bump()
    }

    fun deleteMerchantRule(id: Long) {
        db.deleteMerchantRule(id)
        invalidateCaches()
        bump()
    }

    fun dismissUnmatched(id: Long) {
        db.dismissUnmatched(id)
        bump()
    }

    // ================================================================== export

    fun exportCsv(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("date,amount,currency,direction,merchant,category,account,balance,")
        sb.append("source,confidence,transfer,excluded,note\n")
        for (t in db.allTxns()) {
            sb.append(fmt.format(t.ts)).append(',')
            sb.append(t.amount).append(',')
            sb.append(t.currency).append(',')
            sb.append(t.direction).append(',')
            sb.append(csv(t.merchant)).append(',')
            sb.append(csv(t.category)).append(',')
            sb.append(csv(t.account)).append(',')
            sb.append(t.balance?.toString() ?: "").append(',')
            sb.append(t.source).append(',')
            sb.append(t.confidence).append(',')
            sb.append(if (t.isTransfer) "yes" else "no").append(',')
            sb.append(if (t.excluded) "yes" else "no").append(',')
            sb.append(csv(t.note)).append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    companion object {
        @Volatile
        private var instance: Repo? = null

        fun get(context: Context): Repo =
            instance ?: synchronized(this) {
                instance ?: Repo(context.applicationContext).also { instance = it }
            }
    }
}
