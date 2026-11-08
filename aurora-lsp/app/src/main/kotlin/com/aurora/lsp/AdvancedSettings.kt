package com.aurora.lsp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Expandable "Advanced settings" card.
 *
 * <p>Sits between "Create new EQ" and "Custom curve" on the equalizer page. Collapsed it is one
 * row; expanded it reveals three sliders that control the engine's DSP layer directly:
 * stereo widening (how much the left and right channels differ above 500 Hz), perceived loudness
 * (LoudnessEnhancer, in dB), and spatial depth (NXP Virtualizer, 0-100).
 *
 * <p>These are global, not per-preset: they shape the room the curve plays in, not the curve
 * itself. A user's shared preset carries the curve; the room stays with the device.
 */
@Composable
fun AdvancedSettingsCard(
    state: AuroraState,
    dark: Boolean,
    scope: CoroutineScope
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent

    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
        label = "chevron"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) Color(0xFF23262D) else Color(0xFFF4F6F9))
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("高级设置", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                Text(
                    "声场宽度、感知响度、空间深度",
                    fontSize = 11.sp,
                    color = muted
                )
            }
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (dark) Color(0xFF2E3138) else Color(0xFFE8ECF2))
                    .rotate(chevron),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "▾",
                    fontSize = 13.sp,
                    color = accent,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AdvancedSlider(
                    label = "声场宽度",
                    detail = "左右声道在中高频产生差异，声音从一个点变成一片区域。0 是关闭。",
                    value = state.spread,
                    range = 0f..3f,
                    display = { "${String.format("%.1f", it)} dB" },
                    dark = dark
                ) { v ->
                    scope.launch { state.updateAdvanced(spread = v) }
                }

                AdvancedSlider(
                    label = "感知响度",
                    detail = "提升人耳听到的响度而不增加峰值电平。0 是关闭。",
                    value = state.loudnessDb,
                    range = 0f..5f,
                    display = { "${String.format("%.1f", it)} dB" },
                    dark = dark
                ) { v ->
                    scope.launch { state.updateAdvanced(loudnessDb = v) }
                }

                AdvancedSlider(
                    label = "空间深度",
                    detail = "增加声音的距离感和包围感。0 是关闭。",
                    value = state.virtualizer.toFloat(),
                    range = 0f..1000f,
                    display = { "${(it / 10).toInt()}" },
                    dark = dark
                ) { v ->
                    scope.launch { state.updateAdvanced(virtualizer = v.toInt()) }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSlider(
    label: String,
    detail: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    dark: Boolean,
    onCommit: (Float) -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent
    var draft by remember(value) { mutableStateOf(value) }

    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
            Text(display(draft), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent)
        }
        Text(detail, fontSize = 11.sp, lineHeight = 15.sp, color = muted)
        Slider(
            value = draft,
            onValueChange = { draft = it },
            valueRange = range,
            onValueChangeFinished = { onCommit(draft) }
        )
    }
}
