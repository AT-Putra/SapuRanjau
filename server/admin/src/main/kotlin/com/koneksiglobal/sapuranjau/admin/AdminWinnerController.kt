package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import com.koneksiglobal.sapuranjau.tournament.PiiCipher
import com.koneksiglobal.sapuranjau.tournament.WinnerService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4
import java.math.BigDecimal
import java.time.Instant

// Pemenang & klaim hadiah dari panel (T-042, ADR-0021). Ini layar yang membuat inbox dan klaim
// pemain berhenti kosong: `message.admin_id NOT NULL` berarti tak ada satu pun pesan yang bisa lahir
// sebelum halaman ini ada.
//
// Pembagian peran (ARCH §10, ADR-0020 §PII): `moderator` boleh melihat daftar, menggugurkan, dan
// mengirim pesan; **PII klaim hanya `admin` & `finance`**, dan tiap pembacaannya menulis audit —
// nomor HP/e-wallet/alamat pemenang adalah alasan tabelnya dienkripsi sejak awal (T-029).
@RestController
class AdminWinnerController(
    private val jdbc: JdbcClient,
    private val winners: WinnerService,
    private val pii: PiiCipher,
    private val ra: RaQuery,
    private val audit: AuditService,
    private val json: ObjectMapper,
) {

    data class WinnerDto(
        val id: String,
        val periodId: String,
        val periodName: String?,
        val userId: String,
        val displayName: String,
        val rank: Int,
        val status: String,
        val disqualifyReason: String?,
        val totalScore: Long,
        val prize: String?, // hadiah untuk peringkat ini, dari prize_config (ADR-0021)
        val claimStatus: String?, // null = pemenang belum mengisi form klaim
        val createdAt: Instant,
    )

    data class ReasonRequest(val reason: String = "")
    data class MessageRequest(val body: String = "")
    data class ClaimStatusRequest(val status: String = "", val prizeValue: BigDecimal? = null)

    // PII didekripsi hanya di sini, hanya untuk peran yang berhak, dan tak pernah masuk daftar:
    // list pemenang dipakai sehari-hari, dan PII yang ikut di setiap barisnya akan tersebar ke
    // cache browser, ekspor CSV, dan screenshot rapat.
    data class ClaimPiiDto(
        val winnerId: String,
        val status: String,
        val phone: String,
        val ewallet: String?,
        val address: String?,
        val prizeValue: BigDecimal?,
        val paidAt: Instant?,
    )

    @GetMapping("/winners")
    fun list(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<WinnerDto>> {
        val h = ra.page(range)
        val s = ra.sort(sort, SORT, default = "rank")
        val periodId = ra.filter(filter)["periodId"]?.toLongOrNull()
        val where = if (periodId == null) "" else "WHERE w.period_id = $periodId"
        // `w.` wajib: `id`/`status`/`created_at` ada di lebih dari satu tabel yang di-join.
        val isi = jdbc.sql("$SELECT $where ORDER BY w.${s.column} ${s.arah} OFFSET ? LIMIT ?")
            .params(h.offset, h.limit).query(::dto).list()
        val total = if (periodId == null) {
            jdbc.sql("SELECT count(*) FROM winner").query(Long::class.java).single()
        } else {
            jdbc.sql("SELECT count(*) FROM winner WHERE period_id = ?").param(periodId).query(Long::class.java).single()
        }
        return ResponseEntity.ok().header("Content-Range", h.contentRange("winners", isi.size, total)).body(isi)
    }

    @GetMapping("/winners/{id}")
    fun one(@PathVariable id: Long): WinnerDto =
        jdbc.sql("$SELECT WHERE w.id = ?").param(id).query(::dto).optional().orElseThrow { tidakAda(id) }

    // Gugurkan: alasan WAJIB (ADR-0021). Aturan promosi peringkat ada di WinnerService — controller
    // ini cuma menyampaikan siapa yang menekan tombolnya supaya jejaknya tak berbunyi "system".
    @PostMapping("/winners/{id}/disqualify")
    fun disqualify(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: ReasonRequest): WinnerDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        winners.disqualify(id, body.reason.trim(), principal.id)
        return one(id)
    }

    // Kirim pesan inbox ke pemenang (ADR-0021: pemberitahuan diketik admin, bukan template sistem).
    @PostMapping("/winners/{id}/message")
    fun message(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: MessageRequest): WinnerDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        val isi = body.body.trim()
        if (isi.isBlank()) bad("Isi pesan tak boleh kosong.")
        if (isi.length > MAX_PESAN) bad("Pesan maksimal $MAX_PESAN karakter.")
        val userId = jdbc.sql("SELECT user_id FROM winner WHERE id = ?").param(id)
            .query(Long::class.java).optional().orElseThrow { tidakAda(id) }
        val messageId = jdbc.sql("INSERT INTO message (user_id, admin_id, body) VALUES (?, ?, ?) RETURNING id")
            .params(userId, principal.id, isi).query(Long::class.java).single()
        // Isi pesan TIDAK ikut ke audit: ia sudah tersimpan utuh di `message`, dan menyalinnya ke
        // tabel append-only berarti pesan yang salah kirim tak bisa dihapus dari mana pun.
        audit.record(Actor.ADMIN, principal.id, "admin_message_sent", "user:$userId", mapOf("messageId" to messageId, "winnerId" to id))
        return one(id)
    }

    @GetMapping("/winners/{id}/claim")
    fun claim(principal: AdminPrincipal, @PathVariable id: Long): ClaimPiiDto {
        principal.require(AdminRole.ADMIN, AdminRole.FINANCE)
        val dto = jdbc.sql(
            "SELECT winner_id, status, phone_enc, ewallet_enc, address_enc, prize_value, paid_at FROM prize_claim WHERE winner_id = ?",
        ).param(id).query { rs, _ ->
            ClaimPiiDto(
                rs.getString("winner_id"), rs.getString("status"),
                pii.decrypt(rs.getBytes("phone_enc")),
                rs.getBytes("ewallet_enc")?.let(pii::decrypt),
                rs.getBytes("address_enc")?.let(pii::decrypt),
                rs.getBigDecimal("prize_value"), rs.getTimestamp("paid_at")?.toInstant(),
            )
        }.optional().orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Pemenang ini belum mengisi form klaim.")
        }
        // ADR-0020: tiap pembacaan PII berjejak. Yang dicatat NAMA FIELD-nya saja, tak pernah isinya
        // (pola T-029) — kalau tidak, `audit_event` yang append-only berubah jadi salinan kedua PII
        // tanpa enkripsi, dan salinan itu tak bisa dihapus.
        audit.record(
            Actor.ADMIN, principal.id, "prize_claim_pii_read", "winner:$id",
            mapOf("fields" to listOfNotNull("phone", dto.ewallet?.let { "ewallet" }, dto.address?.let { "address" })),
        )
        return dto
    }

    // Verifikasi manual (telepon, ADR-0030) lalu tandai lunas setelah transfer dilakukan di luar
    // sistem (ADR-0021: tak ada payout otomatis).
    @PostMapping("/winners/{id}/claim/status")
    fun claimStatus(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: ClaimStatusRequest): WinnerDto {
        principal.require(AdminRole.ADMIN, AdminRole.FINANCE)
        val status = body.status.trim().lowercase()
        if (status !in setOf("verified", "paid")) bad("Status hanya boleh 'verified' atau 'paid'.")
        val ada = jdbc.sql("SELECT 1 FROM prize_claim WHERE winner_id = ?").param(id).query(Int::class.java).optional().isPresent
        if (!ada) throw ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Pemenang ini belum mengisi form klaim.")

        // `verified_by`/`paid_by` diisi sesuai langkahnya; `paid_at` hanya saat lunas. Nilai hadiah
        // dicatat untuk jejak PPh (`08` §2.11) — angka, bukan PII, jadi ia boleh ikut audit.
        if (status == "verified") {
            jdbc.sql("UPDATE prize_claim SET status = 'verified', verified_by = ?, prize_value = coalesce(?, prize_value) WHERE winner_id = ?")
                .params(listOf(principal.id, body.prizeValue, id)).update()
        } else {
            jdbc.sql(
                "UPDATE prize_claim SET status = 'paid', paid_by = ?, paid_at = now(), prize_value = coalesce(?, prize_value) WHERE winner_id = ?",
            ).params(listOf(principal.id, body.prizeValue, id)).update()
        }
        audit.record(Actor.ADMIN, principal.id, "prize_claim_$status", "winner:$id", mapOf("prizeValue" to body.prizeValue?.toPlainString()))
        return one(id)
    }

    private fun dto(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): WinnerDto {
        val rank = rs.getInt("rank")
        val daftar = rs.getString("prizes")?.let {
            runCatching { json.readValue(it, Array<String>::class.java) }.getOrNull()
        }
        return WinnerDto(
            id = rs.getString("id"),
            periodId = rs.getString("period_id"),
            periodName = rs.getString("period_name"),
            userId = rs.getString("user_id"),
            // Pemain boleh tak pernah menyetel nama (ADR-0039) — fallback yang sama dengan sisi klien.
            displayName = rs.getString("display_name") ?: "Pemain #${rs.getString("user_id")}",
            rank = rank,
            status = rs.getString("status"),
            disqualifyReason = rs.getString("disqualify_reason"),
            totalScore = rs.getLong("total_score"),
            prize = daftar?.getOrNull(rank - 1),
            claimStatus = rs.getString("claim_status"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private fun bad(pesan: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, pesan)

    private fun tidakAda(id: Long) = ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Pemenang $id tak ada.")

    private companion object {
        val SORT = setOf("id", "rank", "status", "created_at")
        const val MAX_PESAN = 2000

        // Skor diambil dari `run` periode yang sama (sumber leaderboard, `08` §2.4) dan hadiah dari
        // `prize_config` — dua-duanya yang ditanya admin saat menelepon pemenang.
        const val SELECT = """
            SELECT w.id, w.period_id, w.user_id, w.rank, w.status, w.disqualify_reason, w.created_at,
                   p.name AS period_name, u.display_name,
                   coalesce(r.total_score, 0) AS total_score,
                   c.prizes::text AS prizes,
                   cl.status AS claim_status
              FROM winner w
              JOIN period p ON p.id = w.period_id
              JOIN app_user u ON u.id = w.user_id
              LEFT JOIN run r ON r.period_id = w.period_id AND r.user_id = w.user_id
              LEFT JOIN prize_config c ON c.period_id = w.period_id
              LEFT JOIN prize_claim cl ON cl.winner_id = w.id
        """
    }
}
