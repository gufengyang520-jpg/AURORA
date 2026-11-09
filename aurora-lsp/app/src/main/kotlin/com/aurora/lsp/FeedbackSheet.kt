package com.aurora.lsp

import top.yukonga.miuix.kmp.basic.Switch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val PROBLEMS = listOf(
    "听不出区别" to "listen",
    "崩溃或闪退" to "crash",
    "界面异常" to "ui",
    "其它问题" to "other"
)

/**
 * Below this many characters a report says nothing actionable. The button stays tappable anyway -
 * a greyed-out button with no explanation reads as "the app is broken", which is exactly what
 * happened to the first people who tried it.
 */
private const val MIN_TEXT = 4

/**
 * Manual feedback, as a drawer: it rises from the bottom edge and settles, and leaves the same way.
 *
 * Not a new page — it opens over the settings screen and closes back to it, so the user never
 * loses their place.
 */
@Composable
fun FeedbackSheet(
    dark: Boolean,
    submitting: Boolean,
    result: String?,
    onSubmit: (problem: String, text: String, withLogs: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent
    val card = if (dark) Color(0xFF1C1F24) else Color.White

    var problem by remember { mutableStateOf(PROBLEMS.first().second) }
    var text by remember { mutableStateOf("") }
    var withLogs by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    val trimmed = text.trim()
    val canSubmit = trimmed.length >= MIN_TEXT

    val scope = rememberCoroutineScope()
    // 0 = off screen, 1 = settled. Drives both the scrim and the drawer in one number.
    val rise = remember { Animatable(0f) }
    val travel = with(LocalDensity.current) { 640.dp.toPx() }

    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(durationMillis = 340, easing = FastOutSlowInEasing))
    }

    fun close() {
        if (submitting) return
        scope.launch {
            rise.animateTo(0f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    // The drawer sits inside the app window, so the system back gesture never reached it: the only
    // way out was the 取消 button, and a swipe back dropped the user out of the app entirely.
    BackHandler(enabled = !submitting) { close() }

    val scrim = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
            .graphicsLayer { alpha = rise.value }
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(interactionSource = scrim, indication = null) { close() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = (1f - rise.value) * travel }
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(card)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp)
        ) {
            Text("反馈问题", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Spacer(Modifier.height(14.dp))

            Text("是哪一类", fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROBLEMS.forEach { (label, key) ->
                    val selected = problem == key
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                when {
                                    selected && dark -> accent.copy(alpha = 0.20f)
                                    selected -> accent.copy(alpha = 0.12f)
                                    dark -> Color(0xFF262A31)
                                    else -> Color(0xFFF2F4F7)
                                }
                            )
                            .clickable { problem = key }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) accent else ink,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("具体是什么情况", fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    if (it.length <= 600) {
                        text = it
                        hint = null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 108.dp),
                placeholder = {
                    Text(
                        "比如：开着空间音频听歌，切到柏林之声后声音没有变化。",
                        fontSize = 13.sp,
                        color = muted
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = ink,
                    lineHeight = 20.sp
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = if (dark) Color(0xFF33383F) else Color(0xFFE2E6EC),
                    cursorColor = accent
                )
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("上传运行日志", fontSize = 14.sp, color = ink)
                    Text(
                        "强烈建议开启。没有日志的问题基本无法定位。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (withLogs) muted else WarnColor
                    )
                }
                Switch(checked = withLogs, onCheckedChange = { withLogs = it })
            }

            val message = hint ?: result
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = if (hint != null) WarnColor else muted
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dark) Color(0xFF262A31) else Color(0xFFF2F4F7))
                        .clickable { close() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消", fontSize = 14.sp, color = ink)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                submitting || !canSubmit -> accent.copy(alpha = 0.45f)
                                else -> accent
                            }
                        )
                        .clickable(enabled = !submitting) {
                            if (canSubmit) {
                                hint = null
                                onSubmit(problem, trimmed, withLogs)
                            } else {
                                hint = "先写一句具体是什么情况（至少 $MIN_TEXT 个字），不然看不出问题在哪。"
                            }
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (submitting) "提交中" else "提交",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = if (canSubmit) 1f else 0.55f)
                    )
                }
            }
        }
    }
}