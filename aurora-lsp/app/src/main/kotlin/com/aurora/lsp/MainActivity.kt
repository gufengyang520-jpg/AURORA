package com.aurora.lsp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import android.app.Activity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.aurora.lsp.theme.LocalAuroraDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HyperOS 4 / Android 17 forces edge-to-edge, and setStatusBarColor /
        // setNavigationBarColor are no-ops there. Let the framework do it and handle insets
        // in Compose, so the gesture bar (the MIUI/HyperOS "提示线") sits on our own background
        // instead of on the framework window colour.
        // Fully immersive gesture bar: both bars transparent, page content runs underneath.
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(clear, clear),
            navigationBarStyle = SystemBarStyle.auto(clear, clear)
        )
        setContent { AuroraApp() }
    }
}

internal val Ink = Color(0xFF1A1D22)
internal val InkDark = Color(0xFFEEF0F3)
internal val Muted = Color(0xFF6A7180)
internal val MutedDark = Color(0xFFA8ADB6)
internal val Accent = Color(0xFF2F5BD0)
internal val AccentDark = Color(0xFF8FB4FF)
internal val OkColor = Color(0xFF2E9E63)
internal val WarnColor = Color(0xFFB07514)

@Composable
private fun AuroraApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("aurora_ui", Context.MODE_PRIVATE) }
    val ui = remember { UiPrefs(prefs) }
    val systemDark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dark = when (ui.themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val state = remember { AuroraState(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    // Held here rather than inside EqPage: the save hint has to float above the dock, which
    // lives outside the pager.
    var draftCurve by remember { mutableStateOf<FloatArray?>(null) }

    val version = remember { installedVersion(context) }
    var pendingUpdate by remember { mutableStateOf<Update.Release?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    fun runUpdateCheck(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        if (manual) updateMessage = null
        scope.launch {
            when (val result = Update.check(version.code, version.name)) {
                is Update.Result.Newer ->
                    if (manual || result.release.versionCode > ui.skippedVersionCode) {
                        pendingUpdate = result.release
                    }
                is Update.Result.UpToDate ->
                    if (manual) updateMessage = "已是最新版本 " + result.versionName
                is Update.Result.Failed ->
                    if (manual) updateMessage = result.reason
            }
            updateChecking = false
        }
    }

    // The module writes a profile when it probes the device at boot. "I turned it on and nothing
    // happened" has three different causes, so each gets its own notice instead of one vague page.
    val profile = state.device
    var noticeDismissed by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var feedbackSending by remember { mutableStateOf(false) }
    var feedbackResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile.probedAt) { noticeDismissed = false }
    LaunchedEffect(profile, version.code) {
        if (profile.probed && !profile.supported) {
            Feedback.reportDevice(
                context.applicationContext, profile, version.code, version.name, prefs
            )
        }
    }

    // Silent on every launch unless there really is something newer; picking "以后再说" remembers
    // the version so the same prompt never comes back.
    LaunchedEffect(Unit) {
        if (ui.autoCheckUpdate) runUpdateCheck(manual = false)
    }

    LaunchedEffect(Unit) {
        state.refresh()
        while (true) {
            delay(2000)
            state.refresh()
        }
    }

    LaunchedEffect(Unit) {
        AuroraLog.append(context, "app", "start v" + version.name + " (" + version.code + ")")
    }

    // Records every state change the engine reports, so a later report shows the whole history.
    LaunchedEffect(state.backendState, state.backendMessage) {
        if (state.backendState.isNotEmpty()) {
            AuroraLog.append(context, "engine", state.backendState + " " + state.backendMessage)
        }
    }

    CompositionLocalProvider(LocalAuroraDarkTheme provides dark) {
        MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
            val activity = context as? Activity
            // The window background belongs to the framework theme and does not follow the in-app
            // theme, so anything Compose does not paint (the status bar strip, the empty space
            // under a short page) used to show the framework colour. Paint it here instead.
            val surface = MiuixTheme.colorScheme.surface
            val windowBackground = remember(surface) { ColorDrawable(surface.toArgb()) }
            SideEffect {
                if (activity != null) {
                    val decor = activity.window.decorView
                    val controller = WindowCompat.getInsetsController(activity.window, decor)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                    // Without this the three-button bar gets a translucent scrim on top of the
                    // gesture bar's own background, which breaks the "same colour as the page"
                    // rule from the HyperOS adaptation guide.
                    activity.window.isNavigationBarContrastEnforced = false
                    decor.background = windowBackground
                }
            }
            val backdrop = rememberLayerBackdrop()
            // Cross-page jumps fade through instead of sweeping across the pages in between: the
            // sweep drew four pages at once while the dock's glass was re-sampling, which is what
            // made a tab jump feel like a stutter.
            val pageFade = remember { Animatable(1f) }
            // Two taps in a row used to cancel each other mid-fade and leave the page half
            // transparent with no page switch. One jump runs at a time; the rest queue.
            val jumpLock = remember { Mutex() }
            val pagerState = rememberPagerState(pageCount = { 4 })
            LaunchedEffect(pagerState.currentPage) { tab = pagerState.currentPage }

            var backProgress by remember { mutableFloatStateOf(0f) }
            PredictiveBackHandler(enabled = ui.predictiveBack && tab != 0) { progress ->
                try {
                    progress.collect { backProgress = it.progress }
                    pagerState.scrollToPage(0)
                } finally {
                    // Runs once when the gesture ends, whether it completed or was cancelled.
                    backProgress = 0f
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surface)
                    .graphicsLayer {
                        val progress = backProgress
                        val scale = 1f - 0.07f * progress
                        scaleX = scale
                        scaleY = scale
                        shape = RoundedCornerShape((26 * progress).dp)
                        clip = progress > 0.01f
                    }
            ) {
                Scaffold(
                    topBar = {
                        SmallTopAppBar(title = when (tab) {
                            0 -> "音效"
                            1 -> "均衡器"
                            2 -> "诊断"
                            else -> "设置"
                        })
                    }
                ) { inner ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            // layerBackdrop must wrap the background, not sit inside it: it captures
                            // what its children paint, so a background declared outside it is never
                            // sampled and the dock's glass mixed towards black in light theme.
                            .layerBackdrop(backdrop)
                            .background(MiuixTheme.colorScheme.surface)
                            // Only the top is padded: the bottom inset is left to the content so the
                            // list scrolls underneath the gesture bar instead of stopping above it.
                            .padding(top = inner.calculateTopPadding())
                    ) {
                        StatusStrip(state, dark)
                        HorizontalPager(
                            state = pagerState,
                            // Keeps all four pages composed, so jumping from the first tab to the
                            // last one no longer composes three pages mid-animation.
                            beyondViewportPageCount = 3,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = pageFade.value }
                        ) { page ->
                            when (page) {
                                0 -> TonePage(state, dark, scope, draftCurve != null)
                                1 -> EqPage(
                                    state = state,
                                    dark = dark,
                                    scope = scope,
                                    draft = draftCurve ?: state.curve,
                                    onDraftChange = { draftCurve = it }
                                )
                                2 -> DiagPage(state, dark, scope)
                                else -> SettingsPage(
                                    dark = dark,
                                    ui = ui,
                                    versionName = version.name,
                                    versionCode = version.code,
                                    engineVersion = profile.engineVersion,
                                    onFeedback = { showFeedback = true },
                                    checking = updateChecking,
                                    message = updateMessage,
                                    onCheckUpdate = { runUpdateCheck(manual = true) }
                                )
                            }
                        }
                    }
                }

                AuroraBottomBar(
                    selected = tab,
                    onSelect = { index ->
                        scope.launch {
                            jumpLock.withLock {
                                try {
                                    val distance = kotlin.math.abs(index - pagerState.currentPage)
                                    if (distance > 1) {
                                        pageFade.animateTo(0f, tween(90))
                                        pagerState.scrollToPage(index)
                                    } else {
                                        pagerState.animateScrollToPage(index)
                                    }
                                } finally {
                                    // Always leaves the page visible, however this exits.
                                    pageFade.snapTo(1f)
                                }
                            }
                        }
                    },
                    backdrop = backdrop,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                draftCurve?.let { staged ->
                    SaveHint(
                        dark = dark,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 108.dp),
                        onSave = {
                            scope.launch {
                                state.update(newCurve = staged)
                                draftCurve = null
                            }
                        },
                        onDiscard = { draftCurve = null }
                    )
                }
            }

            pendingUpdate?.let { release ->
                UpdateDialog(
                    release = release,
                    dark = dark,
                    onLater = {
                        ui.skipVersion(release.versionCode)
                        pendingUpdate = null
                    },
                    onDownload = {
                        openUrl(context, release.url)
                        pendingUpdate = null
                    }
                )
            }

            if (!noticeDismissed && (!profile.probed || !profile.supported)) {
                ModuleNotice(profile = profile, dark = dark) { noticeDismissed = true }
            }

            if (showFeedback) {
                FeedbackSheet(
                    dark = dark,
                    submitting = feedbackSending,
                    result = feedbackResult,
                    onDismiss = { if (!feedbackSending) showFeedback = false },
                    onSubmit = { problem, text, withLogs ->
                        feedbackSending = true
                        feedbackResult = null
                        scope.launch {
                            val ok = Feedback.reportIssue(
                                context.applicationContext,
                                problem,
                                text,
                                withLogs,
                                profile,
                                version.code,
                                version.name,
                                prefs
                            )
                            feedbackSending = false
                            feedbackResult = if (ok) {
                                AuroraLog.append(context, "feedback", problem + " sent")
                                "已提交，谢谢。"
                            } else {
                                "提交失败，请检查网络后重试。"
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(state: AuroraState, dark: Boolean) {
    val muted = if (dark) MutedDark else Muted
    val (label, color) = when (state.backendState) {
        "active" -> "正在生效" to OkColor
        "selftest" -> "自检中" to OkColor
        "idle" -> "等待播放" to muted
        "disabled" -> "已关闭" to muted
        "panic" -> "已紧急停用" to WarnColor
        else -> if (state.backendState.endsWith("error")) "写入失败" to WarnColor
        else "读取中" to muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
        Spacer(Modifier.width(10.dp))
        Text(
            buildString {
                if (state.sessions.isNotEmpty()) append("会话 ").append(state.sessions).append(" · ")
                append("强度 ").append(state.strength).append("%")
            },
            fontSize = 12.sp,
            color = muted
        )
    }
}

@Composable
private fun PageList(content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // The dock floats over the list, so the last card needs enough room to clear it plus the
        // gesture bar underneath.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { content() }
    }
}

@Composable
private fun TonePage(
    state: AuroraState,
    dark: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    hasUnsavedCurve: Boolean
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent

    PageList {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("叠加调音", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
                        Text(
                            if (state.enabled) "已开启，正在叠加曲线" else "已关闭，音频走原厂链路",
                            fontSize = 12.sp, color = muted
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = { value ->
                        scope.launch { state.update(newEnabled = value) }
                    })
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("当前预设", fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                    Text("强度 ${state.strength}%", fontSize = 12.sp, color = muted)
                }
                if (hasUnsavedCurve) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "均衡页有未保存的曲线修改，那条曲线还没有生效",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = WarnColor
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    Presets.NAMES[state.presetIndex],
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    Presets.NOTES[state.presetIndex],
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = muted
                )
                Spacer(Modifier.height(12.dp))
                CurvePreview(state.curve, accent, muted, showScale = true)
                Spacer(Modifier.height(4.dp))
                BandScale(muted)
            }

            GroupLabel("快速切换", muted)
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 3, 4, 5).forEach { index ->
                        val selected = index == state.presetIndex
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
                                .clickable {
                                    scope.launch { state.update(newPreset = Presets.KEYS[index]) }
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                Presets.NAMES[index],
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) accent else ink,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            SectionCard {
                Text(
                    "曲线只挂在正在播放的会话上：暂停或切歌时不会有叠加，播放恢复后自动接上。" +
                        "想逐段细调就到均衡页。",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = muted
                )
            }

            if (state.error != null) {
                SectionCard { Text(state.error!!, fontSize = 13.sp, color = WarnColor) }
            }
        }
    }
}

@Composable
private fun CurvePreview(
    curve: FloatArray,
    accent: Color,
    grid: Color,
    showScale: Boolean = false
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (showScale) 132.dp else 96.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val inset = 4f
        if (showScale) {
            val faint = grid.copy(alpha = 0.16f)
            drawLine(faint, Offset(0f, inset), Offset(w, inset), strokeWidth = 1f)
            drawLine(grid.copy(alpha = 0.30f), Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
            drawLine(faint, Offset(0f, h - inset), Offset(w, h - inset), strokeWidth = 1f)
        } else {
            drawLine(grid.copy(alpha = 0.30f), Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
        }
        val step = w / (curve.size - 1).coerceAtLeast(1)
        val usable = h / 2f - inset
        val path = Path()
        curve.forEachIndexed { index, db ->
            val x = step * index
            val y = mid - (db / 12f) * usable
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = Stroke(width = 3f, cap = StrokeCap.Round))
        curve.forEachIndexed { index, db ->
            val x = step * index
            val y = mid - (db / 12f) * usable
            drawCircle(accent, radius = 3.5f, center = Offset(x, y))
        }
    }
}

@Composable
private fun BandScale(color: Color) {
    Row(Modifier.fillMaxWidth()) {
        AudioEq.BAND_HZ.forEach { hz ->
            Text(
                if (hz >= 1000f) "${(hz / 1000).toInt()}k" else hz.toInt().toString(),
                fontSize = 9.sp,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EqPage(
    state: AuroraState,
    dark: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    draft: FloatArray,
    onDraftChange: (FloatArray) -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent

    PageList {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GroupLabel("预设", muted)
            SectionCard {
                Presets.KEYS.indices.chunked(3).forEach { cells ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        cells.forEach { index ->
                            PresetCell(
                                index = index,
                                selected = index == state.presetIndex,
                                dark = dark,
                                modifier = Modifier.weight(1f)
                            ) {
                                scope.launch { state.update(newPreset = Presets.KEYS[index]) }
                            }
                        }
                        repeat(3 - cells.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            GroupLabel("自定义曲线", muted)
            SectionCard {
                CurvePreview(draft, accent, muted)
                Spacer(Modifier.height(6.dp))
                AudioEq.BAND_HZ.forEachIndexed { index, hz ->
                    BandRow(
                        label = if (hz >= 1000) "${(hz / 1000).toInt()} kHz" else "${hz.toInt()} Hz",
                        value = draft[index],
                        dark = dark,
                        onChange = { value ->
                            onDraftChange(draft.copyOf().also { it[index] = value })
                        }
                    )
                }
                if (state.savedCurve != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "恢复我之前保存的曲线",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { scope.launch { state.restoreSaved() } }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            GroupLabel("强度", muted)
            SectionCard {
                var draftStrength by remember(state.strength) {
                    mutableStateOf(state.strength.toFloat())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("叠加强度", fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
                    Text(
                        "${draftStrength.toInt()}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent
                    )
                }
                Slider(
                    value = draftStrength,
                    onValueChange = { draftStrength = it },
                    valueRange = 0f..100f,
                    onValueChangeFinished = {
                        scope.launch { state.update(newStrength = draftStrength.toInt()) }
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetCell(
    index: Int,
    selected: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = if (dark) AccentDark else Accent
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected && dark -> accent.copy(alpha = 0.18f)
                    selected -> accent.copy(alpha = 0.10f)
                    dark -> Color(0xFF23262D)
                    else -> Color(0xFFF4F6F9)
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 9.dp)
    ) {
        Text(
            Presets.NAMES[index],
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else ink,
            maxLines = 1
        )
        Spacer(Modifier.height(5.dp))
        MiniCurve(
            curve = Presets.curve(index),
            color = if (selected) accent else muted.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
        )
    }
}

@Composable
private fun MiniCurve(curve: FloatArray, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val mid = h / 2f
        val step = w / (curve.size - 1).coerceAtLeast(1)
        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = step * i
            val y = mid - (db / 12f) * (h / 2f - 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
}
@Composable
private fun BandRow(
    label: String,
    value: Float,
    dark: Boolean,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 13.sp,
                color = if (dark) MutedDark else Muted,
                modifier = Modifier.width(56.dp)
            )
            Text(
                String.format(java.util.Locale.US, "%+.1f dB", value),
                fontSize = 13.sp,
                color = if (dark) InkDark else Ink
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -12f..12f
        )
    }
}

@Composable
private fun DiagPage(state: AuroraState, dark: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent
    val context = LocalContext.current
    val player = remember { TonePlayer() }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { player.onFinished = { playing = false } }
    LaunchedEffect(playing) {
        if (playing) player.start(context) { scope.launch { state.selfTest() } } else player.stop()
    }

    PageList {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard {
                Text("试听自检", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点一下会播放测试音，并把效果压掉 6 秒。听到一强一弱说明工作正常。",
                    fontSize = 13.sp, color = muted
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (playing) "停止测试音" else "播放测试音",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dark) Color(0xFF1B2436) else Color(0xFFE8EEFB))
                        .clickable { playing = !playing }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            GroupLabel("实际写入", muted)
            SectionCard {
                val bands = state.actualBands
                if (bands != null) {
                    CurvePreview(bands, accent, muted, showScale = true)
                    Spacer(Modifier.height(4.dp))
                    BandScale(muted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "引擎当前下发到系统的增益，单位 dB，精度 0.1。",
                        fontSize = 11.sp,
                        color = muted
                    )
                } else {
                    Text(
                        "还没有写入记录。播一段音频，回到这里就能看到引擎实际下发的曲线。",
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        color = muted
                    )
                }
            }

            GroupLabel("系统回执", muted)
            SectionCard {
                Row {
                    ReceiptCell("后端状态", state.backendState.ifEmpty { "—" }, dark, Modifier.weight(1f))
                    ReceiptCell("生效会话", state.applied.ifEmpty { "0" }, dark, Modifier.weight(1f))
                }
                Row {
                    ReceiptCell("挂载会话", state.sessions.ifEmpty { "无" }, dark, Modifier.weight(1f))
                    ReceiptCell("叠加强度", state.strength.toString() + "%", dark, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text("说明", fontSize = 11.sp, color = muted)
                Text(
                    state.backendMessage.ifEmpty { "—" },
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = ink
                )
            }
        }
    }
}

@Composable
private fun ReceiptCell(
    key: String,
    value: String,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(vertical = 5.dp)) {
        Text(key, fontSize = 11.sp, color = if (dark) MutedDark else Muted)
        Text(value, fontSize = 14.sp, color = if (dark) InkDark else Ink)
    }
}

@Composable
private fun SettingsPage(
    dark: Boolean,
    ui: UiPrefs,
    versionName: String,
    versionCode: Int,
    engineVersion: Int,
    onFeedback: () -> Unit,
    checking: Boolean,
    message: String?,
    onCheckUpdate: () -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent

    PageList {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GroupLabel("外观", muted)
            SectionCard {
                listOf(
                    "system" to "跟随系统",
                    "light" to "浅色",
                    "dark" to "深色"
                ).forEach { (key, label) ->
                    SelectRow(label, ui.themeMode == key, dark) { ui.setTheme(key) }
                }
            }

            GroupLabel("交互", muted)
            SectionCard {
                ToggleRow(
                    title = "预测性返回",
                    detail = "侧滑时页面跟随手指收缩",
                    checked = ui.predictiveBack,
                    dark = dark,
                    onChange = { ui.changePredictiveBack(it) }
                )
                ToggleRow(
                    title = "启动时检查更新",
                    detail = "只有发现新版本才会提示",
                    checked = ui.autoCheckUpdate,
                    dark = dark,
                    onChange = { ui.changeAutoCheckUpdate(it) }
                )
            }

            GroupLabel("更新", muted)
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前版本", fontSize = 14.sp, color = ink)
                        Text(versionName, fontSize = 12.sp, color = muted)
                    }
                    Text(
                        if (checking) "检查中" else "检查更新",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (checking) muted else accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !checking) { onCheckUpdate() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(message, fontSize = 12.sp, color = muted)
                }
                if (engineVersion > 0 && engineVersion != versionCode) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "引擎版本不一致：模块里跑的是 " + engineVersion + "，当前安装的是 " +
                            versionCode + "。重启设备后生效。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = WarnColor
                    )
                }
            }

            GroupLabel("反馈", muted)
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("反馈问题", fontSize = 14.sp, color = ink)
                        Text(
                            "用不了、听不出区别、界面不对，都可以在这里说",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = muted
                        )
                    }
                    Text(
                        "去反馈",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onFeedback() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            GroupLabel("创作者", muted)
            SectionCard { CreatorCard(dark) }

            GroupLabel("关于", muted)
            SectionCard { ProgramCard(dark, versionName) }

            SectionCard {
                Text("开源声明", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "界面组件参考 MIUIX 与 KernelSU（GPL-3.0），液态玻璃折射效果来自 " +
                        "AndroidLiquidGlass（Apache-2.0）。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = muted
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String, color: Color) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    dark: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = if (dark) InkDark else Ink)
            Text(detail, fontSize = 11.sp, color = if (dark) MutedDark else Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Explains why the module cannot work here. The two cases look identical to a user but need
 * completely different actions, so they are never merged into one message.
 */
@Composable
private fun ModuleNotice(
    profile: DeviceProfile,
    dark: Boolean,
    onDismiss: () -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val scrim = remember { MutableInteractionSource() }
    val swallow = remember { MutableInteractionSource() }

    val running = profile.probed
    val title = if (running) "此设备暂不支持" else "模块没有运行"
    val body = if (running) {
        "自动检测停在「" + profile.stageLabel() + "」。这台机器的音频链路和已验证的机型不一样，" +
            "现在开启也不会有声音变化。"
    } else {
        "检测不到音频引擎。多半是模块没在 LSPosed 里启用，或者启用后没有重启设备。"
    }
    val action = if (running) {
        "机型和链路信息已经打包发回，作者拿到后可以做适配。"
    } else {
        "去 LSPosed 里启用 AURORA，作用域勾选「系统」，然后重启设备。"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(interactionSource = scrim, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (dark) Color(0xFF1C1F24) else Color.White)
                .clickable(interactionSource = swallow, indication = null) { }
                .padding(20.dp)
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Spacer(Modifier.height(10.dp))
            Text(body, fontSize = 13.sp, lineHeight = 20.sp, color = muted)
            Spacer(Modifier.height(10.dp))
            Text(action, fontSize = 13.sp, lineHeight = 20.sp, color = muted)
            if (running) {
                Spacer(Modifier.height(12.dp))
                Text(
                    profile.model + " · SDK " + profile.sdk,
                    fontSize = 11.sp,
                    color = muted
                )
            }
            Spacer(Modifier.height(18.dp))
            DialogAction("知道了", true, dark, Modifier.fillMaxWidth(), onDismiss)
        }
    }
}

@Composable
private fun UpdateDialog(
    release: Update.Release,
    dark: Boolean,
    onLater: () -> Unit,
    onDownload: () -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent

    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (dark) Color(0xFF1C1F24) else Color.White)
                .padding(20.dp)
        ) {
            Text("发现新版本", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Spacer(Modifier.height(6.dp))
            Text(release.versionName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = accent)
            if (release.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    release.notes.take(240),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = muted
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogAction("以后再说", false, dark, Modifier.weight(1f), onLater)
                DialogAction("下载", true, dark, Modifier.weight(1f), onDownload)
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    primary: Boolean,
    dark: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val accent = if (dark) AccentDark else Accent
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    primary -> accent.copy(alpha = if (dark) 0.22f else 0.12f)
                    dark -> Color(0xFF262A31)
                    else -> Color(0xFFF1F3F7)
                }
            )
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) accent else (if (dark) InkDark else Ink)
        )
    }
}

/** Pure UI preferences; these stay in SharedPreferences and never touch Settings.Global. */
internal class UiPrefs(private val prefs: SharedPreferences) {

    var themeMode by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set
    var predictiveBack by mutableStateOf(prefs.getBoolean("predictive_back", true))
        private set
    var autoCheckUpdate by mutableStateOf(prefs.getBoolean("auto_update", true))
        private set
    var skippedVersionCode by mutableIntStateOf(prefs.getInt("skipped_version", 0))
        private set

    fun setTheme(value: String) {
        themeMode = value
        prefs.edit().putString("theme", value).apply()
    }

    fun changePredictiveBack(value: Boolean) {
        predictiveBack = value
        prefs.edit().putBoolean("predictive_back", value).apply()
    }

    fun changeAutoCheckUpdate(value: Boolean) {
        autoCheckUpdate = value
        prefs.edit().putBoolean("auto_update", value).apply()
    }

    fun skipVersion(code: Int) {
        skippedVersionCode = code
        prefs.edit().putInt("skipped_version", code).apply()
    }
}

private class VersionInfo(val code: Int, val name: String)

private fun installedVersion(context: Context): VersionInfo = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    VersionInfo(PackageInfoCompat.getLongVersionCode(info).toInt(), info.versionName ?: "1.0.0")
}.getOrDefault(VersionInfo(0, "1.0.0"))

/**
 * Opens a link from latest.json. Restricted to https on our own host: a hijacked manifest must
 * not be able to hand the system an `intent:` or `market:` URI.
 */
private fun openUrl(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    if (uri.scheme != "https") return
    val host = uri.host ?: return
    if (host != "lingluandf.online" && !host.endsWith(".lingluandf.online")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun SelectRow(label: String, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    val accent = if (dark) AccentDark else Accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (selected) accent else (if (dark) InkDark else Ink),
            modifier = Modifier.weight(1f)
        )
        if (selected) Text("✓", fontSize = 15.sp, color = accent)
    }
}

@Composable
private fun SectionCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    } else {
        Modifier.fillMaxWidth()
    }
    Card(
        modifier = modifier,
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp)
    ) {
        Column { content() }
    }
}

@Composable
private fun SaveHint(
    dark: Boolean,
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) Color(0xFF262B33) else Color.White)
            .padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("曲线已修改", fontSize = 13.sp, color = ink, modifier = Modifier.weight(1f))
        Text(
            "还原",
            fontSize = 13.sp,
            color = muted,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onDiscard() }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        Text(
            "保存",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onSave() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

private class TonePlayer {
    var onFinished: (() -> Unit)? = null
    @Volatile private var running = false

    fun start(context: Context, onPulse: () -> Unit) {
        if (running) return
        running = true
        Thread {
            var track: AudioTrack? = null
            try {
                val rate = 48000
                val min = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val chunk = ShortArray(rate / 10)
                var p1 = 0.0
                var p2 = 0.0
                val started = System.currentTimeMillis()
                var nextPulse = 2000L
                while (running && System.currentTimeMillis() - started < 20000) {
                    for (i in chunk.indices) {
                        p1 += 2 * Math.PI * 1000 / rate
                        p2 += 2 * Math.PI * 3000 / rate
                        chunk[i] = ((Math.sin(p1) + Math.sin(p2)) * 5000).toInt().toShort()
                    }
                    track.write(chunk, 0, chunk.size)
                    if (System.currentTimeMillis() - started >= nextPulse) {
                        nextPulse += 8000
                        onPulse()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    track?.stop()
                } catch (_: Throwable) {
                }
                track?.release()
                running = false
                onFinished?.invoke()
            }
        }.start()
    }

    fun stop() {
        running = false
    }
}