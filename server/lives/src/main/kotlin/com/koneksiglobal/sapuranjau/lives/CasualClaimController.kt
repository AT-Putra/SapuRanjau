package com.koneksiglobal.sapuranjau.lives

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// POST /v1/casual/claim (docs/05 §3) — menang casual ≥ ambang → 1 FreeLife (ADR-0023).
// Klaim wajib online meski casual-nya boleh offline: nyawa hanya dicetak server.
@RestController
@RequestMapping("/casual")
class CasualClaimController(private val claims: CasualClaimService) {

    @PostMapping("/claim")
    fun claim(user: VerifiedUser, @RequestBody req: CasualClaimRequest): CasualClaimResponse =
        claims.claim(user.uid, req)
}

// Alfabet aksi klaim casual. Sengaja TERPISAH dari `MoveAction` milik `server/game`: modul `game`
// sudah bergantung pada `lives`, jadi memakainya di sini akan membuat siklus — dan menaikkannya ke
// `engine-core` berarti mengubah kontrak yang sudah RATIFIED (ADR-0032) demi enum tiga nilai.
enum class CasualAction { REVEAL, FLAG, CHORD }

data class CasualMove(val action: CasualAction, val x: Int, val y: Int)

// `(seed, moves)` + geometri papan = semua yang dibutuhkan server untuk mereproduksi papan itu
// persis (ADR-0003 deterministik) dan memutar ulang permainannya. `elapsedMs` = laporan klien,
// dipakai HANYA sebagai sinyal anomali (tak tepercaya — casual boleh dimainkan offline).
data class CasualClaimRequest(
    val gridWidth: Int,
    val gridHeight: Int,
    val mineCount: Int,
    val seed: Long,
    val moves: List<CasualMove>,
    val elapsedMs: Long,
)

// Semua hasil yang WAJAR dikembalikan 200 dengan `result` — cap tercapai & papan terlalu mudah itu
// jawaban normal, bukan kegagalan. 4xx disisakan untuk klaim yang memang cacat (replay tak menang,
// input di luar batas).
enum class ClaimResult { GRANTED, CAP_DAILY, CAP_WEEKLY, CAP_MONTHLY, BELOW_THRESHOLD, NO_ACTIVE_PERIOD }

data class CasualClaimResponse(val result: ClaimResult, val free: Int, val paid: Int)
