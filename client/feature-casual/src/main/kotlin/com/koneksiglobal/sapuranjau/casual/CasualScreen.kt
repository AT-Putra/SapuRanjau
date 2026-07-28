package com.koneksiglobal.sapuranjau.casual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koneksiglobal.sapuranjau.engine.CellIndex
import com.koneksiglobal.sapuranjau.uikit.CellState
import com.koneksiglobal.sapuranjau.uikit.MineGrid
import com.koneksiglobal.sapuranjau.uikit.theme.SapuRanjauTheme
import com.koneksiglobal.sapuranjau.uikit.theme.Space

// Layar casual: satu aksi utama = bermain (03 §1). Tanpa login, tanpa jaringan.
@Composable
fun CasualScreen(vm: CasualViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
            ) {
                Difficulty.entries.forEach { d ->
                    FilterChip(
                        selected = state.difficulty == d,
                        onClick = { vm.pilihKesulitan(d) },
                        label = { Text(d.label) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚑ ${state.sisaBom}", style = MaterialTheme.typography.titleMedium)
                Text(state.pesan(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Button(onClick = vm::ulang) { Text("Ulang") }
            }

            // Papan sulit (16×30) lebih lebar dari layar potret → digeser, bukan diperkecil sampai
            // tak bisa disentuh (03 §5, target sentuh).
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                MineGrid(
                    width = state.config.gridWidth,
                    height = state.config.gridHeight,
                    cellAt = { x, y -> state.cellAt(x, y) },
                    onTap = { x, y -> vm.tap(CellIndex(x, y)) },
                    onLongPress = { x, y -> vm.tahan(CellIndex(x, y)) },
                    enabled = state.bolehDisentuh,
                )
            }
        }
    }
}

private fun CasualUiState.pesan(): String = when (status) {
    GameStatus.SIAP -> "Ketuk untuk mulai"
    GameStatus.MENYIAPKAN -> "Menyiapkan papan…"
    GameStatus.MAIN -> "Tahan untuk bendera"
    GameStatus.KALAH -> "Kena bom"
    GameStatus.MENANG -> "Bersih! 🎉"
}

// Pemetaan state permainan → state tampilan. Sel bom lain TIDAK dibuka saat kalah: peta bom hanya
// diketahui `engine-core` (`Board.mines` internal), dan membukanya butuh perluasan kontrak yang
// RATIFIED (05 §1) — belum sepadan untuk kosmetik.
private fun CasualUiState.cellAt(x: Int, y: Int): CellState {
    val at = CellIndex(x, y)
    return when {
        at == explodedAt -> CellState.Mine
        at in revealed -> CellState.Revealed(revealed.getValue(at))
        at in flags -> CellState.Flagged
        else -> CellState.Hidden
    }
}

@Preview(name = "Casual — terang")
@Composable
private fun PreviewCasualLight() {
    SapuRanjauTheme(darkTheme = false) { CasualScreen() }
}

@Preview(name = "Casual — gelap")
@Composable
private fun PreviewCasualDark() {
    SapuRanjauTheme(darkTheme = true) { CasualScreen() }
}
