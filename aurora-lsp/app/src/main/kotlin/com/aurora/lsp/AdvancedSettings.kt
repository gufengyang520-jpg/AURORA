package com.aurora.lsp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * A setting picked from a row of values instead of dragged along a track.
 *
 * <p>The app's other choices - presets, saved curves - are already rows of small tiles. A slider
 * next to them read as a stock Android widget dropped into someone else's design, and its thumb
 * could be dragged past the end of the track. A row of five or six tiles cannot be missed by a
 * thumb, cannot move off screen, and matches what the page already looks like.
 *
 * <p>Meant to sit inside a SectionCard, like every other block on these pages.
 */
@Composable
internal fun StepSettingRow(
    title: String,
    detail: String,
    valueLabel: String,
    steps: List<Float>,
    current: Float,
    dark: Boolean,
    onPick: (Float) -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = ink)
                Text(detail, fontSize = 11.sp, lineHeight = 16.sp, color = muted)
            }
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text(
                valueLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (current == 0f) muted else accent
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            steps.forEach { step ->
                val selected = abs(current - step) < 0.05f
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                selected && dark -> accent.copy(alpha = 0.18f)
                                selected -> accent.copy(alpha = 0.10f)
                                dark -> Color(0xFF23262D)
                                else -> Color(0xFFF4F6F9)
                            }
                        )
                        .clickable { onPick(step) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (step == 0f) "关" else step.toInt().toString(),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) accent else ink
                    )
                }
            }
        }
    }
}

/**
 * The two engine controls that are not part of the curve: stereo width and perceived loudness.
 */
@Composable
internal fun AdvancedSettingsPanel(
    state: AuroraState,
    dark: Boolean,
    scope: CoroutineScope
) {
    val muted = if (dark) MutedDark else Muted

    StepSettingRow(
        title = "声场宽度",
        detail = "左右声道在中高频错开，声音从一个点铺开成一片。低频保持居中，分开会发虚。",
        valueLabel = dbLabel(state.spread),
        steps = SPREAD_STEPS,
        current = state.spread,
        dark = dark
    ) { picked -> scope.launch { state.updateAdvanced(spread = picked) } }

    Spacer(Modifier.height(14.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(muted.copy(alpha = 0.22f))
    )
    Spacer(Modifier.height(14.dp))

    StepSettingRow(
        title = "感知响度",
        detail = "抬高听感响度而不抬高峰值，安静环境下别开太大。",
        valueLabel = dbLabel(state.loudnessDb),
        steps = LOUDNESS_STEPS,
        current = state.loudnessDb,
        dark = dark
    ) { picked -> scope.launch { state.updateAdvanced(loudnessDb = picked) } }
}

private val SPREAD_STEPS = listOf(0f, 2f, 4f, 6f, 8f)
private val LOUDNESS_STEPS = listOf(0f, 1f, 2f, 3f, 4f, 5f)

private fun dbLabel(value: Float): String =
    if (abs(value) < 0.05f) "关" else String.format(Locale.US, "%.1f dB", value)