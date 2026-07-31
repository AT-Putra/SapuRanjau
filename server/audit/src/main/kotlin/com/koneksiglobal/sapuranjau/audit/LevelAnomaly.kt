package com.koneksiglobal.sapuranjau.audit

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

// Sinyal anomali bot untuk level turnamen yang baru selesai (ARCH §9, GDD §10; titipan ADR-0037
// & ADR-0023). **MENANDAI, TIDAK MEMBLOKIR** — sama seperti jalur casual T-024: ambangnya belum
// pernah dikalibrasi terhadap satu pun pemain nyata, jadi menghukum otomatis = menghukum tebakan.
//
// Yang SENGAJA tak ada di sini, dan alasannya (T-027):
//   • **jeda antar-klik** — `MoveCodec` menyimpan 2 byte per langkah tanpa waktu (`board.moves`/
//     `level_score.moves`); yang ada cuma `active_time_ms` agregat per level. Analisis timing
//     butuh format langkah baru = ADR + migrasi, dibayar saat ada bukti bot lolos, bukan sebelumnya.
//   • **urutan identik antar-akun** — seed diundi per-papan (ADR-0031/T-022), jadi dua pemain
//     praktis tak pernah memainkan papan yang sama; urutan identik nyaris mustahil muncul walau
//     keduanya bot. Sinyal ini mahal dijalankan dan hampir tak mungkin berbunyi di desain ini.
@Service
class LevelAnomalyDetector(
    private val audit: AuditService,
    // Ambang "terlalu cepat" per langkah TERSKOR. 80 ms = nilai yang sama dengan jalur casual
    // (T-024, ADR-0023) supaya satu angka mengatur keduanya. Tunable, BELUM dikalibrasi.
    @Value("\${sapuranjau.audit.min-ms-per-move:80}") private val minMsPerMove: Long,
    // "Nyawa terbakar JAUH di atas `lifeCap`" (ADR-0037) — ADR-nya tak menyebut angka. Saya pilih
    // `lifeCap + 3`: di level mudah `lifeCap` bernilai kecil sehingga mentok-cap itu rutin (ADR-0037),
    // jadi ambangnya harus di atas "mentok", bukan di "mentok".
    @Value("\${sapuranjau.audit.lives-over-cap:3}") private val livesOverCap: Int,
) {
    fun inspectLevel(userId: Long, runId: Long, levelIndex: Int, play: LevelFacts) {
        val signals = signalsOf(play, minMsPerMove, livesOverCap)
        if (signals.isEmpty()) return
        audit.record(
            Actor.SYSTEM,
            userId,
            "level_anomaly",
            "run:$runId",
            mapOf(
                "signals" to signals,
                "levelIndex" to levelIndex,
                "moves" to play.moves,
                "parMoves" to play.parMoves,
                "activeTimeMs" to play.activeTimeMs,
                "msPerMove" to play.msPerMove(),
                "livesUsed" to play.livesUsed,
                "lifeCap" to play.lifeCap,
            ),
        )
    }
}

// Aturannya murni fungsi dari angka yang sudah dihitung `game` saat menutup level — tak ada query
// tambahan di jalur selesai-level, dan bisa diuji tanpa database maupun Spring.
internal fun signalsOf(p: LevelFacts, minMsPerMove: Long, livesOverCap: Int): List<String> = buildList {
    // Menyamai/mengalahkan jalur bersih solver. Nama sinyal sama dengan jalur casual (T-024).
    if (p.moves in 1..p.parMoves) add("perfect_path")
    if (p.moves > 0 && p.msPerMove() < minMsPerMove) add("too_fast")
    if (p.livesUsed >= p.lifeCap + livesOverCap) add("lives_over_cap")
}

// Fakta level yang sudah selesai — persis angka yang dipakai `scoring` (ADR-0017), tanpa kepemilikan
// baru atas data apa pun.
data class LevelFacts(
    val moves: Int, // langkah TERSKOR (ADR-0018)
    val parMoves: Int,
    val activeTimeMs: Long,
    val livesUsed: Int,
    val lifeCap: Int,
) {
    fun msPerMove(): Long = if (moves > 0) activeTimeMs / moves else 0
}
