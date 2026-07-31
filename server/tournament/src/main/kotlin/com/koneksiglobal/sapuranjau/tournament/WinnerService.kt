package com.koneksiglobal.sapuranjau.tournament

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Penentuan pemenang periode (ADR-0021) + gugur-pemenang. Hadiahnya sendiri dibagikan MANUAL di luar
// sistem (ADR-0021) — yang disimpan di sini cuma daftar final + jejaknya.
//
// Tombol admin (gugurkan, kirim pesan) = T-042; di sini cuma operasi domainnya, supaya controller
// admin nanti tinggal tipis dan aturan eligible-nya tak ditulis ulang di lapisan HTTP.
@Service
class WinnerService(private val jdbc: JdbcClient, private val audit: AuditService) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Dipanggil rollover untuk periode yang baru berakhir. Idempoten: periode yang sudah punya
    // pemenang dilewati, jadi tick yang berjalan berulang (atau server yang mati di tengah
    // finalisasi) tak pernah menghasilkan daftar ganda.
    @Transactional
    fun finalizePeriod(periodId: Long): Int {
        // Tanpa `prize_config` tak ada hadiah → tak ada pemenang. Wajar: periode tanpa sponsor.
        val slots = jdbc.sql("SELECT winners_count FROM prize_config WHERE period_id = ?")
            .param(periodId).query(Int::class.javaObjectType).optional().orElse(null) ?: return 0

        val picked = eligible(periodId, slots)
        picked.forEachIndexed { i, userId -> insertWinner(periodId, userId, i + 1) }

        audit(
            "winner_selected", "period:$periodId",
            mapOf("slots" to slots, "picked" to picked.size),
        )
        if (picked.size < slots) {
            // Bukan error: ADR-0021 "pemenang aktual = min(dikonfigurasi, peserta-eligible)".
            log.info("Periode $periodId: $slots slot hadiah, ${picked.size} pemenang eligible")
        }
        return picked.size
    }

    // Gugurkan pemenang — ALASAN WAJIB, tercatat audit, bukan aksi senyap (ADR-0021). Peringkat di
    // bawahnya naik satu tingkat dan kandidat eligible berikutnya mengisi peringkat terakhir.
    // `adminId` diisi saat tombolnya ditekan orang di panel (T-042) — jejaknya lalu menyebut siapa,
    // bukan "system". Tetap nullable karena aksi ini juga sah dipanggil tanpa panel (mis. skrip
    // pemulihan), dan memaksa id palsu untuk kasus itu justru membuat audit berbohong.
    @Transactional
    fun disqualify(winnerId: Long, reason: String, adminId: Long? = null) {
        if (reason.isBlank()) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Alasan gugur wajib diisi.")
        }
        val row = jdbc.sql("SELECT period_id, user_id, rank, status FROM winner WHERE id = ?")
            .param(winnerId)
            .query { rs, _ -> Winner(rs.getLong("period_id"), rs.getLong("user_id"), rs.getInt("rank"), rs.getString("status")) }
            .optional().orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Pemenang tak ditemukan.")
        if (row.status == "disqualified") return // idempoten

        // Parkir baris yang digugurkan DI LUAR band peringkat hadiah. `UNIQUE (period_id, rank)`
        // (`08` §2.10) tak mengizinkan slotnya diisi orang lain selama ia masih memegang nomornya;
        // peringkat negatif unik & jelas bukan peringkat hadiah. Nomor aslinya tersimpan di audit.
        jdbc.sql("UPDATE winner SET status = 'disqualified', disqualify_reason = ?, rank = ? WHERE id = ?")
            .params(reason, -winnerId.toInt(), winnerId).update()

        // Naikkan yang di bawahnya satu tingkat. n ≤ 10 (ADR-0021) → loop terurut lebih murah
        // daripada membuat unique index yang tak deferrable ini menerima satu UPDATE massal.
        val below = jdbc.sql("SELECT id FROM winner WHERE period_id = ? AND status = 'active' AND rank > ? ORDER BY rank")
            .params(row.periodId, row.rank).query(Long::class.java).list()
        below.forEach { jdbc.sql("UPDATE winner SET rank = rank - 1 WHERE id = ?").param(it).update() }

        // Peringkat terakhir yang kini kosong diisi kandidat eligible berikutnya (bila ada).
        val promoted = eligible(row.periodId, 1).firstOrNull()
        promoted?.let { insertWinner(row.periodId, it, row.rank + below.size) }

        audit.record(
            if (adminId != null) Actor.ADMIN else Actor.SYSTEM, adminId,
            "winner_disqualified", "winner:$winnerId",
            mapOf(
                "periodId" to row.periodId, "userId" to row.userId, "originalRank" to row.rank,
                "reason" to reason, "promotedUserId" to promoted,
            ),
        )
    }

    // Kandidat pemenang, urut tie-breaker ADR-0009 persis index `run_leaderboard` (`08` §2.4).
    // Dilewati: skor terkunci refund (ADR-0025), yang sedang kena ban (ordinal, ADR-0038), yang
    // sedang cooldown pemenang (ADR-0027), dan yang sudah tercatat sebagai pemenang periode ini.
    //
    // `total_score > 0` = pilihan saya, bukan dari ADR (tunable): hadiah sponsor untuk yang bermain,
    // bukan untuk yang sekadar membuat run. Tanpa ini, periode sepi bisa memberi hadiah ke pemain
    // ber-skor nol.
    private fun eligible(periodId: Long, limit: Int): List<Long> =
        jdbc.sql(
            """
            SELECT r.user_id FROM run r
             WHERE r.period_id = :pid AND r.score_locked = false AND r.total_score > 0
               AND coalesce(${banDistanceSql("r.user_id", ":pid")}, $BAN_PERIODS) >= $BAN_PERIODS
               AND NOT ${onWinnerCooldownSql("r.user_id", ":pid")}
               AND NOT EXISTS (SELECT 1 FROM winner w WHERE w.period_id = :pid AND w.user_id = r.user_id)
             ORDER BY r.total_score DESC, r.lives_used, r.total_time_ms, r.total_moves, r.completed_all_at
             LIMIT :lim
            """,
        ).param("pid", periodId).param("lim", limit).query(Long::class.java).list().filterNotNull()

    private fun insertWinner(periodId: Long, userId: Long, rank: Int) {
        jdbc.sql("INSERT INTO winner (period_id, user_id, rank) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
            .params(periodId, userId, rank).update()
    }

    // Gugur-pemenang & pemilihan pemenang = aksi server (T-042 nanti melengkapi identitas admin yang
    // menekan tombolnya lewat actor `ADMIN`).
    private fun audit(event: String, target: String, detail: Map<String, Any?>) =
        audit.record(Actor.SYSTEM, null, event, target, detail)

    private class Winner(val periodId: Long, val userId: Long, val rank: Int, val status: String)
}
