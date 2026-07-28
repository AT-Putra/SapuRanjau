package com.koneksiglobal.sapuranjau.tournament

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.lives.LifeService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Permukaan HTTP pemain untuk turnamen (05 §3). Prefix `/v1` + auth Bearer datang dari `server/api`.
// CRUD periode/hadiah = admin (T-042), sengaja tak ada di sini: endpoint yang bisa mengubah hadiah
// tak boleh terbit sebelum auth admin + RBAC (ADR-0010) ada.
@RestController
class TournamentController(
    private val gate: TournamentGate,
    private val lives: LifeService, // userIdOf: resolusi Firebase UID → app_user (ADR-0030)
    private val jdbc: JdbcClient,
) {

    // GET /v1/tournament/status — LOCKED / BANNED / CONSENT_REQUIRED / OK (ADR-0021/0025/0026).
    // Ini TAMPILAN: klien memilih layar dari sini. Penegakannya di `game` lewat TournamentGate.require.
    @GetMapping("/tournament/status")
    fun status(user: VerifiedUser): StatusResponse = gate.check(lives.userIdOf(user.uid)).toResponse()

    // POST /v1/tournament/consent — gerbang S&K per periode (ADR-0026).
    @PostMapping("/tournament/consent")
    fun consent(user: VerifiedUser, @RequestBody req: ConsentRequest): StatusResponse =
        gate.agree(lives.userIdOf(user.uid), req.tncVersion).toResponse()

    // GET /v1/leaderboard?period=&page=&size= — peringkat + tie-breaker ADR-0009, pagination offset
    // (ADR-0035). Tanpa `period` = periode berjalan. Melihat peringkat tak butuh consent: yang
    // di-gate adalah bermain, bukan menonton.
    @GetMapping("/leaderboard")
    fun leaderboard(
        user: VerifiedUser,
        @RequestParam(required = false) period: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): LeaderboardResponse {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "page ≥ 0 dan size 1..$MAX_PAGE_SIZE.")
        }
        val periodId = if (period == null) {
            gate.activePeriodId()
                ?: throw ApiException(HttpStatus.CONFLICT, ErrorCode.LOCKED, "Tak ada periode turnamen aktif.")
        } else {
            period.toLongOrNull()
                ?: throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "period tak valid.")
        }

        val me = lives.userIdOf(user.uid)
        val offset = page * size
        // Urutan = index `run_leaderboard` (`08` §2.4) supaya tetap index scan tanpa sort. Join ke
        // app_user hanya untuk baris halaman ini (≤ 50), bukan untuk seluruh peserta.
        val rows = jdbc.sql(
            """
            SELECT r.user_id, coalesce(u.display_name, 'Pemain #' || r.user_id) AS name,
                   r.total_score, r.lives_used, r.total_time_ms, r.total_moves
              FROM run r JOIN app_user u ON u.id = r.user_id
             WHERE r.period_id = :pid
             ORDER BY r.total_score DESC, r.lives_used, r.total_time_ms, r.total_moves, r.completed_all_at
             LIMIT :lim OFFSET :off
            """,
        ).param("pid", periodId).param("lim", size).param("off", offset)
            .query { rs, i ->
                LeaderboardEntry(
                    rank = offset + i + 1,
                    name = rs.getString("name"),
                    totalScore = rs.getLong("total_score"),
                    livesUsed = rs.getInt("lives_used"),
                    totalTimeMs = rs.getLong("total_time_ms"),
                    totalMoves = rs.getInt("total_moves"),
                    me = rs.getLong("user_id") == me,
                )
            }.list()

        return LeaderboardResponse(periodId.toString(), page, size, rows)
    }

    // PUT /v1/profile/display-name — nama tampilan leaderboard (ADR-0039). Server tak mengambil nama
    // dari claim Firebase: pemain yang menentukan, klien boleh mengisi awalnya dari akun Google.
    @PutMapping("/profile/display-name")
    fun displayName(user: VerifiedUser, @RequestBody req: DisplayNameRequest): DisplayNameResponse {
        // Trust boundary: nama ini dibaca pemain lain. Whitelist, bukan blacklist — newline & karakter
        // kontrol tak pernah sah di sini.
        val name = req.displayName.trim()
        if (!NAME_PATTERN.matches(name)) {
            throw ApiException(
                HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION,
                "Nama 2–20 karakter, hanya huruf/angka/spasi/titik/garis bawah/apostrof/strip.",
            )
        }
        jdbc.sql("UPDATE app_user SET display_name = ? WHERE id = ?").params(name, lives.userIdOf(user.uid)).update()
        return DisplayNameResponse(name)
    }

    private fun GateStatus.toResponse() =
        StatusResponse(status, periodId?.toString(), tncVersion, banPeriodsLeft)

    private companion object {
        const val MAX_PAGE_SIZE = 50 // pagar DoS, bukan preferensi UI
        val NAME_PATTERN = Regex("^[\\p{L}\\p{N} ._'-]{2,20}$")
    }
}

data class StatusResponse(
    val status: TournamentStatus,
    val periodId: String?,
    val tncVersion: String,
    val banPeriodsLeft: Int?,
)

data class ConsentRequest(val tncVersion: String)

data class LeaderboardResponse(
    val periodId: String,
    val page: Int,
    val size: Int,
    val entries: List<LeaderboardEntry>,
)

// Tanpa id pemain: peringkat + nama sudah cukup untuk dirender, dan id internal tak perlu bocor.
data class LeaderboardEntry(
    val rank: Int,
    val name: String,
    val totalScore: Long,
    val livesUsed: Int,
    val totalTimeMs: Long,
    val totalMoves: Int,
    // Dinamai `me`, bukan `isMe`: awalan `is` membuat Jackson menerbitkan field `me` saat menulis
    // tapi menuntut `isMe` saat membaca — bentuk wire yang tak simetris.
    val me: Boolean,
)

data class DisplayNameRequest(val displayName: String)

data class DisplayNameResponse(val displayName: String)
