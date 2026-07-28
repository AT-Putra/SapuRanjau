package com.koneksiglobal.sapuranjau.uikit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

// Tema aplikasi (ADR-0042). Dark mode wajib dan datang dari SEED YANG SAMA — bukan palet kedua yang
// bisa melenceng sendiri.
@Composable
fun SapuRanjauTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGameColors provides if (darkTheme) DarkGameColors else LightGameColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}

// Token ukuran & gerak (03 §2). Dipakai lewat nama, jangan hardcode angka di feature.
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
}

object Radius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 16.dp
}

object Motion {
    const val SHORT_MS = 200
    const val MEDIUM_MS = 300
}

// Target sentuh minimum (03 §5). Sel boleh tampil lebih kecil dari ini di grid rapat, tapi area
// sentuhnya tak boleh — strategi grid rapat masih terbuka sampai diuji di perangkat nyata.
val MinTouchTarget = 48.dp
