package com.koneksiglobal.sapuranjau.uikit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.koneksiglobal.sapuranjau.uikit.theme.SapuRanjauTheme
import com.koneksiglobal.sapuranjau.uikit.theme.Space

// Papan Minesweeper. Dipakai casual (state dari engine lokal) maupun turnamen (state dari server) —
// komponen ini tak tahu bedanya, ia cuma merender `cells`.
//
// ponytail: baris/kolom Compose biasa, BUKAN LazyGrid. Grid terbesar yang mungkin (expert klasik
// 16×30 = 480 sel, ADR-0031) masih murah diukur sekaligus — ganti kalau profil menunjukkan jank (03 §3).
//
// `cellSize` sengaja PARAMETER, bukan dihitung sendiri di sini: pemanggil biasanya membungkus papan
// dengan scroll, dan di dalam scroll lebar yang "tersedia" tak terhingga — komponen yang mengukur
// dirinya sendiri di situ akan selalu memilih ukuran maksimum lalu terpotong (terbukti di emulator).
@Composable
fun MineGrid(
    width: Int,
    height: Int,
    cellSize: Dp,
    cellAt: (x: Int, y: Int) -> CellState,
    onTap: (x: Int, y: Int) -> Unit,
    onLongPress: (x: Int, y: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Space.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (y in 0 until height) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
                for (x in 0 until width) {
                    MineCell(
                        state = cellAt(x, y),
                        onTap = { onTap(x, y) },
                        onLongPress = { onLongPress(x, y) },
                        modifier = Modifier.width(cellSize),
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

// Sel sebesar mungkin agar papan MUAT lebar yang tersedia — tapi tak pernah lebih kecil dari batas
// yang masih bisa disentuh. Papan 16 kolom memang tak muat di ponsel potret (16×32dp + gap ≈ 572dp
// di layar ±411dp): di titik itu papan DIGESER pemanggil, bukan diperkecil sampai jari meleset.
//
// 32dp masih di bawah target sentuh 48dp (03 §5) — kompromi sadar: papan expert klasik tak punya
// bentuk lain di ponsel. Naikkan setelah playtest kalau salah-tap ternyata sering.
val MinCellSize = 32.dp
val MaxCellSize = 56.dp

fun cellSizeFor(availableWidth: Dp, columns: Int): Dp =
    ((availableWidth - Space.s1 * (columns - 1)) / columns).coerceIn(MinCellSize, MaxCellSize)

@Preview(name = "Papan 9×9 — terang", widthDp = 360)
@Composable
private fun PreviewGridLight() = PreviewGrid(dark = false)

@Preview(name = "Papan 9×9 — gelap", widthDp = 360)
@Composable
private fun PreviewGridDark() = PreviewGrid(dark = true)

@Composable
private fun PreviewGrid(dark: Boolean) {
    SapuRanjauTheme(darkTheme = dark) {
        MineGrid(
            width = 9,
            height = 9,
            cellSize = cellSizeFor(360.dp - Space.s4 * 2, 9),
            cellAt = { x, y ->
                when {
                    x == y -> CellState.Revealed((x % 8) + 1)
                    x == 0 -> CellState.Flagged
                    y == 8 -> CellState.Revealed(0)
                    else -> CellState.Hidden
                }
            },
            onTap = { _, _ -> },
            onLongPress = { _, _ -> },
        )
    }
}
