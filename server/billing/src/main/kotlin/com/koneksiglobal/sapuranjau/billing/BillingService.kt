package com.koneksiglobal.sapuranjau.billing

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import com.koneksiglobal.sapuranjau.lives.LifeService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Billing (T-025): verifikasi pembelian → grant PaidLife (ADR-0011/0022), dan penegakan void
// (ADR-0025). Client TAK PERNAH menentukan berapa nyawa yang terbit — jumlahnya dibaca dari SKU
// yang sudah dikonfirmasi Google, bukan dari body request (jalur penipuan #1, ARCH §8).
@Service
class BillingService(
    private val play: PlayPurchases,
    private val lives: LifeService,
    private val jdbc: JdbcClient,
    private val audit: AuditService, // T-027: satu penulis audit_event utk seluruh server
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun verifyAndGrant(uid: String, req: VerifyRequest): VerifyResponse {
        val count = SKU_LIVES[req.productId]
            ?: throw bad("SKU '${req.productId}' bukan produk nyawa yang dikenal.")
        if (req.purchaseToken.isBlank()) throw bad("purchaseToken kosong.")
        val userId = lives.userIdOf(uid)

        // Idempotensi ditegakkan DI DB (`purchase_token UNIQUE`, `08` §2.7), bukan dengan cek-lalu-
        // tulis yang bisa balapan: klien billing memang lazim mengirim ulang token yang sama.
        val fresh = jdbc.sql(
            "INSERT INTO purchase (user_id, product_id, purchase_token, lives_granted, status) " +
                "VALUES (?, ?, ?, 0, 'pending') ON CONFLICT (purchase_token) DO NOTHING",
        ).params(userId, req.productId, req.purchaseToken).update() == 1

        if (!fresh) return existing(req.purchaseToken, userId)

        // Verifikasi GAGAL → exception → transaksi rollback → baris `pending` ikut hilang, jadi
        // token yang sama boleh dicoba lagi nanti (mis. Play telat konsisten).
        val purchase = play.verify(req.productId, req.purchaseToken)
            ?: throw bad("Pembelian tak dikenal Google Play.")
        if (!purchase.purchased) throw bad("Pembelian belum berstatus PURCHASED.")

        val purchaseId = jdbc.sql("SELECT id FROM purchase WHERE purchase_token = ?")
            .param(req.purchaseToken).query(Long::class.java).single()
        lives.grantPaid(userId, purchaseId, count)
        jdbc.sql("UPDATE purchase SET status = 'granted', lives_granted = ?, verified_at = now() WHERE id = ?")
            .params(count, purchaseId).update()

        // Consume di luar jalur kritis: kalau gagal, pemain SUDAH punya nyawanya dan Play Billing
        // sisi klien juga meng-consume. Menggagalkan transaksi karena ini justru mencabut nyawa
        // yang sah. Dicatat supaya tak hilang diam-diam.
        runCatching { play.consume(req.productId, req.purchaseToken) }
            .onFailure { log.warn("consume Play gagal utk purchase $purchaseId — nyawa tetap terbit", it) }

        val w = lives.wallet(userId)
        return VerifyResponse(PurchaseStatus.GRANTED, count, w.free, w.paid)
    }

    // ── Void: refund/chargeback (ADR-0025, strict) ───────────────────────────────────────────────
    // Satu operasi domain yang dipakai poller (dan nanti panel admin/RTDN). Idempoten: void kedua
    // atas purchase yang sama tak menghukum dua kali.
    @Transactional
    fun applyVoid(purchaseToken: String, reason: VoidReason): Boolean {
        val p = jdbc.sql("SELECT id, user_id, status FROM purchase WHERE purchase_token = ?")
            .param(purchaseToken).query { rs, _ -> Purchased(rs.getLong("id"), rs.getLong("user_id"), rs.getString("status")) }
            .optional().orElse(null) ?: return false
        if (p.status == "voided") return false

        jdbc.sql("UPDATE purchase SET status = 'voided', voided_at = now(), void_reason = ? WHERE id = ?")
            .params(reason.name.lowercase(), p.id).update()

        // Clawback higiene: hanya nyawa yang masih tersisa. Yang sudah dipakai tetap terpakai —
        // lantai 0, tak ada saldo minus (ADR-0025).
        val clawed = lives.clawbackPurchase(p.id)

        // Skor periode berjalan → 0 DAN dikunci. `score_locked` inilah yang dibaca `game` untuk
        // menolak start-level & pakai-nyawa (T-022/T-023); tanpa itu skor bisa naik lagi.
        val active = periodId("ACTIVE")
        val zeroed = if (active == null) {
            0
        } else {
            jdbc.sql(
                "UPDATE run SET total_score = 0, score_locked = true, updated_at = now() WHERE user_id = ? AND period_id = ?",
            ).params(p.userId, active).update()
        }

        val banStart = active ?: periodId("UPCOMING")
        if (banStart != null) {
            // Jendela ban = P, P+1, P+2 (eligible P+3). `period_end_id` diisi hanya bila P+2 sudah
            // dibuat admin; NULL = belum bisa dipastikan. Penegakannya ordinal, bukan lewat kolom
            // ini (ADR-0038, T-026).
            val end = jdbc.sql(
                "SELECT id FROM period WHERE starts_at > (SELECT starts_at FROM period WHERE id = ?) " +
                    "ORDER BY starts_at OFFSET 1 LIMIT 1",
            ).param(banStart).query(Long::class.java).optional().orElse(null)
            jdbc.sql(
                "INSERT INTO tournament_ban (user_id, reason, purchase_id, period_start_id, period_end_id) VALUES (?, ?, ?, ?, ?)",
            ).params(p.userId, reason.name.lowercase(), p.id, banStart, end).update()
        }

        audit(
            p.userId,
            if (banStart == null) "purchase_voided_ban_deferred" else "purchase_voided",
            "purchase:${p.id}",
            mapOf(
                "reason" to reason.name.lowercase(),
                "livesClawedBack" to clawed,
                "runsZeroed" to zeroed,
                "banPeriodStartId" to banStart,
            ),
        )
        // Tanpa periode mana pun, ban tak bisa dicatat (period_start_id NOT NULL). Void & clawback
        // tetap jalan; sanksinya tersurat di audit agar admin bisa menerbitkan manual (T-042).
        if (banStart == null) log.warn("Void purchase ${p.id}: tak ada periode ACTIVE/UPCOMING → ban ditunda, lihat audit_event")
        return true
    }

    private fun existing(purchaseToken: String, userId: Long): VerifyResponse {
        val row = jdbc.sql("SELECT status, lives_granted FROM purchase WHERE purchase_token = ?")
            .param(purchaseToken).query().singleRow()
        val w = lives.wallet(userId)
        return VerifyResponse(PurchaseStatus.of(row["status"] as String), row["lives_granted"] as Int, w.free, w.paid)
    }

    private fun periodId(status: String): Long? = jdbc.sql("SELECT id FROM period WHERE status = ? ORDER BY starts_at LIMIT 1")
        .param(status).query(Long::class.java).optional().orElse(null)

    private fun audit(userId: Long, event: String, target: String, detail: Map<String, Any?>) =
        audit.record(Actor.SYSTEM, userId, event, target, detail)

    private fun bad(msg: String) = ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, msg)

    private class Purchased(val id: Long, val userId: Long, val status: String)

    private companion object {
        // Isi paket = ADR-0022. HARGA tak ada di sini: harga hidup di Play Console, bukan config app.
        val SKU_LIVES = mapOf("life_s" to 1, "life_m" to 5, "life_l" to 10)
    }
}
