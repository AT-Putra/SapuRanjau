package com.koneksiglobal.sapuranjau.tournament

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koneksiglobal.sapuranjau.data.Cell
import com.koneksiglobal.sapuranjau.data.LevelStatus
import com.koneksiglobal.sapuranjau.uikit.CellState
import com.koneksiglobal.sapuranjau.uikit.MineGrid
import com.koneksiglobal.sapuranjau.uikit.cellSizeFor
import com.koneksiglobal.sapuranjau.uikit.theme.Space

// Satu layar, empat keadaan gerbang + permainan. Keadaan gerbang sengaja LAYAR PENUH, bukan toast:
// pemain harus tahu kenapa turnamen tak bisa dimasuki (ADR-0021/0025/0026).
@Composable
fun TournamentScreen(
    onMainCasual: () -> Unit,
    onBeliNyawa: () -> Unit,
    vm: TournamentViewModel = viewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = ui) {
                TournamentUi.Memuat -> Pesan("Menyiapkan turnamen…") { CircularProgressIndicator() }

                TournamentUi.Terkunci -> Pesan(
                    "Belum ada periode turnamen yang berjalan. Sementara ini kamu bisa berlatih di mode Casual — " +
                        "nyawa yang kamu kumpulkan tetap tersimpan.",
                ) { Button(onClick = onMainCasual) { Text("Main Casual") } }

                is TournamentUi.Dilarang -> Pesan(
                    buildString {
                        append("Akun ini sedang tak boleh mengikuti turnamen karena pembelian nyawa yang dikembalikan (refund/chargeback).")
                        s.sisaPeriode?.let { append(" Sisa $it periode lagi.") }
                        append(" Mode Casual tetap terbuka.")
                    },
                ) { Button(onClick = onMainCasual) { Text("Main Casual") } }

                is TournamentUi.ButuhPersetujuan -> DialogSnK(
                    versi = s.tncVersion,
                    onSetuju = { vm.setujuiSnK(s.tncVersion) },
                    onTolak = onMainCasual,
                )

                is TournamentUi.Gagal -> Pesan(s.pesan) { Button(onClick = vm::muat) { Text("Coba lagi") } }

                is TournamentUi.Bermain -> Papan(s.level, vm, onMainCasual, onBeliNyawa)
            }
        }
    }
}

@Composable
private fun Pesan(teks: String, aksi: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.s4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(teks, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        aksi()
    }
}

// Extension ColumnScope: papan memakai `weight(1f)` supaya header tetap tersemat & papan yang
// digeser tak mendorong apa pun keluar layar.
@Composable
private fun ColumnScope.Papan(level: LevelUi, vm: TournamentViewModel, onMainCasual: () -> Unit, onBeliNyawa: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Level ${level.levelIndex + 1}", style = MaterialTheme.typography.titleMedium)
        Text("⚑ ${level.sisaBom}", style = MaterialTheme.typography.titleMedium)
        Text("${level.movesCount} langkah", style = MaterialTheme.typography.titleMedium)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
        val cellSize = cellSizeFor(maxWidth, level.gridWidth)
        Box(modifier = Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            MineGrid(
                width = level.gridWidth,
                height = level.gridHeight,
                cellSize = cellSize,
                cellAt = { x, y -> level.cellAt(x, y) },
                onTap = vm::tap,
                onLongPress = vm::tahan,
                enabled = level.bolehDisentuh,
            )
        }
    }

    when {
        level.status == LevelStatus.LEVEL_CLEARED -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Level bersih") },
            text = { Text("Skor level ini: ${level.score ?: 0}. Level berikutnya dimulai bersih — tak ada pengulangan (satu kesempatan per level).") },
            confirmButton = { Button(onClick = vm::lanjutLevel) { Text("Lanjut") } },
        )

        level.awaitingLife -> DialogNyawa(level, vm, onMainCasual, onBeliNyawa)
    }
}

// Isi dialog ini TERIKAT ADR-0037 — tiga hal wajib ada saat nyawa berikutnya tak lagi menambah skor:
// (1) skor level sudah 0 dan tak bisa naik, (2) jalur casual GRATIS berdampingan dengan tombol beli
// (inilah yang menjaga §9.5 aturan 5: time-gated, bukan paywalled), (3) pengingat refund → ban.
@Composable
private fun DialogNyawa(level: LevelUi, vm: TournamentViewModel, onMainCasual: () -> Unit, onBeliNyawa: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (level.dompetKosong) "Nyawa habis" else "Kena bom") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                if (level.skorSudahNol) {
                    Text(
                        "Level ini sudah bernilai 0 dan tak bisa naik lagi — nyawa berikutnya hanya melanjutkan " +
                            "permainan, bukan menambah skor.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Progres level ini tersimpan: kamu bisa berhenti sekarang, mengumpulkan nyawa gratis lewat " +
                        "kemenangan di mode Casual, lalu melanjutkan dari titik ini.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Catatan: pembelian nyawa yang di-refund atau di-chargeback membuat skor periode berjalan " +
                        "hangus dan akun tak boleh ikut turnamen selama 3 periode.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            if (level.dompetKosong) {
                // Dialog inilah titik beli yang dimaksud ADR-0037 — ia membuka dompet, tempat jalur
                // gratis dan paket berbayar berdiri berdampingan (T-033).
                Button(onClick = onBeliNyawa) { Text("Beli nyawa") }
            } else {
                Button(onClick = vm::pakaiNyawa) { Text("Pakai 1 nyawa") }
            }
        },
        dismissButton = { TextButton(onClick = onMainCasual) { Text("Main Casual (gratis)") } },
    )
}

@Composable
private fun DialogSnK(versi: String, onSetuju: () -> Unit, onTolak: () -> Unit) {
    AlertDialog(
        onDismissRequest = onTolak,
        title = { Text("Peraturan turnamen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(
                    "Turnamen ini gratis: tak ada biaya masuk dan hadiah didanai sponsor. Peringkat ditentukan " +
                        "keterampilan — skor, waktu, dan jumlah langkah — bukan undian.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Nyawa hanya melanjutkan permainan setelah kena bom; ia tak pernah menambah skor. " +
                        "Pembelian yang di-refund/chargeback → skor periode hangus + tak boleh ikut turnamen 3 periode.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Versi S&K: $versi", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onSetuju) { Text("Setuju & masuk") } },
        dismissButton = { TextButton(onClick = onTolak) { Text("Nanti saja") } },
    )
}

// Peta state papan → state tampilan. Bom yang meledak dikirim server sebagai akhir level; peta bom
// selebihnya memang tak pernah sampai ke klien (ARCH §6.1 — tanpa peta bom).
private fun LevelUi.cellAt(x: Int, y: Int): CellState {
    val at = Cell(x, y)
    return when {
        at in revealed -> CellState.Revealed(revealed.getValue(at))
        at in flags -> CellState.Flagged
        else -> CellState.Hidden
    }
}
