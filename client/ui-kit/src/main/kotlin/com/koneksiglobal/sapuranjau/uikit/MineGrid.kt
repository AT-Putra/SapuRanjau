package com.koneksiglobal.sapuranjau.uikit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.koneksiglobal.sapuranjau.uikit.theme.SapuRanjauTheme
import com.koneksiglobal.sapuranjau.uikit.theme.Space

// Papan Minesweeper. Dipakai casual (state dari engine lokal) maupun turnamen (state dari server) —
// komponen ini tak tahu bedanya, ia cuma merender `cells`.
//
// ponytail: baris/kolom Compose biasa, BUKAN LazyGrid. Grid terbesar yang mungkin (expert klasik
// 16×30 = 480 sel, ADR-0031) muat semua di layar sekaligus — lazy layout justru menambah biaya
// pengukuran untuk sesuatu yang tak pernah di-scroll. Ganti kalau profil menunjukkan jank (03 §3).
@Composable
fun MineGrid(
    width: Int,
    height: Int,
    cellAt: (x: Int, y: Int) -> CellState,
    onTap: (x: Int, y: Int) -> Unit,
    onLongPress: (x: Int, y: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier) {
        // Sel dibuat sebesar mungkin tanpa melebihi lebar layar — papan sempit tetap terbaca,
        // papan lebar tak terpotong.
        val gap = Space.s1
        val cellSize = ((maxWidth - gap * (width - 1)) / width).coerceAtMost(56.dp)

        Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
            for (y in 0 until height) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
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
}

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
