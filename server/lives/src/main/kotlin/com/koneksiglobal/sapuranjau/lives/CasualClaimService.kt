package com.koneksiglobal.sapuranjau.lives

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import com.koneksiglobal.sapuranjau.engine.CellIndex
import com.koneksiglobal.sapuranjau.engine.LevelConfig
import com.koneksiglobal.sapuranjau.engine.MinesweeperEngine
import com.koneksiglobal.sapuranjau.engine.RevealResult
import com.koneksiglobal.sapuranjau.integrity.IntegrityGate
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Earn nyawa dari casual (T-024, ADR-0023): klaim online, server re-simulasi `(seed, moves)`.
//
// Yang PERLU jujur disebut: ini **bukan** anti-bot. Solver no-guess ikut ter-ship di client
// (engine-core dipakai bersama), jadi bot bisa memproduksi replay-menang yang valid tanpa bermain —
// ADR-0023 sudah menyatakan itu. Yang benar-benar mem-bound = cap 1/5/10 + Play Integrity (T-028)
// + akun Google terverifikasi + nilai-farming rendah (nyawa tak menaikkan skor, ADR-0017).
// Re-simulasi tetap perlu: ia menutup pemalsuan TRIVIAL (klaim tanpa main sama sekali) dan
// memberi bahan sinyal anomali untuk T-027.
@Service
class CasualClaimService(
    private val lives: LifeService,
    private val jdbc: JdbcClient,
    private val audit: AuditService, // T-027: satu penulis audit_event utk seluruh server
    private val integrity: IntegrityGate, // T-028: gerbang device (ADR-0023/0041)
    // Kebijakan earn (ADR-0023) — default = angka ADR, tunable, pindah ke admin-config saat panel
    // ada (T-042), pola sama dengan `ms-per-par-move` (ADR-0036). **Lantai legal §9.5: jangan
    // turunkan cap tanpa pertimbangan hukum — 1/5/10 sudah dekat lantai.**
    @Value("\${sapuranjau.lives.casual.cap-daily:1}") private val capDaily: Int,
    @Value("\${sapuranjau.lives.casual.cap-weekly:5}") private val capWeekly: Int,
    @Value("\${sapuranjau.lives.casual.cap-monthly:10}") private val capMonthly: Int,
    // Ambang "≥ medium" (ADR-0023). Default = intermediate klasik 16×16/40 (density 15,6%).
    @Value("\${sapuranjau.lives.casual.min-mines:40}") private val minMines: Int,
    @Value("\${sapuranjau.lives.casual.min-density:0.15}") private val minDensity: Double,
    // Jendela cap = kalender waktu setempat, bukan UTC (ADR-0023 "jendela reset tetap").
    @Value("\${sapuranjau.lives.casual.zone:Asia/Jakarta}") private val zone: String,
) {
    private val engine = MinesweeperEngine()

    @Transactional
    fun claim(uid: String, req: CasualClaimRequest): CasualClaimResponse {
        validate(req)
        val userId = lives.userIdOf(uid)
        // Gerbang device (ADR-0023 memang menuntutnya di sini; mekanismenya ADR-0041/T-028). Nyawa
        // hasil klaim bisa dipakai di turnamen berhadiah → titik cetak nilai, bukan sekadar baca.
        integrity.require(userId)
        lives.lockUser(userId) // cek-cap lalu cetak harus atomik per pemain

        if (!meetsThreshold(req)) return respond(userId, ClaimResult.BELOW_THRESHOLD)
        capReached(lives.earnedCasualCounts(userId, zone))?.let { return respond(userId, it) }

        // Verifikasi (mahal: generate + solve) sengaja SETELAH cek murah — klaim yang sudah kena cap
        // tak layak membakar CPU.
        val v = verifyWin(req)
        if (!lives.grantEarnedCasual(userId)) return respond(userId, ClaimResult.NO_ACTIVE_PERIOD)
        auditAnomalies(userId, req, v)
        return respond(userId, ClaimResult.GRANTED)
    }

    // ── Batas input (trust boundary) ─────────────────────────────────────────────────────────────
    // Payload datang dari klien yang tak dipercaya dan memicu generate+solve. Tanpa pagar ini,
    // satu request bisa meminta papan 1000×1000 atau density mustahil yang membuat generator
    // memutar K=500 percobaan solve — DoS murah. Ini pagar keamanan, BUKAN tunable kebijakan.
    private fun validate(req: CasualClaimRequest) {
        fun bad(msg: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, msg)
        if (req.gridWidth !in 1..MAX_SIDE || req.gridHeight !in 1..MAX_SIDE) bad("Grid di luar batas 1..$MAX_SIDE.")
        val cells = req.gridWidth * req.gridHeight
        if (cells > MAX_CELLS) bad("Grid $cells sel melebihi batas $MAX_CELLS.")
        if (req.mineCount !in 1 until cells) bad("mineCount di luar 1..${cells - 1}.")
        if (req.mineCount.toDouble() / cells > MAX_DENSITY) bad("Density melebihi batas ${MAX_DENSITY * 100}% — papan itu tak bisa dijamin no-guess.")
        if (req.moves.isEmpty() || req.moves.size > cells * MAX_MOVES_PER_CELL) bad("Jumlah langkah di luar batas.")
        if (req.moves.any { it.x !in 0 until req.gridWidth || it.y !in 0 until req.gridHeight }) bad("Ada langkah di luar grid.")
        if (req.elapsedMs < 0) bad("elapsedMs tak boleh negatif.")
    }

    // "Kesulitan ≥ ambang" (ADR-0023) diuji pada DUA sumbu sekaligus: jumlah bom DAN density.
    // Satu sumbu saja bisa diakali — 40 bom di 1000 sel cuma 4% (papan sepi), sedangkan 10 bom di
    // 20 sel padat tapi sepele. Keduanya harus lolos.
    private fun meetsThreshold(req: CasualClaimRequest): Boolean {
        val density = req.mineCount.toDouble() / (req.gridWidth * req.gridHeight)
        return req.mineCount >= minMines && density >= minDensity
    }

    private fun capReached(c: EarnCounts): ClaimResult? = when {
        c.daily >= capDaily -> ClaimResult.CAP_DAILY
        c.weekly >= capWeekly -> ClaimResult.CAP_WEEKLY
        c.monthly >= capMonthly -> ClaimResult.CAP_MONTHLY
        else -> null
    }

    // ── Re-simulasi ──────────────────────────────────────────────────────────────────────────────
    // Papan direproduksi deterministik dari `(config, seed, klik-pertama)` (ADR-0003/0031) lalu log
    // langkah diputar ulang lewat engine yang sama dengan yang dipakai klien. Menang = engine sendiri
    // yang mengatakan `LevelCleared`; tak ada jalan bagi klien untuk mengklaimnya sepihak.
    private fun verifyWin(req: CasualClaimRequest): Verified {
        fun bad(msg: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, msg)
        val first = req.moves.first()
        if (first.action != CasualAction.REVEAL) bad("Langkah pertama harus REVEAL: papan terwujud saat klik pertama.")

        val board = try {
            engine.generate(LevelConfig(req.gridWidth, req.gridHeight, req.mineCount), req.seed, CellIndex(first.x, first.y))
        } catch (e: RuntimeException) {
            // Density mustahil / kena K: papan itu tak pernah bisa di-ship ke pemain mana pun,
            // jadi klaimnya pasti karangan (ADR-0031).
            bad("Papan (config, seed) itu tak bisa dihasilkan: ${e.message}")
        }

        var cleared = false
        var scored = 0 // langkah terskor ADR-0018: reveal/chord yang membuka = 1, flag = 0
        for (m in req.moves) {
            if (cleared) bad("Ada langkah setelah level tuntas.")
            val at = CellIndex(m.x, m.y)
            when (m.action) {
                CasualAction.FLAG -> engine.toggleFlag(board, at)
                CasualAction.REVEAL, CasualAction.CHORD -> {
                    val r = if (m.action == CasualAction.REVEAL) engine.reveal(board, at) else engine.chord(board, at)
                    when (r) {
                        is RevealResult.HitMine -> bad("Replay itu kalah (kena bom), bukan menang.")
                        is RevealResult.Revealed -> if (r.cells.isNotEmpty()) scored++
                        is RevealResult.LevelCleared -> { scored++; cleared = true }
                    }
                }
            }
        }
        if (!cleared) bad("Replay tak menuntaskan level.")
        return Verified(scored, engine.computeParMoves(board))
    }

    // ── Sinyal anomali → AuditEvent (ADR-0023; deteksi sungguhan = T-027) ────────────────────────
    // Menandai, TIDAK memblokir: keduanya bisa dicapai manusia terampil, dan memblokir berarti
    // menghukum pemain jujur. `elapsedMs` datang dari klien (casual boleh offline) → tak tepercaya;
    // ia sinyal, bukan bukti.
    private fun auditAnomalies(userId: Long, req: CasualClaimRequest, v: Verified) {
        val msPerMove = if (v.scored > 0) req.elapsedMs / v.scored else 0
        val signals = buildList {
            if (v.scored <= v.par) add("perfect_path") // menyamai/mengalahkan jalur solver
            if (msPerMove < MIN_MS_PER_MOVE) add("too_fast")
        }
        if (signals.isEmpty()) return

        // Actor SYSTEM (T-027): flag anomali adalah PENGAMATAN server, bukan tindakan pemain —
        // `actor_type='player'` membuat barisnya terbaca "pemain melakukan casual_claim_anomaly"
        // persis di dokumen yang dipakai menangani banding. Pemainnya tetap di `actor_id`.
        audit.record(
            Actor.SYSTEM, userId, "casual_claim_anomaly", "seed:${req.seed}",
            mapOf(
                "signals" to signals, "moves" to v.scored, "par" to v.par,
                "elapsedMs" to req.elapsedMs, "msPerMove" to msPerMove,
                "grid" to "${req.gridWidth}x${req.gridHeight}", "mines" to req.mineCount,
            ),
        )
    }

    private fun respond(userId: Long, result: ClaimResult): CasualClaimResponse {
        val w = lives.wallet(userId)
        return CasualClaimResponse(result, w.free, w.paid)
    }

    private class Verified(val scored: Int, val par: Int)

    private companion object {
        // Pagar keamanan, bukan kebijakan → konstanta, bukan properti. 64×64 = 4096 muat jauh di atas
        // expert klasik 16×30; batas sel yang lebih ketat yang benar-benar mengikat.
        const val MAX_SIDE = 64
        const val MAX_CELLS = 1024
        const val MAX_DENSITY = 0.30
        const val MAX_MOVES_PER_CELL = 4 // flag/unflag berulang → log sah bisa > jumlah sel
        // ponytail: satu ambang datar. Kalibrasi per-grid milik T-027 yang memang punya datanya.
        const val MIN_MS_PER_MOVE = 80
    }
}
