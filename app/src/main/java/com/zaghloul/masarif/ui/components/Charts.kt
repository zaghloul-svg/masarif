package com.zaghloul.masarif.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaghloul.masarif.ui.theme.MasarifColors
import com.zaghloul.masarif.util.Fmt
import kotlin.math.max
import kotlin.math.min

/**
 * Charts are drawn on a plain Canvas rather than pulled from a charting library:
 * fewer dependencies, and full control over the mark specs below.
 *
 * Shared rules: thin marks, a 2dp surface gap between adjacent fills, 4dp rounded
 * data-ends anchored to the baseline, recessive axes, and no value printed on every
 * mark — the labelled breakdown beside each chart carries the numbers.
 */

data class Slice(val label: String, val value: Double, val color: Color)

@Composable
fun DonutChart(
    slices: List<Slice>,
    centerValue: String,
    centerLabel: String,
    modifier: Modifier = Modifier
) {
    val total = slices.sumOf { it.value }
    val emptyTrack = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val stroke = 26.dp.toPx()
            val diameter = min(size.width, size.height) - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            if (total <= 0.0) {
                drawArc(
                    color = emptyTrack,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                return@Canvas
            }

            // A 2dp gap in surface colour separates neighbouring segments.
            val gapDegrees = if (slices.size > 1) 1.6f else 0f
            var start = -90f
            for (s in slices) {
                val sweep = (s.value / total * 360.0).toFloat()
                val drawn = max(0.8f, sweep - gapDegrees)
                drawArc(
                    color = s.color,
                    startAngle = start,
                    sweepAngle = drawn,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                start += sweep
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerValue, style = MaterialTheme.typography.headlineSmall)
            Text(
                centerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The labelled breakdown that accompanies every donut. This is the legend: it
 * carries name and value as text, so identity never rests on colour alone.
 */
@Composable
fun CategoryBreakdown(
    entries: List<Pair<String, Double>>,
    total: Double,
    currency: String,
    modifier: Modifier = Modifier,
    onClick: ((String) -> Unit)? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { (category, amount) ->
            val share = if (total > 0) amount / total else 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            color = MasarifColors.category(category),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    category,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Fmt.pct(share),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    Fmt.money(amount, currency),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Cumulative spend through the cycle against an even-pace reference.
 * The reference line is what makes the actual line mean something.
 */
@Composable
fun BurnChart(
    cumulative: List<Double>,
    cycleDays: Int,
    elapsedDays: Int,
    ceiling: Double?,
    modifier: Modifier = Modifier
) {
    val line = MaterialTheme.colorScheme.primary
    val reference = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface

    val maxValue = max(
        cumulative.maxOrNull() ?: 0.0,
        ceiling ?: 0.0
    ).takeIf { it > 0 } ?: 1.0

    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val padL = 4.dp.toPx()
        val padR = 8.dp.toPx()
        val padT = 10.dp.toPx()
        val padB = 12.dp.toPx()
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        if (w <= 0 || h <= 0) return@Canvas

        fun x(day: Int) = padL + w * (day.toFloat() / max(1, cycleDays - 1))
        fun y(v: Double) = padT + h * (1f - (v / maxValue).toFloat()).coerceIn(0f, 1f)

        // Recessive baseline only; no full grid.
        drawLine(
            color = grid,
            start = Offset(padL, padT + h),
            end = Offset(padL + w, padT + h),
            strokeWidth = 1.dp.toPx()
        )

        // Even-pace reference: where you would be spending the ceiling evenly.
        if (ceiling != null && ceiling > 0) {
            drawLine(
                color = reference,
                start = Offset(x(0), y(0.0)),
                end = Offset(x(cycleDays - 1), y(ceiling)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(6.dp.toPx(), 6.dp.toPx())
                )
            )
        }

        if (cumulative.isEmpty()) return@Canvas

        val points = cumulative.take(max(1, elapsedDays)).mapIndexed { i, v ->
            Offset(x(i), y(v))
        }

        // Soft fill under the line gives the shape weight without competing with it.
        if (points.size > 1) {
            val fill = Path().apply {
                moveTo(points.first().x, padT + h)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, padT + h)
                close()
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0.02f))
                )
            )
        }

        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color = line, style = Stroke(width = 2.dp.toPx()))
        }

        // A single marker on today's value; never a dot on every point.
        points.lastOrNull()?.let { last ->
            drawCircle(surface, radius = 6.dp.toPx(), center = last)
            drawCircle(line, radius = 4.5.dp.toPx(), center = last)
        }
    }
}

/**
 * Spend per cycle. The selected cycle is emphasised; the rest are recessive, so
 * the chart answers "is this month unusual" at a glance.
 */
@Composable
fun HistoryBars(
    data: List<Pair<String, Double>>,
    selectedIndex: Int,
    currency: String,
    modifier: Modifier = Modifier
) {
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxValue = data.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            if (data.isEmpty()) return@Canvas
            val padB = 2.dp.toPx()
            val h = size.height - padB
            // A 2dp surface gap between adjacent bars.
            val gap = 2.dp.toPx()
            val slot = size.width / data.size
            val barW = max(3.dp.toPx(), slot - gap * 2)
            val radius = 4.dp.toPx()

            drawLine(
                color = grid,
                start = Offset(0f, h),
                end = Offset(size.width, h),
                strokeWidth = 1.dp.toPx()
            )

            data.forEachIndexed { i, (_, value) ->
                val barH = ((value / maxValue) * (h - 6.dp.toPx())).toFloat().coerceAtLeast(2f)
                val left = i * slot + (slot - barW) / 2f
                val top = h - barH
                drawPath(
                    path = topRoundedBar(Rect(left, top, left + barW, h), radius),
                    color = if (i == selectedIndex) active else idle
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            data.forEachIndexed { i, (label, _) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selectedIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** Bar with rounded top corners, square where it meets the baseline. */
private fun DrawScope.topRoundedBar(rect: Rect, radius: Float): Path {
    val r = min(radius, rect.width / 2f).coerceAtMost(rect.height)
    return Path().apply {
        moveTo(rect.left, rect.bottom)
        lineTo(rect.left, rect.top + r)
        quadraticBezierTo(rect.left, rect.top, rect.left + r, rect.top)
        lineTo(rect.right - r, rect.top)
        quadraticBezierTo(rect.right, rect.top, rect.right, rect.top + r)
        lineTo(rect.right, rect.bottom)
        close()
    }
}

/** Horizontal progress meter used for budgets. */
@Composable
fun BudgetMeter(
    spent: Double,
    limit: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val over = MasarifColors.negative
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = size.height / 2f
        drawRoundRect(
            color = track,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
        if (limit <= 0) return@Canvas
        val fraction = (spent / limit).coerceIn(0.0, 1.0).toFloat()
        if (fraction > 0f) {
            drawRoundRect(
                color = if (spent > limit) over else color,
                size = Size(size.width * fraction, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
            )
        }
    }
}
