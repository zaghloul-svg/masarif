package com.zaghloul.masarif.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite storage. No ORM and no annotation processor on purpose: it keeps
 * the build dependency-light and the queries explicit.
 *
 * All methods are blocking. Call them off the main thread (see [Repo]).
 */
class MasarifDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "masarif.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: MasarifDb? = null

        fun get(context: Context): MasarifDb =
            instance ?: synchronized(this) {
                instance ?: MasarifDb(context).also { instance = it }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE txn (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                amount REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'AED',
                direction TEXT NOT NULL,
                merchant TEXT NOT NULL,
                merchant_norm TEXT NOT NULL,
                category TEXT NOT NULL,
                account TEXT NOT NULL DEFAULT '',
                balance REAL,
                source TEXT NOT NULL,
                source_pkg TEXT NOT NULL DEFAULT '',
                raw TEXT NOT NULL,
                raw_hash TEXT NOT NULL UNIQUE,
                confidence TEXT NOT NULL,
                rule_id INTEGER NOT NULL DEFAULT -1,
                is_transfer INTEGER NOT NULL DEFAULT 0,
                excluded INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_txn_ts ON txn(ts DESC)")
        db.execSQL("CREATE INDEX idx_txn_cat ON txn(category)")
        db.execSQL("CREATE INDEX idx_txn_norm ON txn(merchant_norm)")
        db.execSQL("CREATE INDEX idx_txn_dedupe ON txn(amount, account, direction, ts)")

        db.execSQL(
            """
            CREATE TABLE unmatched (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                body TEXT NOT NULL,
                source TEXT NOT NULL,
                source_pkg TEXT NOT NULL DEFAULT '',
                sender TEXT NOT NULL DEFAULT '',
                raw_hash TEXT NOT NULL UNIQUE,
                dismissed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE rule (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                priority INTEGER NOT NULL DEFAULT 100,
                enabled INTEGER NOT NULL DEFAULT 1,
                pattern TEXT NOT NULL,
                g_amount INTEGER NOT NULL DEFAULT -1,
                g_merchant INTEGER NOT NULL DEFAULT -1,
                g_account INTEGER NOT NULL DEFAULT -1,
                g_balance INTEGER NOT NULL DEFAULT -1,
                g_currency INTEGER NOT NULL DEFAULT -1,
                direction TEXT NOT NULL DEFAULT 'AUTO',
                sender_filter TEXT NOT NULL DEFAULT '',
                builtin INTEGER NOT NULL DEFAULT 0,
                hits INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE merchant_rule (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword TEXT NOT NULL UNIQUE,
                category TEXT NOT NULL,
                builtin INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL("CREATE TABLE budget (category TEXT PRIMARY KEY, monthly_limit REAL NOT NULL)")
        db.execSQL("CREATE TABLE setting (k TEXT PRIMARY KEY, v TEXT NOT NULL)")

        SeedData.merchantRules().forEach { (keyword, category) ->
            db.insertWithOnConflict(
                "merchant_rule", null,
                ContentValues().apply {
                    put("keyword", keyword)
                    put("category", category)
                    put("builtin", 1)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Single schema version so far; nothing to migrate yet.
    }

    // ============================================================ transactions

    /** @return row id, or -1 when the alert was already stored. */
    fun insertTxn(t: Txn): Long {
        val db = writableDatabase
        if (findDuplicate(t) != null) return -1L
        return db.insertWithOnConflict("txn", null, t.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * A bank often sends the same purchase twice: once by SMS and once as an app
     * push. Same amount, account and direction within a few minutes is one event.
     */
    fun findDuplicate(t: Txn, windowMs: Long = 4 * 60 * 1000L): Long? {
        readableDatabase.rawQuery(
            "SELECT id FROM txn WHERE raw_hash = ? LIMIT 1",
            arrayOf(t.rawHash)
        ).use { if (it.moveToFirst()) return it.getLong(0) }

        readableDatabase.rawQuery(
            """
            SELECT id FROM txn
            WHERE ABS(amount - ?) < 0.005
              AND direction = ?
              AND account = ?
              AND ABS(ts - ?) <= ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                t.amount.toString(), t.direction, t.account,
                t.ts.toString(), windowMs.toString()
            )
        ).use { if (it.moveToFirst()) return it.getLong(0) }

        return null
    }

    fun txnsBetween(fromTs: Long, toTs: Long): List<Txn> =
        readableDatabase.rawQuery(
            "SELECT * FROM txn WHERE ts >= ? AND ts < ? ORDER BY ts DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { it.readTxns() }

    fun recentTxns(limit: Int = 200): List<Txn> =
        readableDatabase.rawQuery(
            "SELECT * FROM txn ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())
        ).use { it.readTxns() }

    fun allTxns(): List<Txn> =
        readableDatabase.rawQuery("SELECT * FROM txn ORDER BY ts DESC", null).use { it.readTxns() }

    fun searchTxns(query: String, category: String?, fromTs: Long?, toTs: Long?): List<Txn> {
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            where.append(" AND (merchant LIKE ? OR raw LIKE ? OR note LIKE ?)")
            val q = "%$query%"
            args += listOf(q, q, q)
        }
        if (!category.isNullOrBlank()) {
            where.append(" AND category = ?"); args += category
        }
        if (fromTs != null) {
            where.append(" AND ts >= ?"); args += fromTs.toString()
        }
        if (toTs != null) {
            where.append(" AND ts < ?"); args += toTs.toString()
        }
        return readableDatabase.rawQuery(
            "SELECT * FROM txn WHERE $where ORDER BY ts DESC LIMIT 1000", args.toTypedArray()
        ).use { it.readTxns() }
    }

    fun updateTxn(t: Txn): Int =
        writableDatabase.update("txn", t.toValues(), "id = ?", arrayOf(t.id.toString()))

    fun setCategory(id: Long, category: String): Int =
        writableDatabase.update(
            "txn", ContentValues().apply { put("category", category) },
            "id = ?", arrayOf(id.toString())
        )

    /** Applies a category to every past transaction from the same merchant. */
    fun setCategoryForMerchant(merchantNorm: String, category: String): Int =
        writableDatabase.update(
            "txn", ContentValues().apply { put("category", category) },
            "merchant_norm = ?", arrayOf(merchantNorm)
        )

    fun setFlags(id: Long, isTransfer: Boolean?, excluded: Boolean?): Int {
        val cv = ContentValues()
        isTransfer?.let { cv.put("is_transfer", if (it) 1 else 0) }
        excluded?.let { cv.put("excluded", if (it) 1 else 0) }
        if (cv.size() == 0) return 0
        return writableDatabase.update("txn", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteTxn(id: Long): Int =
        writableDatabase.delete("txn", "id = ?", arrayOf(id.toString()))

    fun deleteAllTxns() {
        writableDatabase.delete("txn", null, null)
    }

    fun txnCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM txn", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun earliestTs(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM txn", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    /** Distinct source packages seen, so Settings can offer to mute noisy apps. */
    fun seenPackages(): List<String> =
        readableDatabase.rawQuery(
            "SELECT source_pkg, COUNT(*) c FROM txn WHERE source_pkg <> '' GROUP BY source_pkg ORDER BY c DESC",
            null
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    // =============================================================== unmatched

    fun insertUnmatched(u: Unmatched): Long =
        writableDatabase.insertWithOnConflict(
            "unmatched", null,
            ContentValues().apply {
                put("ts", u.ts)
                put("body", u.body)
                put("source", u.source)
                put("source_pkg", u.sourcePkg)
                put("sender", u.sender)
                put("raw_hash", u.rawHash)
                put("dismissed", if (u.dismissed) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )

    fun unmatched(includeDismissed: Boolean = false): List<Unmatched> =
        readableDatabase.rawQuery(
            "SELECT * FROM unmatched ${if (includeDismissed) "" else "WHERE dismissed = 0"} " +
                "ORDER BY ts DESC LIMIT 500",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Unmatched(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                            body = c.getString(c.getColumnIndexOrThrow("body")),
                            source = c.getString(c.getColumnIndexOrThrow("source")),
                            sourcePkg = c.getString(c.getColumnIndexOrThrow("source_pkg")),
                            sender = c.getString(c.getColumnIndexOrThrow("sender")),
                            rawHash = c.getString(c.getColumnIndexOrThrow("raw_hash")),
                            dismissed = c.getInt(c.getColumnIndexOrThrow("dismissed")) == 1
                        )
                    )
                }
            }
        }

    fun unmatchedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM unmatched WHERE dismissed = 0", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun dismissUnmatched(id: Long): Int =
        writableDatabase.update(
            "unmatched", ContentValues().apply { put("dismissed", 1) },
            "id = ?", arrayOf(id.toString())
        )

    fun deleteUnmatched(id: Long): Int =
        writableDatabase.delete("unmatched", "id = ?", arrayOf(id.toString()))

    fun clearUnmatched() {
        writableDatabase.delete("unmatched", null, null)
    }

    // =================================================================== rules

    fun rules(): List<ParseRule> =
        readableDatabase.rawQuery("SELECT * FROM rule ORDER BY priority DESC, id ASC", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            ParseRule(
                                id = c.getLong(c.getColumnIndexOrThrow("id")),
                                name = c.getString(c.getColumnIndexOrThrow("name")),
                                priority = c.getInt(c.getColumnIndexOrThrow("priority")),
                                enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                                pattern = c.getString(c.getColumnIndexOrThrow("pattern")),
                                gAmount = c.getInt(c.getColumnIndexOrThrow("g_amount")),
                                gMerchant = c.getInt(c.getColumnIndexOrThrow("g_merchant")),
                                gAccount = c.getInt(c.getColumnIndexOrThrow("g_account")),
                                gBalance = c.getInt(c.getColumnIndexOrThrow("g_balance")),
                                gCurrency = c.getInt(c.getColumnIndexOrThrow("g_currency")),
                                direction = c.getString(c.getColumnIndexOrThrow("direction")),
                                senderFilter = c.getString(c.getColumnIndexOrThrow("sender_filter")),
                                builtin = c.getInt(c.getColumnIndexOrThrow("builtin")) == 1,
                                hits = c.getInt(c.getColumnIndexOrThrow("hits"))
                            )
                        )
                    }
                }
            }

    fun upsertRule(r: ParseRule): Long {
        val cv = ContentValues().apply {
            put("name", r.name)
            put("priority", r.priority)
            put("enabled", if (r.enabled) 1 else 0)
            put("pattern", r.pattern)
            put("g_amount", r.gAmount)
            put("g_merchant", r.gMerchant)
            put("g_account", r.gAccount)
            put("g_balance", r.gBalance)
            put("g_currency", r.gCurrency)
            put("direction", r.direction)
            put("sender_filter", r.senderFilter)
            put("builtin", if (r.builtin) 1 else 0)
            put("hits", r.hits)
        }
        return if (r.id > 0) {
            writableDatabase.update("rule", cv, "id = ?", arrayOf(r.id.toString()))
            r.id
        } else {
            writableDatabase.insert("rule", null, cv)
        }
    }

    fun deleteRule(id: Long): Int =
        writableDatabase.delete("rule", "id = ?", arrayOf(id.toString()))

    fun bumpRuleHits(id: Long) {
        if (id <= 0) return
        writableDatabase.execSQL("UPDATE rule SET hits = hits + 1 WHERE id = ?", arrayOf<Any>(id))
    }

    // ========================================================= merchant rules

    fun merchantRules(): List<MerchantRule> =
        readableDatabase.rawQuery(
            "SELECT * FROM merchant_rule ORDER BY builtin ASC, LENGTH(keyword) DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        MerchantRule(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            keyword = c.getString(c.getColumnIndexOrThrow("keyword")),
                            category = c.getString(c.getColumnIndexOrThrow("category")),
                            builtin = c.getInt(c.getColumnIndexOrThrow("builtin")) == 1
                        )
                    )
                }
            }
        }

    fun upsertMerchantRule(keyword: String, category: String, builtin: Boolean = false): Long =
        writableDatabase.insertWithOnConflict(
            "merchant_rule", null,
            ContentValues().apply {
                put("keyword", keyword.uppercase())
                put("category", category)
                put("builtin", if (builtin) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )

    fun deleteMerchantRule(id: Long): Int =
        writableDatabase.delete("merchant_rule", "id = ?", arrayOf(id.toString()))

    // ================================================================ budgets

    fun budgets(): Map<String, Double> =
        readableDatabase.rawQuery("SELECT category, monthly_limit FROM budget", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getDouble(1)) }
        }

    fun setBudget(category: String, limit: Double) {
        if (limit <= 0.0) {
            writableDatabase.delete("budget", "category = ?", arrayOf(category))
            return
        }
        writableDatabase.insertWithOnConflict(
            "budget", null,
            ContentValues().apply {
                put("category", category)
                put("monthly_limit", limit)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // =============================================================== settings

    fun getSetting(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM setting WHERE k = ?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setSetting(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "setting", null,
            ContentValues().apply {
                put("k", key)
                put("v", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ================================================================ mapping

    private fun Txn.toValues() = ContentValues().apply {
        put("ts", ts)
        put("amount", amount)
        put("currency", currency)
        put("direction", direction)
        put("merchant", merchant)
        put("merchant_norm", merchantNorm)
        put("category", category)
        put("account", account)
        if (balance == null) putNull("balance") else put("balance", balance)
        put("source", source)
        put("source_pkg", sourcePkg)
        put("raw", raw)
        put("raw_hash", rawHash)
        put("confidence", confidence)
        put("rule_id", ruleId)
        put("is_transfer", if (isTransfer) 1 else 0)
        put("excluded", if (excluded) 1 else 0)
        put("note", note)
        put("created_at", createdAt)
    }

    private fun Cursor.readTxns(): List<Txn> = buildList {
        val iId = getColumnIndexOrThrow("id")
        val iTs = getColumnIndexOrThrow("ts")
        val iAmount = getColumnIndexOrThrow("amount")
        val iCurrency = getColumnIndexOrThrow("currency")
        val iDirection = getColumnIndexOrThrow("direction")
        val iMerchant = getColumnIndexOrThrow("merchant")
        val iNorm = getColumnIndexOrThrow("merchant_norm")
        val iCategory = getColumnIndexOrThrow("category")
        val iAccount = getColumnIndexOrThrow("account")
        val iBalance = getColumnIndexOrThrow("balance")
        val iSource = getColumnIndexOrThrow("source")
        val iPkg = getColumnIndexOrThrow("source_pkg")
        val iRaw = getColumnIndexOrThrow("raw")
        val iHash = getColumnIndexOrThrow("raw_hash")
        val iConf = getColumnIndexOrThrow("confidence")
        val iRule = getColumnIndexOrThrow("rule_id")
        val iTransfer = getColumnIndexOrThrow("is_transfer")
        val iExcluded = getColumnIndexOrThrow("excluded")
        val iNote = getColumnIndexOrThrow("note")
        val iCreated = getColumnIndexOrThrow("created_at")
        while (moveToNext()) {
            add(
                Txn(
                    id = getLong(iId),
                    ts = getLong(iTs),
                    amount = getDouble(iAmount),
                    currency = getString(iCurrency),
                    direction = getString(iDirection),
                    merchant = getString(iMerchant),
                    merchantNorm = getString(iNorm),
                    category = getString(iCategory),
                    account = getString(iAccount),
                    balance = if (isNull(iBalance)) null else getDouble(iBalance),
                    source = getString(iSource),
                    sourcePkg = getString(iPkg),
                    raw = getString(iRaw),
                    rawHash = getString(iHash),
                    confidence = getString(iConf),
                    ruleId = getLong(iRule),
                    isTransfer = getInt(iTransfer) == 1,
                    excluded = getInt(iExcluded) == 1,
                    note = getString(iNote),
                    createdAt = getLong(iCreated)
                )
            )
        }
    }
}
