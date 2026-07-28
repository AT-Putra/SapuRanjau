package com.koneksiglobal.sapuranjau.game

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.LevelAnomalyDetector
import com.koneksiglobal.sapuranjau.audit.LevelFacts
import com.koneksiglobal.sapuranjau.engine.Board
import com.koneksiglobal.sapuranjau.engine.CellIndex
import com.koneksiglobal.sapuranjau.engine.LevelConfig
import com.koneksiglobal.sapuranjau.engine.MinesweeperEngine
import com.koneksiglobal.sapuranjau.engine.RevealResult
import com.koneksiglobal.sapuranjau.engine.RevealedCell
import com.koneksiglobal.sapuranjau.integrity.IntegrityGate
import com.koneksiglobal.sapuranjau.lives.LifeService
import com.koneksiglobal.sapuranjau.scoring.LevelPar
import com.koneksiglobal.sapuranjau.scoring.LevelPlay
import com.koneksiglobal.sapuranjau.scoring.LevelScoring
import com.koneksiglobal.sapuranjau.scoring.ScoringParams
import com.koneksiglobal.sapuranjau.tournament.TournamentGate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Collections

// Orkestrasi run turnamen one-shot (T-022, ADR-0024) — server-authoritative (ARCH §6).
// Kebenaran state = DB: `board.moves` (log langkah) + seed. Objek `Board` termaterialisasi hidup di
// cache sesi = MEMO murni (ADR-0036): boleh hilang kapan saja → generate + replay. Peta bom tak
// pernah keluar dari sini (05 §6).
@Service
class GameService(
    private val users: AppUserRepo,
    private val periods: PeriodRepo,
    private val levels: LevelConfigRepo,
    private val runs: RunRepo,
    private val boards: BoardRepo,
    private val scores: LevelScoreRepo,
    private val lives: LifeService, // T-023: konsumsi FIFO-expiry; `game` → `lives`, tak sebaliknya
    private val gate: TournamentGate, // T-026: periode terkunci / ban / S&K (ADR-0021/0025/0026)
    private val anomali: LevelAnomalyDetector, // T-027: flag bot saat level ditutup
    private val integrity: IntegrityGate, // T-028: gerbang device Play Integrity (ADR-0041)
    private val jdbc: JdbcClient,
    // parTimeMs = parMoves × konstanta (05 §2). Tunable (ADR-0017/0036); pindah ke admin-config saat kalibrasi.
    @Value("\${sapuranjau.scoring.ms-per-par-move:2000}") private val msPerParMove: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val engine = MinesweeperEngine()
    private val scoring = LevelScoring()
    private val random = SecureRandom()

    // ponytail: LRU 5k sesi in-process. Memo murni → kehilangan = biaya regenerate, bukan data hilang.
    // Multi-instance/Redis = ADR masa depan (ADR-0036), bukan sekarang (deploy = satu app-VM, ADR-0015).
    private val sessions: MutableMap<Long, LevelSession> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, LevelSession>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, LevelSession>) = size > 5_000
        },
    )

    // ponytail: 64 lock bergaris (boardId % 64) — cegah dua aksi paralel pada board yang sama saling
    // merusak state mutable Board. Ganti ke lock per-board kalau kontensi pernah terukur.
    private val stripes = Array(64) { Any() }

    private fun <T> onBoard(boardId: Long, body: () -> T): T =
        synchronized(stripes[(boardId % stripes.size).toInt()], body)

    // ── Endpoint: mulai/lanjut level (05 §3, ARCH §6.1) ──────────────────────────────────────────
    // Board TIDAK digenerate di sini: server cuma commit seed (kirim hash). Papan terwujud saat
    // REVEAL pertama (first-click-safe, ADR-0031). Panggilan ulang = resume level yang sama.
    @Transactional
    fun startLevel(uid: String): StartResponse {
        val user = users.findByFirebaseUid(uid) ?: users.save(AppUserRow(firebaseUid = uid))
        // Gerbang turnamen (T-026): periode terkunci (ADR-0021), ban refund (ADR-0025), S&K belum
        // disetujui (ADR-0026) ditolak DI SINI. `GET /tournament/status` cuma menampilkan; kalau
        // penegakannya cuma di sana, klien yang tak bertanya bisa main tanpa menyetujui apa pun.
        val periodId = gate.require(user.id!!)
        integrity.require(user.id) // gerbang device (T-028, ADR-0041) — hanya di titik masuk
        val run = runs.findByPeriodIdAndUserId(periodId, user.id)
            ?: runs.save(RunRow(userId = user.id, periodId = periodId))
        if (run.scoreLocked) throw ApiException(HttpStatus.FORBIDDEN, ErrorCode.CONFLICT, "Skor run ini terkunci.")

        val cfg = levels.findByPeriodIdAndLevelIndex(periodId, run.currentLevel)
            ?: throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Semua level periode ini sudah tuntas.")
        check(cfg.gridWidth - 1 <= MoveCodec.MAX_X && cfg.gridHeight - 1 <= MoveCodec.MAX_Y) {
            "level_config ${cfg.id}: grid ${cfg.gridWidth}x${cfg.gridHeight} melebihi jangkauan log langkah"
        }

        val existing = boards.findByRunIdAndLevelConfigId(run.id!!, cfg.id!!)
        val row = when {
            existing == null -> {
                val seed = random.nextLong()
                boards.save(BoardRow(runId = run.id, levelConfigId = cfg.id, seed = seed, commitHash = commitOf(seed)))
            }
            existing.status == "active" -> existing
            // One-shot (ADR-0024): level bersih sekali, tak ada seed baru untuk level yang sudah selesai.
            else -> throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Level ini sudah selesai (one-shot).")
        }

        val s = onBoard(row.id!!) { sessionOf(row, cfg) }
        return StartResponse(
            runId = run.id.toString(),
            boardId = row.id.toString(),
            levelIndex = cfg.levelIndex,
            gridWidth = cfg.gridWidth,
            gridHeight = cfg.gridHeight,
            mineCount = cfg.mineCount,
            commitHash = row.commitHash,
            // Resume (ARCH §12): state yang sudah terbuka dikirim ulang; board baru → kosong.
            revealed = s.revealed.map { (c, n) -> RevealedCellDto(c.x, c.y, n) },
            flags = s.flags.map { CellDto(it.x, it.y) },
            movesCount = s.scoredMoves,
            awaitingLife = s.dead,
        )
    }

    // ── Endpoint: reveal / flag / chord (05 §3, ARCH §6.2) ───────────────────────────────────────
    @Transactional
    fun action(uid: String, req: ActionRequest): ActionResponse {
        if (req.action == MoveAction.USE_LIFE) {
            // USE_LIFE hidup di log langkah (ADR-0037), bukan di alfabet aksi klien: memakainya di
            // sini akan melewati pemotongan nyawa. Jalurnya `/tournament/life/use`.
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Pakai POST /tournament/life/use untuk memakai nyawa.")
        }
        val (_, run, cfg, row) = locate(uid, req.runId, req.levelIndex)
        if (req.cell.x !in 0 until cfg.gridWidth || req.cell.y !in 0 until cfg.gridHeight) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Sel di luar grid ${cfg.gridWidth}x${cfg.gridHeight}.")
        }

        return onBoard(row.id!!) {
            val s = sessionOf(row, cfg)
            if (s.dead) throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Level menunggu pemakaian nyawa.")

            val now = System.currentTimeMillis()
            s.activeMs += now - s.lastTick // waktu aktif = jeda antar-aksi dalam satu sesi (ADR-0036)
            s.lastTick = now

            val prevLogBytes = s.moves.size * 2
            val out = apply(s, Move(req.action, CellIndex(req.cell.x, req.cell.y)))
            if (!out.record) {
                // No-op (sel berflag, chord belum tuntas/ tanpa target): bukan langkah (ADR-0018),
                // tak masuk log → tak ada yang perlu ditulis.
                return@onBoard ActionResponse(ActionResult.NO_OP, emptyList(), LevelStatus.CONTINUE, s.scoredMoves)
            }
            s.moves += out.move
            s.scoredMoves += out.scored

            val score = if (out.cleared) finalizeLevel(run, cfg, s) else null
            persist(s, prevLogBytes, if (out.cleared) "cleared" else "active")
            if (out.cleared) sessions.remove(s.boardId)

            ActionResponse(
                result = out.result,
                cells = out.cells.map { RevealedCellDto(it.index.x, it.index.y, it.adjacentMines) },
                status = when {
                    out.cleared -> LevelStatus.LEVEL_CLEARED
                    out.dead -> LevelStatus.HIT_MINE
                    else -> LevelStatus.CONTINUE
                },
                movesCount = s.scoredMoves,
                score = score,
            )
        }
    }

    // ── Endpoint: pakai nyawa setelah kena bom (05 §3, ARCH §6.3, ADR-0037) ──────────────────────
    // Level LANJUT DI TEMPAT: bom yang membunuh di-auto-flag (flag = "tidak membuka" di engine → sel
    // itu kebal reveal/cascade/chord, dan hitungan chord tetap benar karena flagnya memang benar).
    // Bukan rewind (sel tersembunyi lagi = pemain bisa mati di bom yang sama berulang kali) dan bukan
    // restart level (melanggar one-shot ADR-0024).
    @Transactional
    fun useLife(uid: String, req: UseLifeRequest): UseLifeResponse {
        val (user, run, cfg, row) = locate(uid, req.runId, req.levelIndex)
        if (run.scoreLocked) throw ApiException(HttpStatus.FORBIDDEN, ErrorCode.CONFLICT, "Skor run ini terkunci.")
        gate.require(user.id!!) // membeli/memakai nyawa = titik masuk turnamen juga (T-026)
        integrity.require(user.id) // T-028

        return onBoard(row.id!!) {
            val s = sessionOf(row, cfg)
            if (!s.dead) throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Level ini tidak sedang menunggu nyawa.")
            val at = checkNotNull(s.deadAt) { "board ${s.boardId}: dead tanpa deadAt" }

            // Konsumsi & papan ditulis dalam SATU transaksi: kalau optimistic lock di persist() kalah,
            // nyawanya ikut kembali. Nyawa boleh dipakai walau skor level sudah 0 (livesUsed >=
            // lifeCap) — menolaknya mengunci run selamanya di level ini (ADR-0037).
            if (!lives.consumeOne(user.id!!, run.id!!)) {
                throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Nyawa habis. Beli atau menangkan di mode casual.")
            }

            val prevLogBytes = s.moves.size * 2
            val m = Move(MoveAction.USE_LIFE, at)
            try {
                apply(s, m)
                s.moves += m
                s.lastTick = System.currentTimeMillis() // jeda mencari nyawa bukan waktu bermain
                runs.save(run.copy(livesUsed = run.livesUsed + 1, updatedAt = Instant.now())) // tie-breaker ADR-0009
                persist(s, prevLogBytes, "active")
            } catch (e: Exception) {
                sessions.remove(s.boardId) // sesi = memo di luar transaksi: jangan tertinggal lebih maju dari DB
                throw e
            }

            val w = lives.wallet(user.id)
            UseLifeResponse(s.livesUsed, cfg.lifeCap, w.free, w.paid)
        }
    }

    // ── Endpoint: reveal seed (provably-fair, ARCH §6.5) ─────────────────────────────────────────
    // Seed baru boleh diungkap saat papan tak lagi dimainkan (level selesai) atau periode berakhir —
    // sebelum itu seed = peta bom (05 §6).
    fun revealSeed(uid: String, boardId: Long): RevealSeedResponse {
        val user = users.findByFirebaseUid(uid) ?: throw notFound("Board tak ditemukan.")
        val row = boards.findById(boardId).orElse(null) ?: throw notFound("Board tak ditemukan.")
        val run = runs.findById(row.runId).orElse(null)?.takeIf { it.userId == user.id } ?: throw notFound("Board tak ditemukan.")
        val period = periods.findById(run.periodId).orElse(null) ?: throw notFound("Board tak ditemukan.")
        if (row.status == "active" && period.status != "ENDED") {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Seed diungkap setelah level selesai atau periode berakhir.")
        }
        return RevealSeedResponse(boardId.toString(), row.seed.toString(), row.commitHash, COMMIT_ALGORITHM)
    }

    // ── Internal ─────────────────────────────────────────────────────────────────────────────────

    private fun notFound(msg: String) = ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, msg)

    // Resolusi (pemain, run, level, board) + cek kepemilikan — sama untuk aksi & pakai nyawa.
    private fun locate(uid: String, runIdRaw: String, levelIndex: Int): Located {
        val user = users.findByFirebaseUid(uid) ?: throw notFound("Run tak ditemukan.")
        val runId = runIdRaw.toLongOrNull() ?: throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "runId tak valid.")
        val run = runs.findById(runId).orElse(null)?.takeIf { it.userId == user.id } ?: throw notFound("Run tak ditemukan.")
        val cfg = levels.findByPeriodIdAndLevelIndex(run.periodId, levelIndex) ?: throw notFound("Level tak ditemukan.")
        val row = boards.findByRunIdAndLevelConfigId(run.id!!, cfg.id!!) ?: throw notFound("Level belum dimulai.")
        if (row.status != "active") throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Level ini sudah selesai (one-shot).")
        return Located(user, run, cfg, row)
    }

    private fun sessionOf(row: BoardRow, cfg: LevelConfigRow): LevelSession =
        sessions[row.id!!] ?: replay(row, cfg).also { sessions[row.id] = it }

    // Rekonstruksi state dari DB (ADR-0036): generate ulang board dari seed + klik-pertama, lalu
    // putar ulang log langkah. Angka terskor/waktu diambil dari kolom (ditulis satu transaksi dg log).
    private fun replay(row: BoardRow, cfg: LevelConfigRow): LevelSession {
        val s = LevelSession(row.id!!, row.seed, cfg.toEngineConfig())
        // `dead`/`deadAt`/`livesUsed` tak perlu diset di sini: apply() sendiri yang memutarnya.
        for (m in MoveCodec.decode(row.moves)) {
            apply(s, m)
            s.moves += m
        }
        s.scoredMoves = row.movesCount
        s.activeMs = row.activeTimeMs
        s.lastTick = System.currentTimeMillis()
        return s
    }

    // Satu-satunya transisi state — dipakai aksi live DAN replay, jadi keduanya tak mungkin berbeda.
    private fun apply(s: LevelSession, m: Move): Outcome {
        if (s.board == null && m.action != MoveAction.REVEAL) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Aksi pertama harus REVEAL: papan baru terwujud saat klik pertama.")
        }
        val board = s.board ?: engine.generate(s.config, s.seed, m.cell).also { s.board = it }
        return when (m.action) {
            MoveAction.FLAG -> {
                if (m.cell in s.revealed) return Outcome(m, ActionResult.NO_OP, record = false) // cermin engine
                engine.toggleFlag(board, m.cell)
                val on = if (s.flags.add(m.cell)) true else { s.flags.remove(m.cell); false }
                Outcome(m, if (on) ActionResult.FLAGGED else ActionResult.UNFLAGGED, scored = 0) // flag gratis (ADR-0018)
            }
            // Nyawa (ADR-0037): bom penyebab mati ditandai flag → tak bisa membunuh lagi, dan pemain
            // memang berhak tahu letaknya (dia sudah membayar). Flag = 0 langkah (ADR-0018); penalti
            // skornya lewat livesUsed (ADR-0017). `dead` dimiliki apply() (satu-satunya transisi
            // state, dipakai aksi live & replay) → resume tak butuh cabang khusus.
            MoveAction.USE_LIFE -> {
                if (s.flags.add(m.cell)) engine.toggleFlag(board, m.cell)
                s.dead = false
                s.deadAt = null
                s.livesUsed++
                Outcome(m, ActionResult.FLAGGED, scored = 0)
            }
            MoveAction.REVEAL, MoveAction.CHORD -> {
                val r = if (m.action == MoveAction.REVEAL) engine.reveal(board, m.cell) else engine.chord(board, m.cell)
                when (r) {
                    // `r.at` = bom yang MELEDAK; utk chord itu bukan sel yang di-tap (m.cell) — sel
                    // inilah yang di-flag saat nyawa dipakai (ADR-0037).
                    is RevealResult.HitMine -> {
                        s.dead = true
                        s.deadAt = r.at
                        Outcome(m, ActionResult.HIT_MINE, dead = true)
                    }
                    is RevealResult.Revealed ->
                        if (r.cells.isEmpty()) Outcome(m, ActionResult.NO_OP, record = false) // tak ada yang terbuka = bukan langkah
                        else Outcome(m, ActionResult.REVEALED, cells = s.absorb(r.cells))
                    is RevealResult.LevelCleared -> Outcome(m, ActionResult.LEVEL_CLEARED, cells = s.absorb(r.cells), cleared = true)
                }
            }
        }
    }

    // Skor level final (ADR-0017) + agregat run (sumber leaderboard). Satu transaksi dengan persist().
    private fun finalizeLevel(run: RunRow, cfg: LevelConfigRow, s: LevelSession): Int {
        // Par = jalur bersih solver PER-PAPAN; computeParMoves memakai salinan segar → aman walau dimainkan.
        val parMoves = engine.computeParMoves(s.board!!)
        val par = LevelPar(parMoves, parMoves * msPerParMove)
        val play = LevelPlay(moves = s.scoredMoves, activeTimeMs = s.activeMs, livesUsed = s.livesUsed)
        val score = scoring.levelScore(par, play, ScoringParams(lifeCap = cfg.lifeCap, baseScore = cfg.baseScore))

        scores.save(
            LevelScoreRow(
                runId = run.id!!, levelConfigId = cfg.id!!, moves = MoveCodec.encode(s.moves),
                movesCount = s.scoredMoves, parMoves = parMoves, activeTimeMs = s.activeMs,
                livesUsed = s.livesUsed, score = score,
            ),
        )
        val next = run.currentLevel + 1
        val allDone = next >= levels.countByPeriodId(run.periodId)

        // Corong SATU-SATUNYA tempat skor masuk ke agregat `run` (= sumber leaderboard) → syaratnya
        // dipasang di sini, bukan di tiap endpoint. Menutup dua kebocoran sekaligus (T-026):
        //   • void/refund di tengah level → `score_locked` sudah true (ADR-0025), skornya tak boleh naik lagi;
        //   • periode berakhir di tengah level → pemenang sudah ditentukan, agregatnya tak boleh bergerak.
        // `level_score` tetap ditulis di atas: itu fakta permainan & bahan re-sim, bukan hadiah.
        val doneClause = if (allDone) "completed_all_at = coalesce(completed_all_at, now()), " else ""
        val credited = jdbc.sql(
            "UPDATE run SET current_level = ?, total_score = total_score + ?, total_time_ms = total_time_ms + ?, " +
                "total_moves = total_moves + ?, ${doneClause}updated_at = now() " +
                "WHERE id = ? AND score_locked = false " +
                "AND EXISTS (SELECT 1 FROM period WHERE id = ? AND status = 'ACTIVE')",
        ).params(next, score.toLong(), s.activeMs, s.scoredMoves, run.id, run.periodId).update()
        if (credited == 0) log.info("Run ${run.id}: level selesai tapi tak dikreditkan (terkunci / periode tak aktif)")

        // Sinyal anomali bot (T-027) — dari angka yang sudah dihitung di atas, tanpa query tambahan
        // di jalur selesai-level. MENANDAI, tak memblokir (ARCH §9, ADR-0037).
        anomali.inspectLevel(
            userId = run.userId, runId = run.id!!, levelIndex = cfg.levelIndex,
            play = LevelFacts(s.scoredMoves, parMoves, s.activeMs, s.livesUsed, cfg.lifeCap),
        )

        return score
    }

    // Optimistic lock tanpa kolom versi (ADR-0036): panjang log yang tersimpan = token. Dua aksi
    // paralel yang lolos cache → yang kalah dapat 0 baris → sesi dibuang, klien retry.
    private fun persist(s: LevelSession, prevLogBytes: Int, status: String) {
        val updated = jdbc.sql(
            "UPDATE board SET moves = ?, moves_count = ?, active_time_ms = ?, status = ?, updated_at = now() " +
                "WHERE id = ? AND octet_length(moves) = ?",
        ).params(MoveCodec.encode(s.moves), s.scoredMoves, s.activeMs, status, s.boardId, prevLogBytes).update()

        if (updated == 0) {
            sessions.remove(s.boardId)
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Aksi bertabrakan dengan aksi lain. Muat ulang level.")
        }
    }

    // Dipakai test durabilitas ADR-0036: paksa state dibangun ulang dari DB (setara restart server).
    internal fun evictAllSessions() = sessions.clear()

    private companion object {
        const val COMMIT_ALGORITHM = "SHA-256(seed big-endian 8 byte)"
    }

    // commit-reveal (ARCH §6.1/§6.5): hash diterbitkan di start, seed diungkap di akhir → pemain
    // bisa buktikan papan tak diubah di tengah jalan.
    private fun commitOf(seed: Long): String {
        val bytes = ByteArray(8) { i -> (seed ushr (56 - 8 * i)).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

// Sesi = memo atas (seed, log langkah) — ADR-0036. `revealed`/`flags` adalah PROYEKSI untuk klien;
// state asli tetap milik engine. Keduanya digerakkan log yang sama → tak bisa berbeda.
internal class LevelSession(val boardId: Long, val seed: Long, val config: LevelConfig) {
    var board: Board? = null // terwujud saat REVEAL pertama (ADR-0031)
    val moves = ArrayList<Move>()
    val revealed = LinkedHashMap<CellIndex, Int>()
    val flags = HashSet<CellIndex>()
    var scoredMoves = 0 // ADR-0018: reveal/chord yang membuka = 1; flag = 0
    var activeMs = 0L
    var lastTick = System.currentTimeMillis()
    var dead = false // kena bom, menunggu nyawa (ADR-0037)
    var deadAt: CellIndex? = null // bom yang meledak (utk chord ≠ sel yang di-tap) → di-flag saat nyawa dipakai
    var livesUsed = 0 // dihitung ulang dari log saat replay (ADR-0037) → penalti skor ADR-0017

    fun absorb(cells: List<RevealedCell>): List<RevealedCell> {
        cells.forEach { revealed[it.index] = it.adjacentMines }
        return cells
    }
}

// Hasil `locate()` — destructuring di pemanggil (`val (user, run, cfg, row) = ...`).
private data class Located(val user: AppUserRow, val run: RunRow, val cfg: LevelConfigRow, val row: BoardRow)

private class Outcome(
    val move: Move,
    val result: ActionResult,
    val cells: List<RevealedCell> = emptyList(),
    val scored: Int = 1,
    val record: Boolean = true,
    val dead: Boolean = false,
    val cleared: Boolean = false,
)

// LevelConfig engine = proyeksi geometry dari baris DB (ADR-0032); base_score/life_cap → ScoringParams.
private fun LevelConfigRow.toEngineConfig() = LevelConfig(gridWidth, gridHeight, mineCount)
