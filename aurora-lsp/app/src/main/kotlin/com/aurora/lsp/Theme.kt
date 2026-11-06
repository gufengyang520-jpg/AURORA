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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Accent palettes.
 *
 * <p>Two values per theme: one for light backgrounds, one for dark. They are not the same hue at
 * different lightness - a colour readable on white goes muddy on near-black, so the dark variant is
 * lifted and slightly desaturated to keep contrast without glowing.
 *
 * <p>Everything else - surfaces, cards, text - stays with the MIUIX theme. Only the accent moves,
 * which is what keeps the app looking like one product across palettes.
 */
data class AuroraTheme(
    val id: String,
    val name: String,
    val light: Long,
    val dark: Long,
    val note: String
)

val AURORA_THEMES = listOf(
    AuroraTheme("pink", "桃粉", 0xFFD95C74, 0xFFFFA8BC, "默认，带一点暖"),
    AuroraTheme("blue", "经典蓝", 0xFF2F5BD0, 0xFF8FB4FF, "中性、克制"),
    AuroraTheme("teal", "青碧", 0xFF17807F, 0xFF6FD1D1, "冷而清透"),
    AuroraTheme("amber", "琥珀", 0xFFA96A10, 0xFFFFC46B, "偏暖，夜晚不刺眼"),
    AuroraTheme("violet", "紫罗兰", 0xFF7B4BC4, 0xFFC3A6FF, "安静的高级感"),
    AuroraTheme("moss", "苔绿", 0xFF457F4B, 0xFF9ED9A2, "自然、低饱和"),
    AuroraTheme("coral", "珊瑚", 0xFFBE5B3C, 0xFFFF9B7A, "比桃粉更热烈"),
    AuroraTheme("slate", "石板", 0xFF556070, 0xFFB4BFCC, "近乎无色，只留层次"),
)

const val DEFAULT_THEME_ID = "pink"

/**
 * The live accent, read by every screen.
 *
 * <p>A single mutable source rather than a composition local: dozens of composables already refer
 * to [Accent] directly, and making them read a state object means switching a theme recomposes
 * exactly the parts that draw with it and nothing else.
 */
object AuroraPalette {
    var accent by mutableStateOf(Color(AURORA_THEMES[0].light))
    var accentDark by mutableStateOf(Color(AURORA_THEMES[0].dark))
    var currentId by mutableStateOf(DEFAULT_THEME_ID)

    fun apply(id: String?) {
        val theme = AURORA_THEMES.firstOrNull { it.id == id } ?: AURORA_THEMES[0]
        currentId = theme.id
        accent = Color(theme.light)
        accentDark = Color(theme.dark)
    }

    fun nameOf(id: String?): String =
        AURORA_THEMES.firstOrNull { it.id == id }?.name ?: AURORA_THEMES[0].name
}

/** Theme picker: a grid of swatches, each showing the real accent on the real surface. */
@Composable
fun ThemePanel(
    dark: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val selected = AuroraPalette.currentId

    PanelScaffold(
        dark = dark,
        title = "主题",
        subtitle = "只改变强调色，背景、卡片和文字保持原样。",
        onBack = onDismiss,
        actions = {
            PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss)
        }
    ) {
        PanelCard(dark) {
            AURORA_THEMES.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { theme ->
                        ThemeSwatch(
                            theme = theme,
                            selected = theme.id == selected,
                            dark = dark,
                            ink = ink,
                            muted = muted,
                            modifier = Modifier.weight(1f)
                        ) { onPick(theme.id) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        PanelCard(dark) {
            Text(
                "亮色和暗色是两套单独调过的颜色，不是同一个色值调透明度。"
                    + "暗色下会降低一点饱和度，避免在近黑背景上发亮刺眼。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = muted
            )
        }
    }
}

@Composable
private fun ThemeSwatch(
    theme: AuroraTheme,
    selected: Boolean,
    dark: Boolean,
    ink: Color,
    muted: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = Color(if (dark) theme.dark else theme.light)
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) accent.copy(alpha = if (dark) 0.18f else 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accent)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    theme.name,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) accent else ink
                )
                Text(theme.note, fontSize = 10.sp, color = muted)
            }
        }
    }
}
