package com.aurora.lsp

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

private const val BLOG_URL = "https://lingluandf.online"

@Composable
private fun rememberAsset(name: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(name) {
        runCatching {
            context.assets.open(name).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }
}

/** Avatar, name and the one-line bio, with a way out to the blog. */
@Composable
fun CreatorCard(dark: Boolean) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent
    val context = LocalContext.current
    val avatar = rememberAsset("profile.jpg")

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (avatar != null) {
                Image(
                    bitmap = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.2f))
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("凌乱的风", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Text(
                    "18 岁",
                    fontSize = 12.sp,
                    color = muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(BLOG_URL))
                        )
                    }
                }
                .background(if (dark) Color(0xFF1B2436) else Color(0xFFE8EEFB))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("个人博客", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent)
                Text(
                    "lingluandf.online",
                    fontSize = 11.sp,
                    color = muted
                )
            }
            Text("›", fontSize = 20.sp, color = accent)
        }
    }
}

/** The program's own card: banner, one-line pitch, and where the module sits in the audio path. */
@Composable
fun ProgramCard(dark: Boolean, versionName: String) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent
    val banner = rememberAsset("ambient.webp")

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2.6f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (dark) Color(0xFF0B0F16) else Color(0xFF10141C))
        ) {
            if (banner != null) {
                Image(
                    bitmap = banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xE60B0F16),
                                Color(0x330B0F16)
                            )
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp, end = 16.dp)
            ) {
                Text(
                    "AURORA",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    "按播放会话挂载的多段均衡叠加",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "系统均衡器在音轨末端处理 PCM，AURORA 把一套自己设计的曲线挂到每个正在播放的会话上。" +
                "关掉即回到原厂链路，不改动 /odm 配置、不重启音频服务、不更换系统文件。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = muted
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "只在小米 17 系列上验证过。其它品牌没有测试，不保证能用。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = if (dark) Color(0xFFC9A227) else Color(0xFFB07514)
        )
        Spacer(Modifier.height(16.dp))
        LinkDiagram(dark)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("版本", fontSize = 11.sp, color = muted)
            Spacer(Modifier.weight(1f))
            Text(versionName, fontSize = 12.sp, color = ink)
        }
    }
}

/**
 * Where the module sits: player session -> mixer -> our EQ -> speaker.
 *
 * Drawn with real Text nodes rather than baked into the banner image so the labels stay sharp at
 * every density and follow the light/dark palette.
 */
@Composable
private fun LinkDiagram(dark: Boolean) {
    val ink = if (dark) InkDark else Ink
    val muted = if (dark) MutedDark else Muted
    val accent = if (dark) AccentDark else Accent
    val line = if (dark) Color(0xFF3A4048) else Color(0xFFD5DAE2)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlowNode("播放器", "App 会话", false, dark, ink, muted, accent, Modifier.weight(1f))
        FlowArrow(line, false)
        FlowNode("系统混音", "AudioFlinger", false, dark, ink, muted, accent, Modifier.weight(1f))
        FlowArrow(accent, true)
        FlowNode("AURORA", "实时 EQ", true, dark, ink, muted, accent, Modifier.weight(1f))
        FlowArrow(line, false)
        FlowNode("扬声器", "300Hz–6kHz", false, dark, ink, muted, accent, Modifier.weight(1f))
    }
}

@Composable
private fun FlowNode(
    title: String,
    detail: String,
    highlight: Boolean,
    dark: Boolean,
    ink: Color,
    muted: Color,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    highlight && dark -> accent.copy(alpha = 0.16f)
                    highlight -> accent.copy(alpha = 0.10f)
                    dark -> Color(0xFF23262D)
                    else -> Color(0xFFF1F3F7)
                }
            )
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            title,
            fontSize = 10.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) accent else ink,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        Text(
            detail,
            fontSize = 8.sp,
            color = muted,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun FlowArrow(color: Color, highlight: Boolean) {
    Canvas(
        Modifier
            .width(12.dp)
            .height(12.dp)
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        drawLine(
            color = color,
            start = Offset(0f, midY),
            end = Offset(w * 0.62f, midY),
            strokeWidth = if (highlight) 2.2f else 1.4f,
            cap = StrokeCap.Round
        )
        val head = w * 0.34f
        drawLine(
            color = color,
            start = Offset(w - head, midY - head * 0.8f),
            end = Offset(w, midY),
            strokeWidth = if (highlight) 2.2f else 1.4f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w - head, midY + head * 0.8f),
            end = Offset(w, midY),
            strokeWidth = if (highlight) 2.2f else 1.4f,
            cap = StrokeCap.Round
        )
    }
}