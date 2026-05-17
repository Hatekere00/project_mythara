package com.mythara.ui.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.MytharaColors

/**
 * 7-day (or N-day) sparkline stack for the About-Me health panel.
 *
 * Renders one mini Canvas per metric we know how to plot from the
 * `kind:health-snapshot` rows that [com.mythara.health.HealthLearningWorker]
 * writes every 6 hours. Each row has the day label on the left, the
 * line + dot points in the middle, and the latest value labeled on
 * the right.
 *
 * Visually deliberate:
 *  - JetBrainsMono labels match the rest of the Mythara minimal
 *    aesthetic.
 *  - One brand colour per metric (Charple HR, Bok sleep, Citron
 *    kcal, Malibu steps, Julep weight) so the eye can map a
 *    glyph to a meaning instantly.
 *  - Gaps in the data (days with no snapshot) leave the line
 *    interrupted instead of drawing through zero — important for
 *    weight where a missing day shouldn't drag the line to 0 kg.
 *
 * A standalone composable so the AboutMeScreen body just embeds it
 * and we can reuse the same renderer elsewhere (e.g. a future
 * dashboard tile) without re-deriving the math.
 */
@Composable
fun HealthGraph(
    series: HealthSeries,
    modifier: Modifier = Modifier,
) {
    if (series.points.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Last ${series.windowDays} days",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        // One row per metric we have any data for. Order matches
        // the user's likely scanning priority: HR first (most
        // dynamic), then sleep, then activity (kcal / steps),
        // then weight (slow-changing) last.
        MetricRow(
            label = "HR",
            unit = "bpm",
            color = MytharaColors.Charple,
            series = series.points.map { it.hrAvg },
            xLabels = series.points.map { it.dayLabel },
            band = series.points.map { Pair(it.hrMin, it.hrMax) },
        )
        Spacer(Modifier.height(10.dp))
        MetricRow(
            label = "sleep",
            unit = "h",
            color = MytharaColors.Bok,
            series = series.points.map { it.sleepHours },
            xLabels = series.points.map { it.dayLabel },
            decimals = 1,
        )
        Spacer(Modifier.height(10.dp))
        MetricRow(
            label = "kcal",
            unit = "",
            color = MytharaColors.Citron,
            series = series.points.map { it.kcal },
            xLabels = series.points.map { it.dayLabel },
        )
        Spacer(Modifier.height(10.dp))
        MetricRow(
            label = "steps",
            unit = "",
            color = MytharaColors.Malibu,
            series = series.points.map { it.steps },
            xLabels = series.points.map { it.dayLabel },
        )
        if (series.points.any { it.weightKg != null }) {
            Spacer(Modifier.height(10.dp))
            MetricRow(
                label = "weight",
                unit = "kg",
                color = MytharaColors.Julep,
                series = series.points.map { it.weightKg },
                xLabels = series.points.map { it.dayLabel },
                decimals = 1,
            )
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    unit: String,
    color: Color,
    series: List<Double?>,
    xLabels: List<String>,
    decimals: Int = 0,
    band: List<Pair<Double?, Double?>>? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MytharaColors.FgDim,
        fontSize = 10.sp,
    )
    val valueStyle = MaterialTheme.typography.bodySmall.copy(
        color = color,
        fontWeight = FontWeight.Bold,
    )

    val present = series.count { it != null }
    val latest = series.lastOrNull { it != null }
    val latestText = latest?.let {
        when (decimals) {
            0 -> it.toInt().toString()
            else -> "%.${decimals}f".format(it)
        } + if (unit.isNotEmpty()) " $unit" else ""
    } ?: "—"

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header line: glyph label + current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.weight(1f))
            Text(text = latestText, style = valueStyle)
        }
        Spacer(Modifier.height(3.dp))

        // The sparkline itself
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MytharaColors.SurfaceMid.copy(alpha = 0.4f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            if (present == 0) {
                Text(
                    text = "no data yet",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(modifier = Modifier.fillMaxWidth().height(GRAPH_HEIGHT_DP.dp)) {
                    val w = size.width
                    val h = size.height - X_AXIS_TAGS_HEIGHT_PX

                    // Y range — auto-scale to the actual values
                    // with a small headroom so the line never
                    // touches the top edge.
                    val finite = series.filterNotNull()
                    val yMin = finite.min()
                    val yMax = finite.max()
                    val pad = ((yMax - yMin) * 0.15).coerceAtLeast(0.5)
                    val yLo = (yMin - pad).coerceAtLeast(0.0)
                    val yHi = yMax + pad
                    val ySpan = (yHi - yLo).coerceAtLeast(0.01)

                    fun xAt(i: Int) = if (series.size == 1) w / 2f
                        else w * i / (series.size - 1).toFloat()
                    fun yAt(v: Double) = (h * (1f - ((v - yLo) / ySpan)).toFloat())
                        .coerceIn(0f, h)

                    // Optional min-max shaded band for HR.
                    if (band != null) {
                        val bandPath = Path()
                        var first = true
                        for (i in series.indices) {
                            val lo = band[i].first ?: continue
                            val x = xAt(i)
                            val y = yAt(lo)
                            if (first) {
                                bandPath.moveTo(x, y); first = false
                            } else bandPath.lineTo(x, y)
                        }
                        for (i in series.indices.reversed()) {
                            val hi = band[i].second ?: continue
                            val x = xAt(i)
                            val y = yAt(hi)
                            bandPath.lineTo(x, y)
                        }
                        bandPath.close()
                        drawPath(bandPath, color = color.copy(alpha = 0.12f))
                    }

                    // Main line — break the path at any null gap.
                    val path = Path()
                    var pen = false
                    for (i in series.indices) {
                        val v = series[i]
                        if (v == null) { pen = false; continue }
                        val x = xAt(i)
                        val y = yAt(v)
                        if (!pen) { path.moveTo(x, y); pen = true }
                        else path.lineTo(x, y)
                    }
                    drawPath(path, color = color, style = Stroke(width = 2.5f))

                    // Dot per data point.
                    for (i in series.indices) {
                        val v = series[i] ?: continue
                        drawCircle(
                            color = color,
                            radius = 3f,
                            center = Offset(xAt(i), yAt(v)),
                        )
                    }

                    // X-axis day labels — first / mid / last only
                    // (a 7-point chart with all 7 labels is unreadable).
                    val tickIndices = listOf(0, series.size / 2, series.size - 1).distinct()
                    for (i in tickIndices) {
                        val text = xLabels[i]
                        val layout = measurer.measure(
                            text = text,
                            style = TextStyle(color = MytharaColors.FgMute, fontSize = 9.sp),
                        )
                        val x = (xAt(i) - layout.size.width / 2f)
                            .coerceIn(0f, w - layout.size.width)
                        drawText(
                            layout,
                            topLeft = Offset(x, size.height - layout.size.height),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Daily-aggregated health data for the AboutMe panel.
 *
 * `points` is ordered oldest → newest with one entry per calendar
 * day in the window. Days with no health-snapshot row in the vault
 * appear as a [DayPoint] with all metrics null — the graph leaves
 * a gap rather than drawing a fake zero.
 */
data class HealthSeries(
    val windowDays: Int,
    val points: List<DayPoint>,
) {
    data class DayPoint(
        /** "Mon" / "Tue" / ... — short day-of-week label for x-axis. */
        val dayLabel: String,
        /** "May 11" — full date for tooltips/captions. */
        val dateLabel: String,
        val hrAvg: Double?,
        val hrMin: Double?,
        val hrMax: Double?,
        val sleepHours: Double?,
        val kcal: Double?,
        val steps: Double?,
        val weightKg: Double?,
    )
}

private const val GRAPH_HEIGHT_DP = 64
private const val X_AXIS_TAGS_HEIGHT_PX = 18f
