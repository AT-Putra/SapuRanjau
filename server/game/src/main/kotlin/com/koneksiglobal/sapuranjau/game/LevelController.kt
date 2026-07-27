package com.koneksiglobal.sapuranjau.game

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Endpoint turnamen level (05 §3, RATIFIED ADR-0033). Prefix `/v1` + auth Bearer datang dari
// `server/api` (ADR-0035) — controller cukup bicara bisnis. Id = string (presisi JS, ADR-0035).
@RestController
@RequestMapping("/tournament/level")
class LevelController(private val game: GameService) {

    // POST /v1/tournament/level/start — mulai/lanjut level berjalan. Balas commit hash + dimensi grid,
    // TANPA peta bom (05 §6). Idempoten: panggil ulang = resume, seed & commit tak berubah.
    @PostMapping("/start")
    fun start(user: VerifiedUser): StartResponse = game.startLevel(user.uid)

    // POST /v1/tournament/level/action — reveal / flag / chord. Server yang mencatat langkah & waktu.
    @PostMapping("/action")
    fun action(user: VerifiedUser, @RequestBody req: ActionRequest): ActionResponse = game.action(user.uid, req)

    // GET /v1/tournament/level/{id}/reveal — ungkap seed (provably-fair, ARCH §6.5). `id` = boardId.
    @GetMapping("/{id}/reveal")
    fun reveal(user: VerifiedUser, @PathVariable id: String): RevealSeedResponse =
        game.revealSeed(user.uid, id.toLongOrNull() ?: -1)
}

// POST /v1/tournament/life/use — kena bom → bakar 1 nyawa (FIFO-expiry ADR-0008); level lanjut di
// tempat dengan bom penyebab mati ter-flag (ADR-0037). Dilayani `game`, bukan `lives`, karena yang
// berubah adalah papan; `lives` hanya menyediakan dompetnya.
@RestController
@RequestMapping("/tournament/life")
class LifeController(private val game: GameService) {

    @PostMapping("/use")
    fun use(user: VerifiedUser, @RequestBody req: UseLifeRequest): UseLifeResponse = game.useLife(user.uid, req)
}

data class CellDto(val x: Int, val y: Int)

data class RevealedCellDto(val x: Int, val y: Int, val adjacentMines: Int)

data class StartResponse(
    val runId: String,
    val boardId: String,
    val levelIndex: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val mineCount: Int,
    val commitHash: String,
    val revealed: List<RevealedCellDto>, // terisi saat resume (ARCH §12), kosong utk level baru
    val flags: List<CellDto>,
    val movesCount: Int,
    val awaitingLife: Boolean, // kena bom & belum pakai nyawa (ARCH §6.3; pemakaian nyawa = T-023)
)

data class ActionRequest(
    val runId: String,
    val levelIndex: Int,
    val action: MoveAction,
    val cell: CellDto,
)

// Ekstensi non-breaking atas contoh 05 §3: NO_OP/FLAGGED/UNFLAGGED memberi klien konfirmasi eksplisit
// bahwa aksi tak mengubah papan (mis. flag di sel terbuka) — tanpa menebak dari `cells` kosong.
enum class ActionResult { REVEALED, FLAGGED, UNFLAGGED, HIT_MINE, LEVEL_CLEARED, NO_OP }

enum class LevelStatus { CONTINUE, HIT_MINE, LEVEL_CLEARED }

data class ActionResponse(
    val result: ActionResult,
    val cells: List<RevealedCellDto>,
    val status: LevelStatus,
    val movesCount: Int, // langkah terskor (ADR-0018)
    val score: Int? = null, // terisi hanya saat LEVEL_CLEARED
)

data class UseLifeRequest(val runId: String, val levelIndex: Int)

// `lifeCap` ikut dikirim supaya klien bisa memperingatkan "level ini sudah 0 skor" SEBELUM pemain
// membakar nyawa berikutnya (livesUsed >= lifeCap → penalti 0, ADR-0017/0037).
data class UseLifeResponse(
    val livesUsed: Int,
    val lifeCap: Int,
    val freeLives: Int,
    val paidLives: Int,
)

data class RevealSeedResponse(
    val boardId: String,
    val seed: String,
    val commitHash: String,
    val algorithm: String,
)
