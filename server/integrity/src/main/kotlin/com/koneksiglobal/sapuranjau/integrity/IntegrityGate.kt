package com.koneksiglobal.sapuranjau.integrity

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

// Gerbang device (T-028, ADR-0041): attestasi SEKALI PER SESI, hasilnya di-cache di
// `app_user.integrity_ok_until` (V19). Titik masuk turnamen & klaim casual cukup membaca kolom itu
// → nol panggilan Google di jalur yang punya anggaran p95 200 ms (ARCH §11).
//
// Diperiksa HANYA di titik masuk (`level/start`, `life/use`, `casual/claim`) — sama seperti gerbang
// turnamen T-026 — supaya verdict yang kedaluwarsa di tengah level tak pernah memutus permainan.
@Service
class IntegrityGate(
    private val verifier: IntegrityVerifier,
    private val jdbc: JdbcClient,
    private val audit: AuditService,
    // Masa berlaku verdict yang lulus. Makin pendek makin rapat terhadap pemain yang berpindah ke
    // perangkat lain (cache-nya per pemain, bukan per perangkat), makin sering pula klien attest.
    @Value("\${sapuranjau.integrity.valid-for:PT6H}") private val validFor: Duration,
    // Masa berlaku saat KITA yang tak bisa memeriksa (ADR-0041). Sengaja jauh lebih pendek: pemain
    // tetap bisa bermain saat Google terganggu, tapi pemeriksaan kembali berjalan begitu Google pulih
    // — bukan memberi jendela buta selama berjam-jam.
    @Value("\${sapuranjau.integrity.unavailable-for:PT15M}") private val unavailableFor: Duration,
) {

    // POST /v1/integrity. Balas kapan verdict ini berlaku sampai; klien attest lagi sebelum itu.
    @Transactional
    fun attest(userId: Long, token: String): Instant = when (val v = verifier.verdict(token)) {
        is IntegrityVerdict.Pass -> stamp(userId, validFor)

        // Perangkat/APK memang tak lulus → tolak. Ini bukti tentang pemain, jadi ia dihukum.
        is IntegrityVerdict.Fail -> {
            audit.record(Actor.SYSTEM, userId, "integrity_failed", null, mapOf("reason" to v.reason))
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ErrorCode.INTEGRITY_FAILED,
                "Perangkat atau aplikasi ini tak lolos pemeriksaan keamanan Google Play.",
            )
        }

        // Kita yang tak bisa memeriksa → izinkan, tapi catat. Kalau jendela buta ini ternyata sering,
        // adminlah yang harus melihatnya di audit, bukan pemain yang menanggung tebakan kita.
        is IntegrityVerdict.Unavailable -> {
            audit.record(Actor.SYSTEM, userId, "integrity_unavailable", null, mapOf("reason" to v.reason))
            stamp(userId, unavailableFor)
        }
    }

    // Penegakan di titik masuk. Belum pernah attest / sudah kedaluwarsa BUKAN kegagalan perangkat —
    // klien tinggal memanggil `POST /v1/integrity` lalu mengulang aksinya, karena itu kodenya beda.
    fun require(userId: Long) {
        val ok = jdbc.sql("SELECT integrity_ok_until > now() FROM app_user WHERE id = ?")
            .param(userId).query(Boolean::class.javaObjectType).optional().orElse(false)
        if (ok != true) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                ErrorCode.INTEGRITY_REQUIRED,
                "Perangkat perlu diperiksa dulu (POST /v1/integrity).",
            )
        }
    }

    private fun stamp(userId: Long, window: Duration): Instant {
        val until = Instant.now().plus(window)
        jdbc.sql("UPDATE app_user SET integrity_ok_until = ? WHERE id = ?")
            .params(until.atOffset(ZoneOffset.UTC), userId).update()
        return until
    }
}
