package com.koneksiglobal.sapuranjau.uikit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.koneksiglobal.sapuranjau.uikit.theme.LocalGameColors
import com.koneksiglobal.sapuranjau.uikit.theme.Motion
import com.koneksiglobal.sapuranjau.uikit.theme.Radius
import com.koneksiglobal.sapuranjau.uikit.theme.SapuRanjauTheme

// State satu sel (03 §3). Tipe milik UI — sengaja BUKAN tipe engine, supaya komponen ini juga bisa
// dipakai turnamen yang state-nya datang dari server, bukan dari engine lokal.
sealed interface CellState {
    data object Hidden : CellState
    data object Flagged : CellState

    /** `adjacentMines` 0 = sel kosong (tak menampilkan angka). */
    data class Revealed(val adjacentMines: Int) : CellState

    /** Hanya saat kalah / reveal akhir. */
    data object Mine : CellState
}

// Satu handler tap KONTEKSTUAL (ADR-0019, 03 §3): tap sel tersembunyi = reveal, tap angka terbuka =
// chord, long-press = flag. Tanpa tombol mode — mode toggle adalah sumber salah-tap nomor satu, dan
// turnamen one-shot menghukum salah-tap secara permanen (ADR-0024).
@Composable
fun MineCell(
    state: CellState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalGameColors.current
    val target = when (state) {
        CellState.Hidden, CellState.Flagged -> colors.cellHidden
        is CellState.Revealed -> colors.cellRevealed
        CellState.Mine -> colors.mineDanger
    }
    // Warna dianimasikan supaya flood-fill terbaca sebagai gelombang, bukan kedipan (03 §4).
    val background by animateColorAsState(target, tween(Motion.SHORT_MS), label = "cellBackground")

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.sm))
            .background(background)
            .border(HAIRLINE, colors.cellBorder, RoundedCornerShape(Radius.sm))
            .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLongPress)
            .semantics { contentDescription = state.describe() },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            // Angka + warna, tak pernah warna saja (03 §5: jangan sampaikan info hanya lewat warna).
            is CellState.Revealed -> if (state.adjacentMines > 0) {
                Text(
                    text = state.adjacentMines.toString(),
                    color = colors.numbers[state.adjacentMines],
                    fontWeight = FontWeight.Bold,
                )
            }
            CellState.Flagged -> Text("⚑", color = colors.flag, fontWeight = FontWeight.Bold)
            CellState.Mine -> Text("✱", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
            CellState.Hidden -> Unit
        }
    }
}

// Screen reader membaca STATE-nya, bukan sekadar "tombol" (03 §5).
private fun CellState.describe(): String = when (this) {
    CellState.Hidden -> "sel tertutup"
    CellState.Flagged -> "sel berbendera"
    CellState.Mine -> "bom"
    is CellState.Revealed -> if (adjacentMines == 0) "sel kosong" else "$adjacentMines bom di sekitar"
}

private val HAIRLINE = 1.dp

@Preview(name = "Sel — terang")
@Composable
private fun PreviewCellsLight() = PreviewCells(dark = false)

@Preview(name = "Sel — gelap")
@Composable
private fun PreviewCellsDark() = PreviewCells(dark = true)

@Composable
private fun PreviewCells(dark: Boolean) {
    SapuRanjauTheme(darkTheme = dark) {
        Row {
            listOf(
                CellState.Hidden,
                CellState.Flagged,
                CellState.Revealed(0),
                CellState.Revealed(1),
                CellState.Revealed(3),
                CellState.Revealed(8),
                CellState.Mine,
            ).forEach {
                MineCell(state = it, onTap = {}, onLongPress = {}, modifier = Modifier.size(40.dp))
            }
        }
    }
}
