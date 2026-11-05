package com.aurora.lsp.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Whether the in-app theme is dark. Ported KSU components read this to tint their glass. */
val LocalAuroraDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isInDarkTheme(): Boolean = LocalAuroraDarkTheme.current