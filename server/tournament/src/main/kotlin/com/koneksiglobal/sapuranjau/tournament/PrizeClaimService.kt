package com.koneksiglobal.sapuranjau.tournament

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Form klaim hadiah pemenang (T-029, ADR-0021): distribusi hadiahnya MANUAL di luar sistem, jadi
// yang dikerjakan di sini cuma mengumpulkan data kirim + jejaknya. Verifikasi pemenang tetap manual
// lewat telepon (ADR-0030) — karena itu nomor HP wajib.
//
// Semua kolom `*_enc` melewati [PiiCipher] (ARCH §14). Yang TAK pernah terjadi di sini: menulis isi
// PII ke `audit_event`. Audit mencatat BAHWA klaim masuk/berubah, bukan isinya — kalau tidak, tabel
// append-only yang tak bisa dihapus itu berubah jadi salinan kedua data pribadi tanpa enkripsi.
@Service
class PrizeClaimService(
    private val jdbc: JdbcClient,
    private val pii: PiiCipher,
    private val audit: AuditService,
) {

    @Transactional
    fun submit(userId: Long, req: PrizeClaimRequest): PrizeClaimResponse {
        val phone = req.phone.trim()
        val ewallet = req.ewallet?.trim()?.takeIf { it.isNotEmpty() }
        val address = req.address?.trim()?.takeIf { it.isNotEmpty() }

        if (!PHONE.matches(phone)) bad("Nomor HP tak valid (8–15 digit, boleh diawali +).")
        // Hadiah bisa berupa saldo e-wallet ATAU barang fisik (GDD §8.3) — tanpa salah satunya,
        // klaim ini tak bisa dibayar oleh siapa pun.
        if (ewallet == null && address == null) bad("Isi nomor e-wallet atau alamat pengiriman.")

        // Kemenangan yang belum diklaim, terbaru dulu. Pemenang yang digugurkan (status
        // 'disqualified') tak punya hak klaim (ADR-0021).
        val winnerId = jdbc.sql(
            "SELECT w.id FROM winner w JOIN period p ON p.id = w.period_id " +
                "WHERE w.user_id = ? AND w.status = 'active' " +
                "AND NOT EXISTS (SELECT 1 FROM prize_claim c WHERE c.winner_id = w.id AND c.status <> 'pending') " +
                "ORDER BY p.starts_at DESC LIMIT 1",
        ).param(userId).query(Long::class.java).optional().orElse(null)
            ?: throw ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.CONFLICT,
                "Tak ada hadiah yang bisa diklaim (belum menang, atau klaimnya sudah diproses admin).",
            )

        // Selama `pending`, pemain boleh MEMPERBAIKI datanya — salah ketik nomor e-wallet di sini
        // berarti hadiah nyata nyasar. Begitu admin menandai verified/paid, formulirnya beku:
        // filter di atas sudah menyaringnya, jadi upsert ini tak pernah menimpa yang sudah diproses.
        jdbc.sql(
            "INSERT INTO prize_claim (winner_id, phone_enc, ewallet_enc, address_enc) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (winner_id) DO UPDATE SET phone_enc = EXCLUDED.phone_enc, " +
                "ewallet_enc = EXCLUDED.ewallet_enc, address_enc = EXCLUDED.address_enc",
        ).params(
            listOf(winnerId, pii.encrypt(phone), ewallet?.let(pii::encrypt), address?.let(pii::encrypt)),
        ).update()

        // Satu jenis event untuk kirim & perbaiki: `audit_event` bertimestamp, jadi dua baris untuk
        // winner yang sama sudah berarti formulirnya diubah. Membedakannya butuh query tambahan
        // (atau trik `xmax = 0`) demi informasi yang sudah tersirat.
        audit.record(
            Actor.PLAYER,
            userId,
            "prize_claim_saved",
            "winner:$winnerId",
            // Nama field saja — TIDAK ada nomor HP / e-wallet / alamat di sini.
            mapOf("fields" to listOfNotNull("phone", ewallet?.let { "ewallet" }, address?.let { "address" })),
        )

        return PrizeClaimResponse(winnerId.toString(), "pending")
    }

    private fun bad(msg: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, msg)

    private companion object {
        // Pagar trust-boundary, bukan validasi operator: nomor diverifikasi manusia lewat telepon
        // (ADR-0030), jadi yang perlu ditolak cuma yang jelas-jelas bukan nomor.
        val PHONE = Regex("^\\+?[0-9]{8,15}$")
    }
}

data class PrizeClaimRequest(val phone: String, val ewallet: String? = null, val address: String? = null)

// Tak pernah memantulkan kembali PII yang dikirim: pemain melihat status klaimnya, bukan salinan
// datanya. Kalau salah ketik, ia mengirim ulang formulirnya selama masih `pending`.
data class PrizeClaimResponse(val winnerId: String, val status: String)
