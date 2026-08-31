package com.zaghloul.masarif.analysis

import com.zaghloul.masarif.data.Insight
import com.zaghloul.masarif.data.Prefs
import com.zaghloul.masarif.data.Txn
import com.zaghloul.masarif.util.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Optional narrative coaching on top of the on-device insights.
 *
 * What leaves the phone: category totals, income, the computed insight headlines,
 * and (only if you switch it on) merchant names. Never sent: raw alert text, card
 * or account numbers, balances, or individual transaction timestamps.
 *
 * Uses HttpURLConnection so the app carries no HTTP dependency.
 */
object LlmCoach {

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Error(val message: String) : Result
    }

    private const val SYSTEM = """You are a personal finance coach for someone living in the UAE.
All amounts are in AED. You are given an anonymised monthly spending summary.

Write practical, specific advice in under 250 words:
- Lead with the single biggest opportunity and name the number.
- Give 3-4 concrete actions, each tied to a figure from the data.
- Use UAE context where relevant (Salik, DEWA/SEWA, ADNOC/ENOC, delivery apps,
  bank fee waivers, salary transfer offers).
- Be direct. No preamble, no restating the data back, no generic tips like "make a budget".
- If the data shows the person is doing well, say so briefly rather than inventing problems.

Never invent figures that are not in the data."""

    fun buildPrompt(
        cycleLabel: String,
        txns: List<Txn>,
        insights: List<Insight>,
        income: Double,
        savingsTarget: Double,
        shareMerchants: Boolean,
        currency: String = "AED"
    ): String {
        val totals = Analytics.totals(txns)
        val sb = StringBuilder()

        sb.append("Period: ").append(cycleLabel).append('\n')
        if (income > 0) sb.append("Monthly income: ").append(Fmt.money(income, currency)).append('\n')
        if (savingsTarget > 0) {
            sb.append("Savings target: ").append(Fmt.money(savingsTarget, currency)).append('\n')
        }
        sb.append("Total spent: ").append(Fmt.money(totals.spend, currency)).append('\n')
        sb.append("Transactions: ").append(totals.count).append("\n\n")

        sb.append("Spending by category:\n")
        totals.byCategory.entries
            .sortedByDescending { it.value }
            .forEach { (cat, amount) ->
                val share = if (totals.spend > 0) amount / totals.spend else 0.0
                sb.append("- ").append(cat).append(": ")
                    .append(Fmt.money(amount, currency))
                    .append(" (").append(Fmt.pct(share)).append(")\n")
            }

        if (shareMerchants) {
            sb.append("\nTop merchants:\n")
            Analytics.topMerchants(txns, 10).forEach { (m, amount) ->
                sb.append("- ").append(m).append(": ").append(Fmt.money(amount, currency)).append('\n')
            }
        }

        val subs = Analytics.detectSubscriptions(txns)
        if (subs.isNotEmpty()) {
            sb.append("\nRecurring charges (monthly equivalent):\n")
            subs.take(10).forEach {
                val name = if (shareMerchants) it.merchant else it.category
                sb.append("- ").append(name).append(": ")
                    .append(Fmt.money(Analytics.monthlyCost(it), currency)).append('\n')
            }
        }

        if (insights.isNotEmpty()) {
            sb.append("\nDetected patterns:\n")
            insights.take(8).forEach {
                sb.append("- ").append(it.title)
                it.potentialSaving?.let { s ->
                    sb.append(" (opportunity ").append(Fmt.money(s, currency)).append(')')
                }
                sb.append('\n')
            }
        }

        return sb.toString()
    }

    fun request(prefs: Prefs, prompt: String): Result {
        val key = prefs.llmApiKey
        if (key.isBlank()) return Result.Error("No API key saved. Add one in Settings.")

        return try {
            when (prefs.llmProvider) {
                "anthropic" -> anthropic(
                    key,
                    prefs.llmModel.ifBlank { Prefs.DEFAULT_ANTHROPIC_MODEL },
                    prompt
                )
                "openai" -> openai(
                    key,
                    prefs.llmModel.ifBlank { Prefs.DEFAULT_OPENAI_MODEL },
                    prompt
                )
                else -> Result.Error("Pick a provider in Settings first.")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Request failed")
        }
    }

    // ------------------------------------------------------------- providers

    private fun anthropic(key: String, model: String, prompt: String): Result {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 900)
            put("system", SYSTEM)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            )
        }

        val (code, text) = post(
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json"
            ),
            payload = body.toString()
        )

        if (code !in 200..299) return Result.Error(apiError(text, code))

        val content = JSONObject(text).optJSONArray("content") ?: return Result.Error("Empty response")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") sb.append(part.optString("text"))
        }
        val out = sb.toString().trim()
        return if (out.isBlank()) Result.Error("Empty response") else Result.Ok(out)
    }

    private fun openai(key: String, model: String, prompt: String): Result {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 900)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM)
                    })
                    .put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
            )
        }

        val (code, text) = post(
            url = "https://api.openai.com/v1/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "content-type" to "application/json"
            ),
            payload = body.toString()
        )

        if (code !in 200..299) return Result.Error(apiError(text, code))

        val choices = JSONObject(text).optJSONArray("choices") ?: return Result.Error("Empty response")
        val msg = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        return if (msg.isBlank()) Result.Error("Empty response") else Result.Ok(msg.trim())
    }

    // ----------------------------------------------------------------- http

    private fun post(
        url: String,
        headers: Map<String, String>,
        payload: String
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        }.orEmpty()

        conn.disconnect()
        return code to text
    }

    private fun apiError(body: String, code: Int): String = try {
        val msg = JSONObject(body).optJSONObject("error")?.optString("message")
        if (msg.isNullOrBlank()) "Request failed ($code)" else msg
    } catch (_: Exception) {
        when (code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> "API key rejected"
            HttpURLConnection.HTTP_PAYMENT_REQUIRED -> "Billing issue on the API account"
            429 -> "Rate limited — try again shortly"
            else -> "Request failed ($code)"
        }
    }
}
