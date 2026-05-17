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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raw-sample timeline plot for the About-Me health panel.
 *
 * Plots every individual reading Health Connect returned in the
 * configured window (default last 24h) as its own (timestamp, value)
 * point — NOT a daily aggregate. The x-axis is real time;
 * sample density tells you when the sensor was reading.
 *
 * One Canvas per metric, stacked vertically:
 *  - HR     · Charple · scatter + thin line between consecutive samples
 *  - sleep  · Bok     · horizontal bars at each session's [start, end]
 *  - kcal   · Citron  · per-record dot at the record's end time
 *  - steps  · Malibu  · per-record dot
 *  - weight · Julep   · scatter dots only (typically 1-2 readings / day)
 *
 * X-axis ticks are wall-clock-relative: "−24h", "−18h", "−12h",
 * "−6h", "now" — easier to read against "what was I doing then"
 * than absolute clock time.
 *
 * Gaps in the data leave the line interrupted (HR) or just an
 * empty band (other metrics).
 */
@Composable
fun HealthGraph(
    series: HealthSeries,
    modifier: Modifier = Modifier,
) {
    if (!series.hasAnyData) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Past ${series.windowHours}h · raw samples",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        MetricRow(
            label = "HR",
            unit = "bpm",
            color = MytharaColors.Charple,
            samples = series.hr,
            windowStartMs = series.startMs,
            windowEndMs = series.endMs,
            connectLine = true,
        )
        if (series.sleepSessions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SleepRow(
                color = MytharaColors.Bok,
                sessions = series.sleepSessions,
                windowStartMs = series.startMs,
                windowEndMs = series.endMs,
            )
        }
        if (series.kcal.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MetricRow(
                label = "kcal",
                unit = "",
                color = MytharaColors.Citron,
                samples = series.kcal,
                windowStartMs = series.startMs,
                windowEndMs = series.endMs,
                connectLine = false,
            )
        }
        if (series.steps.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MetricRow(
                label = "steps",
                unit = "",
                color = MytharaColors.Malibu,
                samples = series.steps,
                windowStartMs = series.startMs,
                windowEndMs = series.endMs,
                connectLine = false,
            )
        }
        if (series.weight.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MetricRow(
                label = "weight",
                unit = "kg",
                color = MytharaColors.Julep,
                samples = series.weight,
                windowStartMs = series.startMs,
                windowEndMs = series.endMs,
                connectLine = false,
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
    samples: List<HealthSeries.Sample>,
    windowStartMs: Long,
    windowEndMs: Long,
    connectLine: Boolean,
    decimals: Int = 0,
) {
    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.bodySmall.copy(
        color = color,
        fontWeight = FontWeight.Bold,
    )

    val latest = samples.maxByOrNull { it.tsMs }
    val latestText = latest?.let {
        val v = when (decimals) {
            0 -> it.value.toInt().toString()
            else -> "%.${decimals}f".format(it.value)
        }
        val unitStr = if (unit.isNotEmpty()) " $unit" else ""
        "$v$unitStr   ·   ${shortTime(it.tsMs)}"
    } ?: "—"

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header: metric label + most recent value with timestamp.
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

        // Sparkline canvas.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MytharaColors.SurfaceMid.copy(alpha = 0.4f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            if (samples.isEmpty()) {
                Text(
                    text = "no samples in window",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(modifier = Modifier.fillMaxWidth().height(GRAPH_HEIGHT_DP.dp)) {
                    drawTimeAxis(measurer, windowStartMs, windowEndMs, size)
                    drawScatter(
                        samples = samples,
                        color = color,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        size = size,
                        connectLine = connectLine,
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepRow(
    color: Color,
    sessions: List<HealthSeries.TimeRange>,
    windowStartMs: Long,
    windowEndMs: Long,
) {
    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.bodySmall.copy(
        color = color,
        fontWeight = FontWeight.Bold,
    )

    val totalMs = sessions.sumOf { it.endMs.coerceAtMost(windowEndMs) - it.startMs.coerceAtLeast(windowStartMs) }
        .coerceAtLeast(0L)
    val hrs = totalMs / 3_600_000.0
    val latestText = "%.1f h total".format(hrs)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "sleep",
                color = color,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.weight(1f))
            Text(text = latestText, style = valueStyle)
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MytharaColors.SurfaceMid.copy(alpha = 0.4f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(GRAPH_HEIGHT_DP.dp)) {
                drawTimeAxis(measurer, windowStartMs, windowEndMs, size)
                val bandH = (size.height - X_AXIS_TAGS_HEIGHT_PX) * 0.55f
                val bandY = (size.height - X_AXIS_TAGS_HEIGHT_PX) * 0.20f
                val span = (windowEndMs - windowStartMs).toFloat().coerceAtLeast(1f)
                for (s in sessions) {
                    val s0 = s.startMs.coerceAtLeast(windowStartMs)
                    val s1 = s.endMs.coerceAtMost(windowEndMs)
                    if (s1 <= s0) continue
                    val x0 = size.width * (s0 - windowStartMs) / span
                    val x1 = size.width * (s1 - windowStartMs) / span
                    drawRect(
                        color = color.copy(alpha = 0.75f),
                        topLeft = Offset(x0, bandY),
                        size = Size((x1 - x0).coerceAtLeast(2f), bandH),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScatter(
    samples: List<HealthSeries.Sample>,
    color: Color,
    windowStartMs: Long,
    windowEndMs: Long,
    size: Size,
    connectLine: Boolean,
) {
    if (samples.isEmpty()) return
    val w = size.width
    val h = size.height - X_AXIS_TAGS_HEIGHT_PX
    val tSpan = (windowEndMs - windowStartMs).toFloat().coerceAtLeast(1f)

    val vMin = samples.minOf { it.value }
    val vMax = samples.maxOf { it.value }
    val pad = ((vMax - vMin) * 0.15).coerceAtLeast(0.5)
    val yLo = (vMin - pad).coerceAtLeast(0.0)
    val yHi = vMax + pad
    val ySpan = (yHi - yLo).coerceAtLeast(0.01)

    fun xAt(ts: Long) = (w * (ts - windowStartMs) / tSpan).coerceIn(0f, w)
    fun yAt(v: Double) = (h * (1f - ((v - yLo) / ySpan)).toFloat()).coerceIn(0f, h)

    if (connectLine && samples.size > 1) {
        val sorted = samples.sortedBy { it.tsMs }
        val path = Path()
        path.moveTo(xAt(sorted.first().tsMs), yAt(sorted.first().value))
        for (i in 1 until sorted.size) {
            path.lineTo(xAt(sorted[i].tsMs), yAt(sorted[i].value))
        }
        drawPath(path, color = color.copy(alpha = 0.7f), style = Stroke(width = 1.5f))
    }

    for (s in samples) {
        drawCircle(
            color = color,
            radius = SCATTER_DOT_RADIUS_PX,
            center = Offset(xAt(s.tsMs), yAt(s.value)),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimeAxis(
    measurer: androidx.compose.ui.text.TextMeasurer,
    windowStartMs: Long,
    windowEndMs: Long,
    size: Size,
) {
    val w = size.width
    // 5 ticks evenly spaced. Labels are "−24h" / "−18h" / "−12h"
    // / "−6h" / "now" relative to the window end.
    val ticks = 5
    val tickStyle = TextStyle(color = MytharaColors.FgMute, fontSize = 9.sp)
    for (i in 0 until ticks) {
        val frac = i / (ticks - 1).toFloat()
        val tsAt = windowStartMs + ((windowEndMs - windowStartMs) * frac).toLong()
        val agoH = ((windowEndMs - tsAt) / 3_600_000.0).toInt()
        val label = if (agoH == 0) "now" else "−${agoH}h"
        val layout = measurer.measure(text = label, style = tickStyle)
        val x = (w * frac - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
        drawText(
            layout,
            topLeft = Offset(x, size.height - layout.size.height),
        )
    }
}

private fun shortTime(tsMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(tsMs))

/**
 * Raw-sample series for the past N hours of Health Connect data.
 *
 * Each metric carries the individual readings (no aggregation),
 * with their original timestamps. The graph plots each as a
 * scatter point against a continuous time axis.
 */
data class HealthSeries(
    val windowHours: Int,
    val startMs: Long,
    val endMs: Long,
    val hr: List<Sample> = emptyList(),
    val sleepSessions: List<TimeRange> = emptyList(),
    val kcal: List<Sample> = emptyList(),
    val steps: List<Sample> = emptyList(),
    val weight: List<Sample> = emptyList(),
) {
    data class Sample(val tsMs: Long, val value: Double)
    data class TimeRange(val startMs: Long, val endMs: Long)

    val hasAnyData: Boolean
        get() = hr.isNotEmpty() || sleepSessions.isNotEmpty() ||
            kcal.isNotEmpty() || steps.isNotEmpty() || weight.isNotEmpty()
}

private const val GRAPH_HEIGHT_DP = 72
private const val X_AXIS_TAGS_HEIGHT_PX = 18f
private const val SCATTER_DOT_RADIUS_PX = 2.5f
