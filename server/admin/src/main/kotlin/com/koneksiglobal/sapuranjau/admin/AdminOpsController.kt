package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// Layar "kejadian": sanksi ban, persetujuan S&K, jejak audit (T-042). Semuanya BACA saja kecuali
// satu aksi — mengampuni ban — karena itu satu-satunya keputusan manusia di antara ketiganya.
@RestController
class AdminOpsController(
    private val jdbc: JdbcClient,
    private val ra: RaQuery,
    private val audit: AuditService,
    @Value("\${sapuranjau.tournament.tnc-version:}") private val tncVersion: String,
) {

    // ── bans ────────────────────────────────────────────────────────────────────────────────────

    data class BanDto(
        val id: String,
        val userId: String,
        val displayName: String,
        val reason: String, // refund | chargeback (ADR-0025)
        val purchaseId: String?,
        val periodStartId: String,
        val periodStartName: String?,
        val createdAt: Instant,
        val forgivenAt: Instant?,
        val forgiveReason: String?,
        // Sisa jendela dalam SATUAN PERIODE, dihitung ordinal (ADR-0038) terhadap periode berjalan.
        // Negatif/0 = sudah lewat. `null` = tak ada periode ACTIVE, jadi tak ada yang bisa dihitung.
        val periodsRemaining: Int?,
    )

    data class ForgiveRequest(val reason: String = "")

    @GetMapping("/bans")
    fun bans(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<BanDto>> {
        val h = ra.page(range)
        val s = ra.sort(sort, BAN_SORT, default = "created_at")
        val userId = ra.filter(filter)["userId"]?.toLongOrNull()
        val where = if (userId == null) "" else "WHERE b.user_id = $userId"
        val isi = jdbc.sql("$BAN_SELECT $where ORDER BY b.${s.column} ${s.arah} OFFSET ? LIMIT ?")
            .params(h.offset, h.limit).query { rs, _ ->
                BanDto(
                    rs.getString("id"), rs.getString("user_id"),
                    rs.getString("display_name") ?: "Pemain #${rs.getString("user_id")}",
                    rs.getString("reason"), rs.getString("purchase_id"),
                    rs.getString("period_start_id"), rs.getString("period_start_name"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("forgiven_at")?.toInstant(), rs.getString("forgive_reason"),
                    rs.getObject("periods_remaining")?.let { (it as Number).toInt() },
                )
            }.list()
        val total = jdbc.sql("SELECT count(*) FROM tournament_ban").query(Long::class.java).single()
        return ResponseEntity.ok().header("Content-Range", h.contentRange("bans", isi.size, total)).body(isi)
    }

    // Mengampuni = MENANDAI, bukan menghapus (V22). Baris `tournament_ban` yang hilang membuat
    // `purchase` ber-status 'voided' terlihat belum tertangani, dan `PeriodService.issueDeferredBans`
    // menerbitkan ban baru di tick berikutnya — pemain yang sudah diberi tahu "ban dicabut" kena
    // lagi tanpa ada yang menekan tombol.
    @PostMapping("/bans/{id}/forgive")
    fun forgive(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: ForgiveRequest): Map<String, String> {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        val alasan = body.reason.trim()
        if (alasan.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Alasan pengampunan wajib diisi.")
        }
        val n = jdbc.sql(
            "UPDATE tournament_ban SET forgiven_at = now(), forgiven_by = ?, forgive_reason = ? WHERE id = ? AND forgiven_at IS NULL",
        ).params(principal.id, alasan, id).update()
        if (n == 0) {
            // Sudah diampuni sebelumnya, atau id-nya tak ada. Dibedakan supaya operator tak menebak.
            val ada = jdbc.sql("SELECT 1 FROM tournament_ban WHERE id = ?").param(id).query(Int::class.java).optional().isPresent
            if (!ada) throw ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Ban $id tak ada.")
        }
        audit.record(Actor.ADMIN, principal.id, "tournament_ban_forgiven", "tournament_ban:$id", mapOf("reason" to alasan))
        return mapOf("id" to id.toString(), "status" to "forgiven")
    }

    // ── consents (S&K) ──────────────────────────────────────────────────────────────────────────

    data class ConsentDto(
        val id: String,
        val userId: String,
        val displayName: String,
        val periodId: String,
        val periodName: String?,
        val tncVersion: String,
        val agreedAt: Instant,
    )

    @GetMapping("/consents")
    fun consents(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<ConsentDto>> {
        val h = ra.page(range)
        val s = ra.sort(sort, CONSENT_SORT, default = "agreed_at")
        val periodId = ra.filter(filter)["periodId"]?.toLongOrNull()
        val where = if (periodId == null) "" else "WHERE c.period_id = $periodId"
        val isi = jdbc.sql(
            """
            SELECT c.id, c.user_id, c.period_id, c.tnc_version, c.agreed_at, u.display_name, p.name AS period_name
              FROM tournament_consent c
              JOIN app_user u ON u.id = c.user_id
              JOIN period p ON p.id = c.period_id
            $where ORDER BY c.${s.column} ${s.arah} OFFSET ? LIMIT ?
            """,
        ).params(h.offset, h.limit).query { rs, _ ->
            ConsentDto(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("display_name") ?: "Pemain #${rs.getString("user_id")}",
                rs.getString("period_id"),
                rs.getString("period_name"),
                rs.getString("tnc_version"),
                rs.getTimestamp("agreed_at").toInstant(),
            )
        }.list()
        val total = jdbc.sql("SELECT count(*) FROM tournament_consent").query(Long::class.java).single()
        return ResponseEntity.ok().header("Content-Range", h.contentRange("consents", isi.size, total)).body(isi)
    }

    // Versi S&K yang sedang berlaku hidup sebagai PROPERTI aplikasi (ADR-0026, keputusan T-026:
    // tabel S&K ditolak karena tanpa UI ia cuma migrasi tanpa pembeli). Panel menampilkannya supaya
    // operator tahu versi mana yang sedang ditagihkan ke pemain — mengubahnya = deploy, bukan klik,
    // dan itu memang disengaja: naskahnya ikut rilis (legal pack, T-052).
    @GetMapping("/tnc")
    fun tnc(): Map<String, Any> = mapOf(
        "version" to tncVersion,
        "editable" to false,
        "note" to "Versi S&K disetel lewat properti `sapuranjau.tournament.tnc-version` (ADR-0026) — naskahnya ikut rilis.",
    )

    // ── audit ───────────────────────────────────────────────────────────────────────────────────

    data class AuditDto(
        val id: String,
        val actorType: String,
        val actorId: String?,
        val eventType: String,
        val target: String?,
        val detail: String?,
        val createdAt: Instant,
    )

    @GetMapping("/audit-events")
    fun auditEvents(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<AuditDto>> {
        val h = ra.page(range)
        val s = ra.sort(sort, AUDIT_SORT, default = "created_at")
        val f = ra.filter(filter)
        // Filter dibangun dari daftar putih kolom + parameter terikat; nilai klien tak pernah
        // masuk SQL sebagai teks.
        val syarat = mutableListOf<String>()
        val nilai = mutableListOf<Any>()
        f["actorType"]?.let {
            syarat += "actor_type = ?"
            nilai += it
        }
        f["eventType"]?.let {
            syarat += "event_type = ?"
            nilai += it
        }
        f["target"]?.let {
            syarat += "target = ?"
            nilai += it
        }
        val where = if (syarat.isEmpty()) "" else "WHERE ${syarat.joinToString(" AND ")}"

        val isi = jdbc.sql(
            "SELECT id, actor_type, actor_id, event_type, target, detail::text AS detail, created_at " +
                "FROM audit_event $where ORDER BY ${s.column} ${s.arah} OFFSET ? LIMIT ?",
        ).params(nilai + listOf(h.offset, h.limit)).query { rs, _ ->
            AuditDto(
                rs.getString("id"),
                rs.getString("actor_type"),
                rs.getString("actor_id"),
                rs.getString("event_type"),
                rs.getString("target"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toInstant(),
            )
        }.list()
        val total = jdbc.sql("SELECT count(*) FROM audit_event $where").params(nilai).query(Long::class.java).single()
        return ResponseEntity.ok().header("Content-Range", h.contentRange("audit-events", isi.size, total)).body(isi)
    }

    private companion object {
        val BAN_SORT = setOf("id", "created_at", "reason", "forgiven_at")
        val CONSENT_SORT = setOf("id", "agreed_at", "tnc_version")
        val AUDIT_SORT = setOf("id", "created_at", "event_type", "actor_type")

        // `periods_remaining`: sisa jendela sanksi dalam satuan periode terhadap periode ACTIVE.
        // Ekspresi ordinal-nya sengaja SAMA bentuknya dengan `PeriodWindows.banDistanceSql` (ADR-0038)
        // — ini tampilan informatif; yang menegakkan tetap file itu.
        const val BAN_SELECT = """
            SELECT b.id, b.user_id, b.reason, b.purchase_id, b.period_start_id, b.created_at,
                   b.forgiven_at, b.forgive_reason,
                   u.display_name, ps.name AS period_start_name,
                   CASE WHEN b.forgiven_at IS NOT NULL THEN 0 ELSE (
                     SELECT 3 - (SELECT count(*) FROM period x
                                  WHERE x.starts_at > ps.starts_at AND x.starts_at <= act.starts_at)
                       FROM period act WHERE act.status = 'ACTIVE'
                   ) END AS periods_remaining
              FROM tournament_ban b
              JOIN app_user u ON u.id = b.user_id
              JOIN period ps ON ps.id = b.period_start_id
        """
    }
}
