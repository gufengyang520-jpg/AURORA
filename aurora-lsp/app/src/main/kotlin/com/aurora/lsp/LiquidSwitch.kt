package com.aurora.lsp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aurora.lsp.liquid.InnerShadow
import com.aurora.lsp.liquid.innerShadow
import com.aurora.lsp.liquid.lens
import com.aurora.lsp.liquid.rememberCombinedBackdrop
import com.aurora.lsp.theme.isInDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * The page's background layer, published so controls above it can sample it.
 *
 * <p>Safe only because the background is a sibling of the content, never its parent: the sampled
 * layer holds paint and nothing else, so a control inside the content can read it without the
 * render tree recursing into itself.
 */
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Switch made of clear glass.
 *
 * <p>No blur on the knob, on purpose. A frosted knob turns grey over a dark page and stops looking
 * like glass; leaving the sample sharp lets the groove and the page show through undistorted, with
 * only the lens curvature and a thin rim to say "there is something here".
 *
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val accent = Accent
    val dark = isInDarkTheme()
    val trackOff = if (dark) Color(0xFF787880).copy(alpha = 0.28f) else Color(0xFF787878).copy(alpha = 0.14f)
    val outer = LocalLiquidBackdrop.current

    val fraction = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        fraction.animateTo(
            if (checked) 1f else 0f,
            spring(dampingRatio = 0.58f, stiffness = 620f)
        )
    }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f),
        label = "switch-press"
    )

    val pill = remember { RoundedCornerShape(percent = 50) }
    val trackBackdrop = rememberLayerBackdrop()
    val combined = if (outer != null) rememberCombinedBackdrop(outer, trackBackdrop) else trackBackdrop

    Box(
        modifier
            .size(width = 64.dp, height = 28.dp)
            // Tap handling lives on the whole switch, not on the knob. A 40×24 target is
            // already small, and once size() sat after pointerInput the hit area did not even
            // match the knob - which is why it stopped responding.
            .pointerInput(enabled, checked) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = { onCheckedChange(!checked) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Groove: paint only. This is the one thing the knob refracts.
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(pill)
                .drawBehind { drawRect(lerp(trackOff, accent.copy(alpha = 0.75f), fraction.value)) }
                .size(width = 64.dp, height = 28.dp)
        )

        Box(
            Modifier
                .size(width = 40.dp, height = 24.dp)
                .graphicsLayer { translationX = 2.dp.toPx() + 20.dp.toPx() * fraction.value }.drawBackdrop(
                    backdrop = combined,
                    shape = { pill },
                    effects = {
                        // No blur: keeping the sample sharp is what makes it read as clear glass.
                        lens(
                            2f.dp.toPx() + 4f.dp.toPx() * press,
                            4f.dp.toPx() + 7f.dp.toPx() * press,
                            chromaticAberration = 0.15f + 0.5f * press
                        )
                    },
                    layerBlock = {
                        val s = 1f + 0.45f * press
                        scaleX = s
                        scaleY = s
                    },
                    onDrawSurface = {
                        // Almost nothing on top: the glass is the material.
                        drawRect(Color.White.copy(alpha = 0.18f + 0.10f * (1f - press)))
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.45f + 0.25f * press),
                            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                )
                .innerShadow(shape = pill) {
                    InnerShadow(
                        radius = 5.dp * press,
                        color = Color.Black.copy(alpha = 0.18f),
                        alpha = press
                    )
                }
        )
    }
}