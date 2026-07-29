package com.koneksiglobal.sapuranjau.uikit.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Token warna (ADR-0042). Palet MEREK TETAP dari seed #0F766E — bukan dynamic color: aplikasi
// berhadiah harus tampil sama di semua perangkat, dan tiap wallpaper adalah pasangan kontras yang
// tak pernah kita uji.
//
// SEMUA peran diisi, tak ada yang dibiarkan default. Kesalahan sebelumnya: "sisanya biar M3 yang
// menjaga" — default M3 adalah palet BASELINE UNGU, bukan turunan seed kita. Akibatnya nyata dan
// terlihat di emulator: chip terpilih (`secondaryContainer`) tampil lavender di tengah UI teal.
// Peran yang belum pernah dipakai satu komponen pun tetap diisi di sini, karena komponen berikutnya
// (dialog, kartu, text field di T-032/033/034) memanggilnya tanpa bertanya.
//
// Nilai = tangga tonal M3 dari seed (T10/T20/T30/T40/T80/T90/T95): mekanis, bukan selera.

private val Seed = Color(0xFF0F766E)

internal val LightScheme = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7E8DF),
    onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF6FD9CC),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E3),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF46617A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCE5FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF4FBF9),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceTint = Seed,
    inverseSurface = Color(0xFF2B3231),
    inverseOnSurface = Color(0xFFECF2F0),
    surfaceDim = Color(0xFFD5DBD9),
    surfaceBright = Color(0xFFF4FBF9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5F3),
    surfaceContainer = Color(0xFFE9F0EE),
    surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDEE4E2),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color.Black,
)

internal val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FD9CC),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA7E8DF),
    inversePrimary = Seed,
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFADCAE6),
    onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4A61),
    onTertiaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceTint = Color(0xFF6FD9CC),
    inverseSurface = Color(0xFFDDE4E2),
    inverseOnSurface = Color(0xFF2B3231),
    surfaceDim = Color(0xFF0E1514),
    surfaceBright = Color(0xFF343B3A),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2220),
    surfaceContainerHigh = Color(0xFF252C2B),
    surfaceContainerHighest = Color(0xFF303736),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF3F4947),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color.Black,
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
