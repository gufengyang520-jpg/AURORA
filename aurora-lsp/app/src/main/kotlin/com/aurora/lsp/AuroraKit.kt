package com.aurora.lsp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/**
 * The one place layout and type are decided.
 *
 * <p>Every screen is built from the handful of pieces below, which is the whole point: the app used
 * to carry five corner radii, eight type sizes, three control languages and a few hard-coded greys,
 * so each screen read as its own design. Nothing here invents a value - these are the tokens from
 * DESIGN.md, and colours come from the theme rather than from literals.
 */

/** Spacing tokens, 4 grid. */
internal object Space {
    /** Page side margin. */
    val page = 20.dp

    /** Between two groups. */
    val group = 24.dp

    /** Card inner padding. */
    val card = 16.dp

    /** Inside a row. */
    val rowV = 12.dp

    /** Leading mark to its text. */
    val iconGap = 12.dp

    /** Width reserved for a leading mark so labels line up. */
    val iconColumn = 22.dp
}

/** Radius tokens. Inner = outer - padding, never below 8. */
internal object Radius {
    val card = 22.dp
    val sheet = 28.dp
    val block = 14.dp
}

/**
 * One page: same margins, same title, same scroll rhythm, so switching tabs is not a change of
 * style.
 */
@Composable
internal fun AuroraPage(
    title: String,
    subtitle: String? = null,
    underTitle: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.page,
            end = Space.page,
            top = 8.dp,
            // The dock floats over the list; the last group needs room to clear it.
            bottom = 132.dp
        )
    ) {
        item {
            // fillMaxWidth here is load-bearing, not decoration. A Column wraps its children, and
            // inside a wrap-width parent any `Modifier.weight(1f)` in a Row resolves to zero - which
            // is what clipped the settings rows and pushed the diagnostics cards off screen.
            //
            // The entrance runs once per page and is the only load animation in the app: it settles
            // the page under the dock instead of having it appear in place.
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                appear.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = appear.value
                        translationY = (1f - appear.value) * 12.dp.toPx()
                    }
            ) {
                PageTitle(title, subtitle)
                if (underTitle != null) {
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth(), content = underTitle)
                }
                Spacer(Modifier.height(Space.group))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.group)
                ) { content() }
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String?) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariantSummary
            )
        }
    }
}

/**
 * A titled group: caption, then one card holding the rows.
 *
 * <p>Rows divide with a hairline instead of each becoming its own block. That is the difference
 * between a list and a pile of boxes.
 */
@Composable
internal fun AuroraGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            SmallTitle(
                text = title,
                textColor = colors.onSurfaceVariantSummary,
                insideMargin = PaddingValues(start = 4.dp, top = 0.dp, end = 4.dp, bottom = 8.dp)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = Radius.card,
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = colors.surfaceContainer)
        ) {
            // The card's own content column is the width authority for every row inside it.
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** Hairline between rows of one group. Inset so it starts at the text, not at the card edge. */
@Composable
internal fun RowDivider(startInset: Dp = Space.card) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset),
        thickness = 0.75.dp,
        color = MiuixTheme.colorScheme.dividerLine
    )
}

/**
 * One line of a group: title, optional summary, optional leading mark, optional trailing content.
 * The min height keeps every row the same size whether or not it carries a summary.
 */
@Composable
internal fun AuroraRow(
    title: String,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = Space.card, vertical = Space.rowV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(Modifier.width(Space.iconColumn), contentAlignment = Alignment.CenterStart) {
                leading()
            }
            Spacer(Modifier.width(Space.iconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = colors.onSurface)
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariantSummary
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A block of explanatory text inside a group. */
@Composable
internal fun NoteText(text: String, error: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.padding(horizontal = Space.card, vertical = Space.rowV)) {
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (error) colors.error else colors.onSurfaceVariantSummary
        )
    }
}

/**
 * The signature mark: six bars, one per band, growing up for a boost and down for a cut.
 *
 * <p>It shows up wherever the question is "what is this curve doing" - a preset row, the status
 * capsule - and nowhere else.
 */
@Composable
internal fun EqGlyph(
    curve: FloatArray,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    height: Dp = 16.dp,
    maxDb: Float = 12f
) {
    Canvas(modifier.height(height)) {
        val count = curve.size
        if (count == 0 || size.width <= 0f) return@Canvas
        val slot = size.width / count
        val barWidth = (slot * 0.5f).coerceAtLeast(1.5f)
        val mid = size.height / 2f
        val reach = mid - 0.5f
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        curve.forEachIndexed { index, db ->
            val level = (abs(db) / maxDb).coerceIn(0f, 1f)
            val length = (level * reach).coerceAtLeast(1f)
            val x = slot * index + (slot - barWidth) / 2f
            val top = if (db >= 0f) mid - length else mid
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidth, length),
                cornerRadius = radius
            )
        }
        // Zero line, only as a whisper: it is what tells a cut from a boost.
        drawLine(
            color = color.copy(alpha = 0.35f),
            start = Offset(0f, mid),
            end = Offset(size.width, mid),
            strokeWidth = 0.75f
        )
    }
}

/** A small status dot. */
@Composable
internal fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * The status capsule: one line that answers "is it working right now".
 *
 * <p>It sits directly under the page title on every page, so the answer lives in the same place no
 * matter which tab is open.
 */
@Composable
internal fun StatusCapsule(
    label: String,
    tone: Color,
    readings: String,
    modifier: Modifier = Modifier
) {
    val colors = MiuixTheme.colorScheme
    // The engine changes state on its own every two seconds; colouring the dot and the word with a
    // short crossfade keeps a state change from blinking.
    val liveTone by animateColorAsState(
        targetValue = tone,
        animationSpec = tween(durationMillis = 240),
        label = "statusTone"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.block + 4.dp))
            .background(colors.surfaceContainer)
            .padding(horizontal = Space.card, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(liveTone)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = liveTone)
        Spacer(Modifier.weight(1f))
        Text(readings, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
    }
}

/** Trailing value on the right of a row: quiet, single line, never wraps. */
@Composable
internal fun ValueText(text: String, accent: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
        color = if (accent) colors.primary else colors.onSurfaceVariantSummary,
        maxLines = 1,
        softWrap = false
    )
}