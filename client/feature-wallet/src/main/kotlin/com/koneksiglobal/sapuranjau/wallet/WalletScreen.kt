package com.koneksiglobal.sapuranjau.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.koneksiglobal.sapuranjau.data.LifePackage
import com.koneksiglobal.sapuranjau.uikit.theme.Space

// Dompet nyawa (T-033, ADR-0022). Layar ini juga titik jujur soal peran nyawa: ia melanjutkan
// permainan, TIDAK menambah skor (GDD §6.2) — kalimat itu wajib ada supaya pembelian tak dijual
// sebagai jalan pintas ke puncak leaderboard (§9.5, narasi anti-judi ADR-0006/0007).
@Composable
fun WalletScreen(onMainCasual: () -> Unit, vm: WalletViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nyawa kamu: ${ui.total}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${ui.gratis} gratis · ${ui.berbayar} berbayar" +
                    (ui.kedaluwarsaTerdekat?.let { "\nKedaluwarsa terdekat: ${it.take(10)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "Nyawa melanjutkan permainan setelah kena bom. Ia tak pernah menambah skor — " +
                    "peringkat tetap ditentukan keterampilan.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            if (ui.memuat) {
                CircularProgressIndicator()
            } else {
                // Jalur gratis disebut DULU, sebelum paket berbayar: nyawa memang bisa dikumpulkan
                // tanpa membayar (ADR-0023), dan urutan di layar adalah bagian dari janji itu.
                TextButton(onClick = onMainCasual) { Text("Kumpulkan gratis: menangkan Casual (Sedang ke atas)") }

                LifePackage.entries.forEach { paket ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Space.s4),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("${paket.nyawa} nyawa", style = MaterialTheme.typography.titleMedium)
                                Text(paket.hargaTampilan, style = MaterialTheme.typography.bodyMedium)
                            }
                            Button(
                                onClick = { vm.beli(paket) },
                                enabled = ui.sedangMembeli == null,
                            ) { Text(if (ui.sedangMembeli == paket) "Memproses…" else "Beli") }
                        }
                    }
                }
            }

            ui.pesan?.let { pesan ->
                Text(pesan, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                TextButton(onClick = vm::tutupPesan) { Text("Tutup") }
            }
        }
    }
}
