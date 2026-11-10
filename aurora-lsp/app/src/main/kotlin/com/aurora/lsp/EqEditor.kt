package com.aurora.lsp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one place a curve gets dragged.
 *
 * <p>Two modes share it: adjusting the live curve, and building a new one to keep. They are the
 * same gesture with different endings, so they belong to the same screen rather than one being
 * inline on the equalizer page and the other hidden behind a save button.
 *
 * <p>Creating always asks for a name before it commits - saving without one is what produced the
 * dead end where the curve was stored but nothing appeared in the preset list.
 */
@Composable
fun EqEditorPanel(
    dark: Boolean,
    state: AuroraState,
    scope: CoroutineScope,
    creating: Boolean,
    initial: FloatArray,
    onDismiss: () -> Unit
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent
    val field = MiuixTheme.colorScheme.surfaceContainer

    var curve by remember { mutableStateOf(initial.copyOf()) }
    var name by remember { mutableStateOf("") }
    val canSave = !creating || name.trim().length >= 1

    PanelScaffold(
        dark = dark,
        title = if (creating) "新建一条曲线" else "调整当前曲线",
        subtitle = if (creating) {
            "拖到满意为止，起个名字保存，它会出现在「我的预设」里。"
        } else {
            "改动会立刻生效。想留一份就回来点「新建」，另存一条。"
        },
        onBack = onDismiss,
        actions = {
            PanelButton("取消", dark, false, Modifier.weight(1f), onDismiss)
            PanelButton(
                label = if (creating) "保存" else "应用",
                dark = dark,
                primary = true,
                modifier = Modifier.weight(1f),
                enabled = canSave,
                onClick = {
                    scope.launch {
                        if (creating) {
                            val finalName = name.trim().ifEmpty {
                                "我的预设 " + (state.userPresets.size + 1)
                            }
                            state.savePreset(finalName, curve)
                        }
                        state.update(newCurve = curve)
                        onDismiss()
                    }
                }
            )
        }
    ) {
        if (creating) {
            PanelCard(dark) {
                Text("名称", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(field)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { if (it.length <= 24) name = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = ink),
                        decorationBox = { inner ->
                            if (name.isEmpty()) {
                                Text(
                                    "比如：深夜低频、通勤人声",
                                    fontSize = 15.sp,
                                    color = muted
                                )
                            }
                            inner()
                        }
                    )
                }
                if (!canSave) {
                    Spacer(Modifier.height(6.dp))
                    Text("写个名字才能保存。", fontSize = 11.sp, color = WarnColor)
                }
            }
        }

        PanelLabel("曲线", dark)
        PanelCard(dark) {
            CurvePreview(curve, accent, muted, showScale = true)
            Spacer(Modifier.height(6.dp))
            val activeHz = state.engineBandHz ?: AudioEq.BAND_HZ
            activeHz.forEachIndexed { index, hz ->
                BandRow(
                    label = bandLabel(hz),
                    value = curve[index],
                    // Building a curve has no reference value per band, so no reset mark here.
                    default = null,
                    onValueChange = { value ->
                        curve = curve.copyOf().also { it[index] = value }
                    },
                    // This panel keeps its own draft and commits on 应用, so a drag never writes.
                    onCommit = {}
                )
            }
        }

        state.error?.let { message ->
            PanelCard(dark) {
                Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = WarnColor)
            }
        }

        PanelCard(dark) {
            Text(
                "80 Hz 管低频冲击，200 和 500 Hz 是容易发浑的区间，抬高它们只会让声音糊。"
                    + "相邻两段差得太多，听起来会像被硬拽过。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = muted
            )
        }
    }
}