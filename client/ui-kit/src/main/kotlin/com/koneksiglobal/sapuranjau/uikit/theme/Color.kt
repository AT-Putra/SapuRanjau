package com.koneksiglobal.sapuranjau.uikit.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Token warna (ADR-0042). Palet MEREK TETAP dari seed #0F766E — bukan dynamic color: aplikasi
// berhadiah harus tampil sama di semua perangkat, dan tiap wallpaper adalah pasangan kontras yang
// tak pernah kita uji.
//
// Peran M3 yang tak disebut di sini memakai default Material 3. Yang di-override hanya yang
// benar-benar membawa identitas; sisanya biar M3 yang menjaga konsistensinya.

private val Seed = Color(0xFF0F766E)

internal val LightScheme = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7E8DF),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    background = Color(0xFFF4FBF9),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9),
    onSurface = Color(0xFF161D1C),
    error = Color(0xFFB3261E),
)

internal val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD9CC),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA7E8DF),
    secondary = Color(0xFFB1CCC7),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDDE4E2),
    error = Color(0xFFFFB4AB),
)

// Warna khusus papan — bukan peran Material, jadi hidup di token sendiri (03 §2).
data class GameColors(
    val cellHidden: Color,
    val cellRevealed: Color,
    val cellBorder: Color,
    val flag: Color,
    val mineDanger: Color,
    /** Indeks 1..8 = jumlah bom tetangga; indeks 0 tak dipakai (sel kosong tak menampilkan angka). */
    val numbers: List<Color>,
)

// Konvensi klasik (1 biru, 2 hijau, 3 merah, …) karena pemain Minesweeper membacanya tanpa berpikir.
// **Nilai klasik apa adanya GAGAL WCAG AA di tema terang** — #1565C0 = 4,48 · #2E7D32 = 3,99 ·
// #C62828 = 4,38 terhadap `cellRevealed`. Digelapkan seperti di bawah; rasionya dijaga
// `ContrastTest`, bukan janji dokumen (ADR-0042, 03 §5).
internal val LightGameColors = GameColors(
    cellHidden = Color(0xFFB8CCC8),
    cellRevealed = Color(0xFFDCE5E3),
    cellBorder = Color(0xFF9BB2AD),
    // #B71C1C (merah klasik) hanya 3,91:1 di atas `cellHidden` — digelapkan sampai lolos AA.
    flag = Color(0xFF8E0000),
    mineDanger = Color(0xFF8E0000),
    numbers = listOf(
        Color.Unspecified, // 0 — tak pernah dirender
        Color(0xFF0D47A1), Color(0xFF1B5E20), Color(0xFFB71C1C), Color(0xFF4527A0),
        Color(0xFF8D3B00), Color(0xFF00695C), Color(0xFF212121), Color(0xFF5F6368),
    ),
)

internal val DarkGameColors = GameColors(
    cellHidden = Color(0xFF33403E),
    cellRevealed = Color(0xFF243230),
    cellBorder = Color(0xFF4A5754),
    flag = Color(0xFFFF8A80),
    mineDanger = Color(0xFFFF5449),
    numbers = listOf(
        Color.Unspecified,
        Color(0xFF7FB4FF), Color(0xFF7BD68B), Color(0xFFFF9A94), Color(0xFFB9A7FF),
        Color(0xFFFFB77A), Color(0xFF5FD9C9), Color(0xFFE6E6E6), Color(0xFFB0B6B5),
    ),
)

val LocalGameColors = staticCompositionLocalOf { LightGameColors }
