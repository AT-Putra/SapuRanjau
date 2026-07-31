package com.koneksiglobal.sapuranjau.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.koneksiglobal.sapuranjau.data.LeaderboardEntry
import com.koneksiglobal.sapuranjau.uikit.theme.Radius
import com.koneksiglobal.sapuranjau.uikit.theme.Space

// Peringkat + inbox + klaim hadiah (T-034). Inbox adalah SATU-SATUNYA kanal pemberitahuan selama
// push FCM belum ada (ditunda di T-029) — karena itu ia satu layar dengan peringkat, bukan disembunyikan.
@Composable
fun LeaderboardScreen(vm: LeaderboardViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var formNama by remember { mutableStateOf(false) }
    var formKlaim by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ui.namaTampilan?.let { "Nama: $it" } ?: "Peringkat",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { formNama = true }) { Text("Ubah nama") }
            }

            when {
                ui.memuat -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                ui.terkunci -> Text(
                    "Belum ada periode turnamen yang berjalan, jadi belum ada peringkat. Nyawa yang kamu " +
                        "kumpulkan sekarang tetap terpakai saat periode berikutnya dibuka.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Space.s2),
                    ) {
                        items(ui.entries, key = { it.rank }) { Baris(it) }

                        if (ui.pesanMasuk.isNotEmpty()) {
                            item {
                                Text(
                                    "Pesan (${ui.belumDibaca} belum dibaca)",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = Space.s4),
                                )
                            }
                            items(ui.pesanMasuk, key = { it.id }) { pesan ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        pesan.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (pesan.readAt == null) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    Text(pesan.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
                                    if (pesan.readAt == null) {
                                        TextButton(onClick = { vm.tandaiDibaca(pesan.id) }) { Text("Tandai dibaca") }
                                    }
                                }
                            }
                        }
                    }

                    // Peringkat sendiri menempel di bawah daftar (ADR-0046) — ditampilkan HANYA saat
                    // barisnya tak ada di halaman yang sedang dibuka; kalau ada, menampilkannya dua
                    // kali cuma membuat pemain mengira ia punya dua peringkat.
                    ui.myEntry?.takeIf { milikku -> ui.entries.none { it.me } }?.let { milikku ->
                        Text(
                            "Peringkat kamu",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = Space.s2),
                        )
                        Baris(milikku)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = vm::halamanSebelum, enabled = ui.halaman > 0) { Text("Sebelumnya") }
                        Text("Hal. ${ui.halaman + 1}", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::halamanBerikut, enabled = ui.adaHalamanLagi) { Text("Berikutnya") }
                    }

                    // Pemenang diberi tahu lewat inbox lalu mengisi form ini (ADR-0021). Tombolnya
                    // selalu ada karena klien memang tak punya cara bertanya "apakah saya menang?" —
                    // server yang menjawab, dan 409-nya adalah jawaban yang sah, bukan kegagalan.
                    TextButton(onClick = { formKlaim = true }) { Text("Klaim hadiah (khusus pemenang)") }

                    // Aturannya kini juga tampil per-baris ("sedang jeda hadiah"), tapi catatan ini
                    // tetap ada: badge menjelaskan APA, kalimat ini menjelaskan KENAPA.
                    Text(
                        "Pemenang sebuah periode dilewati dari daftar pemenang 3 periode berikutnya, tapi tetap " +
                            "bermain dan tetap tampil di peringkat.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            ui.catatan?.let { catatan ->
                Text(catatan, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                TextButton(onClick = vm::tutupCatatan) { Text("Tutup") }
            }
        }
    }

    if (formNama) {
        FormNama(onBatal = { formNama = false }) {
            vm.ubahNama(it)
            formNama = false
        }
    }
    if (formKlaim) {
        FormKlaim(onBatal = { formKlaim = false }) { hp, ewallet, alamat ->
            vm.klaimHadiah(hp, ewallet, alamat)
            formKlaim = false
        }
    }
}

@Composable
private fun Baris(entry: LeaderboardEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            // Baris sendiri ditandai warna DAN nama — jangan sampaikan info hanya lewat warna (03 §5).
            .background(if (entry.me) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .padding(Space.s2),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("${entry.rank}. ${entry.name}" + if (entry.me) " (kamu)" else "", style = MaterialTheme.typography.bodyLarge)
            // Jeda hadiah (ADR-0027) ditulis sebagai TEKS di barisnya, bukan sekadar warna atau ikon
            // (`03` §5) — dan di baris orangnya sendiri, bukan sebagai catatan di kaki layar yang
            // justru paling mungkin terlewat oleh yang berkepentingan.
            if (entry.onCooldown) {
                Text(
                    "sedang jeda hadiah",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("${entry.totalScore}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

// Sengaja MULAI KOSONG: nama yang tampil bisa jadi fallback "Pemain #<id>" (ADR-0039), dan `#`
// justru di luar karakter yang diterima server — mem-prefill-nya berarti menyodorkan nilai yang
// pasti ditolak 400. Pola karakter di sini menyalin aturan server supaya penolakan itu tak perlu
// menempuh perjalanan bolak-balik; penegaknya tetap server.
private val POLA_NAMA = Regex("""^[\p{L}\p{N} ._'-]{2,20}$""")

@Composable
private fun FormNama(onBatal: () -> Unit, onSimpan: (String) -> Unit) {
    var nama by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onBatal,
        title = { Text("Nama tampilan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                OutlinedTextField(
                    value = nama,
                    onValueChange = { nama = it },
                    label = { Text("2–20 karakter: huruf, angka, spasi . _ ' -") },
                    singleLine = true,
                )
                Text("Nama ini yang muncul di peringkat dan daftar pemenang.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onSimpan(nama) }, enabled = POLA_NAMA.matches(nama.trim())) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onBatal) { Text("Batal") } },
    )
}

// PII (ADR-0021/0030): dikirim sekali, tak pernah dipantulkan balik server, dan diverifikasi admin
// lewat telepon. Validasi di sini hanya untuk menghemat perjalanan bolak-balik — penegaknya server.
@Composable
private fun FormKlaim(onBatal: () -> Unit, onKirim: (String, String, String) -> Unit) {
    var hp by remember { mutableStateOf("") }
    var ewallet by remember { mutableStateOf("") }
    var alamat by remember { mutableStateOf("") }
    val hpSah = hp.filter { it.isDigit() }.length in 8..15
    val adaTujuan = ewallet.isNotBlank() || alamat.isNotBlank()

    AlertDialog(
        onDismissRequest = onBatal,
        title = { Text("Klaim hadiah") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(
                    "Admin menghubungi lewat telepon untuk memverifikasi sebelum hadiah dikirim. Isi nomor HP " +
                        "aktif, lalu e-wallet atau alamat pengiriman.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = hp, onValueChange = { hp = it }, label = { Text("No. HP") }, singleLine = true)
                OutlinedTextField(value = ewallet, onValueChange = { ewallet = it }, label = { Text("E-wallet (opsional)") }, singleLine = true)
                OutlinedTextField(value = alamat, onValueChange = { alamat = it }, label = { Text("Alamat (opsional)") })
            }
        },
        confirmButton = {
            Button(onClick = { onKirim(hp, ewallet, alamat) }, enabled = hpSah && adaTujuan) { Text("Kirim") }
        },
        dismissButton = { TextButton(onClick = onBatal) { Text("Batal") } },
    )
}
