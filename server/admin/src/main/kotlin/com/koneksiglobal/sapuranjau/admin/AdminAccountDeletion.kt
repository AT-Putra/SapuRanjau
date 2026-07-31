package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import com.koneksiglobal.sapuranjau.tournament.TournamentGate
import com.koneksiglobal.sapuranjau.tournament.TournamentStatus
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// Penghapusan akun pemain (ADR-0044) — kewajiban UU PDP yang sebelumnya hanya bisa dijalankan
// dengan SQL manual di server produksi.
//
// "Hapus" di sini berarti **anonimisasi di tempat**, dan itu bukan penghalusan istilah: id
// `app_user` dirujuk tujuh tabel + `audit_event`, dan barisnya menopang milik ORANG LAIN —
// peringkat & cooldown peserta lain (`winner`, ADR-0021/0027), peringkat periode lampau
// (`run`/`level_score`), pembukuan & penanganan void (`purchase`, ADR-0025). Yang dibuang adalah
// kaitannya ke manusia; yang tinggal tak lagi menunjuk siapa pun.
@Service
class AccountDeletionService(
    private val jdbc: JdbcClient,
    private val gate: TournamentGate,
    private val audit: AuditService,
) {

    data class Hasil(val userId: Long, val pesanDihapus: Int, val klaimDibersihkan: Int)

    @Transactional
    fun delete(userId: Long, adminId: Long, alasan: String): Hasil {
        if (alasan.isBlank()) {
            bad("Alasan/rujukan permintaan wajib diisi — ini yang membuktikan penghapusan memang diminta pemiliknya.")
        }
        val sudah = jdbc.sql("SELECT deleted_at IS NOT NULL FROM app_user WHERE id = ?").param(userId)
            .query(Boolean::class.java).optional().orElseThrow {
                ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Pemain $userId tak ada.")
            }
        if (sudah) konflik("Akun ini sudah dihapus.")

        // Pagar 1 — sanksi yang masih berjalan (ADR-0044 §4). Tanpa ini, hapus-akun menjadi jalan
        // keluar dari ban refund 3 periode: masuk lagi dengan akun Google yang sama menghasilkan
        // `firebase_uid` baru, dan catatan sanksinya tak lagi menempel pada siapa pun.
        val banBelumDiampuni = jdbc.sql(
            "SELECT count(*) FROM tournament_ban WHERE user_id = ? AND forgiven_at IS NULL",
        ).param(userId).query(Long::class.java).single() > 0
        if (banBelumDiampuni) {
            if (gate.activePeriodId() == null) {
                // Sisa sanksi dihitung ORDINAL terhadap periode berjalan (ADR-0038). Tanpa periode
                // ACTIVE, "sudah lewat atau belum" tak bisa dijawab — dan menebak ke arah yang
                // menguntungkan penghapusan berarti menebak ke arah yang menghapus sanksi.
                konflik("Tak ada periode berjalan, jadi sisa sanksi pemain ini tak bisa dipastikan. Ulangi setelah periode berikutnya aktif.")
            }
            if (gate.check(userId).status == TournamentStatus.BANNED) {
                konflik("Pemain ini sedang menjalani sanksi turnamen. Penghapusan ditunda sampai sanksinya selesai atau diampuni (ADR-0044).")
            }
        }

        // Pagar 2 — hadiah yang belum tuntas. Hadiah tak bisa dikirim ke akun yang sudah tak
        // menunjuk siapa pun; selesaikan atau relakan dulu.
        val klaimMenggantung = jdbc.sql(
            "SELECT count(*) FROM prize_claim c JOIN winner w ON w.id = c.winner_id WHERE w.user_id = ? AND c.status <> 'paid'",
        ).param(userId).query(Long::class.java).single() > 0
        if (klaimMenggantung) konflik("Masih ada klaim hadiah yang belum lunas. Selesaikan pembayarannya (atau tandai lunas) sebelum menghapus akun.")

        // PII klaim hadiah dihapus sungguhan; `prize_value`/`status`/`paid_at` tinggal sebagai jejak
        // pembukuan & pajak yang tak menunjuk siapa pun.
        val klaim = jdbc.sql(
            "UPDATE prize_claim SET phone_enc = NULL, ewallet_enc = NULL, address_enc = NULL " +
                "WHERE winner_id IN (SELECT id FROM winner WHERE user_id = ?)",
        ).param(userId).update()

        // Kotak masuk dihapus: isinya milik pemain itu sendiri dan tak ada yang bergantung padanya.
        val pesan = jdbc.sql("DELETE FROM message WHERE user_id = ?").param(userId).update()

        // `firebase_uid` diganti nilai buram (kolomnya UNIQUE & NOT NULL) — dengan begitu akun
        // Google yang sama bisa masuk lagi sebagai pemain BARU, yang memang maksudnya: pemain yang
        // menghapus akunnya berhak memulai bersih.
        jdbc.sql(
            "UPDATE app_user SET firebase_uid = ?, email = NULL, phone_enc = NULL, display_name = NULL, " +
                "deleted_at = now() WHERE id = ?",
        ).params("deleted:$userId:${UUID.randomUUID()}", userId).update()

        // Jejaknya menyebut ALASAN/rujukan permintaan, tak pernah data yang baru saja dihapus —
        // `audit_event` tak bisa dihapus siapa pun (T-027), jadi menyalin PII ke sana justru
        // membatalkan penghapusan yang barusan dilakukan.
        audit.record(
            Actor.ADMIN,
            adminId,
            "account_deleted",
            "user:$userId",
            mapOf("reason" to alasan, "messagesDeleted" to pesan, "claimsScrubbed" to klaim),
        )
        return Hasil(userId, pesan, klaim)
    }

    private fun bad(pesan: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, pesan)

    private fun konflik(pesan: String): Nothing = throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, pesan)
}

// Aksi ini sengaja TIDAK diletakkan di `AdminReportController` (yang menyatakan dirinya baca-saja):
// satu tombol yang menghapus data pemain tak boleh menumpang di layar laporan.
@RestController
class AdminAccountController(private val deletion: AccountDeletionService) {

    data class DeleteRequest(val reason: String = "")

    // Pemicu v1 = permintaan pemain lewat email, dieksekusi operator (ADR-0044 §6). Swalayan di
    // klien ditunda: ia butuh verifikasi identitasnya sendiri, dan permintaan hapus yang salah
    // alamat tak bisa dibatalkan.
    @PostMapping("/players/{id}/delete")
    fun delete(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: DeleteRequest): Map<String, Any> {
        principal.require(AdminRole.ADMIN)
        val hasil = deletion.delete(id, principal.id, body.reason.trim())
        return mapOf("id" to id.toString(), "pesanDihapus" to hasil.pesanDihapus, "klaimDibersihkan" to hasil.klaimDibersihkan)
    }
}
