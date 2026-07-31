package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import com.koneksiglobal.sapuranjau.tournament.PiiCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

// Hasil percobaan login. Enrolment TOTP bukan error — ia langkah berikutnya, jadi ia tipe hasil,
// bukan exception.
sealed interface LoginOutcome {
    data class Ok(val principal: AdminPrincipal) : LoginOutcome
    data class SetupTotp(val pending: PendingTotp, val otpauthUri: String) : LoginOutcome
}

// Login + enrolment 2FA + pembuatan akun admin (T-040; ARCH §10, ADR-0013).
@Service
class AdminAuthService(
    private val users: AdminUsers,
    private val encoder: PasswordEncoder,
    private val pii: PiiCipher, // secret TOTP disimpan terenkripsi (V21) — sama seperti PII klaim
    private val audit: AuditService,
    private val jdbc: JdbcClient,
    @Value("\${sapuranjau.admin.totp-issuer:Sapu Ranjau}") private val issuer: String,
    @Value("\${sapuranjau.admin.max-failed-logins:10}") private val maxFailed: Int,
) {

    fun login(username: String, password: String, code: String?): LoginOutcome {
        val nama = username.trim()
        tolakBilaTerlaluSeringGagal(nama)
        val user = users.byUsername(nama)

        // Password diverifikasi juga saat user tak ada / dinonaktifkan (terhadap hash dummy), supaya
        // lamanya respons tak membedakan "username salah" dari "password salah" — bcrypt cukup lambat
        // untuk membuat selisih itu terukur dari jaringan.
        val hash = user?.takeIf { it.disabledAt == null }?.passwordHash ?: HASH_DUMMY
        val cocok = encoder.matches(password, hash)
        if (user == null || user.disabledAt != null || !cocok) {
            gagal(nama, user?.id, "password")
            throw ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.BAD_CREDENTIALS, "Username atau password salah.")
        }

        val secretEnc = user.totpSecretEnc
        if (secretEnc == null) {
            // Akun baru: password benar tapi 2FA belum ada. Panel tak boleh bisa dimasuki dengan satu
            // faktor (gate `RELEASE` §4), jadi sesi BELUM terautentikasi — yang diberikan cuma secret
            // untuk di-enrol, dan ia baru tersimpan setelah operator membuktikan kode yang cocok.
            val secret = Totp.randomSecret()
            audit.record(Actor.ADMIN, user.id, "admin_totp_setup_started", "admin:${user.username}")
            return LoginOutcome.SetupTotp(
                PendingTotp(user.id, secret),
                Totp.otpauthUri(issuer, user.username, secret),
            )
        }

        if (code.isNullOrBlank()) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.TOTP_REQUIRED, "Masukkan kode 6 digit dari aplikasi authenticator.")
        }
        if (!Totp.verify(pii.decrypt(secretEnc), code)) {
            gagal(nama, user.id, "totp")
            throw ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.TOTP_REQUIRED, "Kode authenticator salah atau kedaluwarsa.")
        }
        return LoginOutcome.Ok(sukses(user))
    }

    // Konfirmasi enrolment: kode harus cocok dengan secret yang dipegang sesi, baru ia ditulis ke DB.
    fun enrollTotp(pending: PendingTotp, code: String): AdminPrincipal {
        val user = users.byId(pending.adminId)?.takeIf { it.disabledAt == null }
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Sesi enrolment tak berlaku lagi.")
        if (user.totpEnrolled) {
            // Balapan dua tab / enrolment ganda: yang pertama menang, yang kedua harus login ulang
            // memakai secret yang benar-benar tersimpan.
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "TOTP akun ini sudah terdaftar — login ulang dengan kode dari authenticator.")
        }
        if (!Totp.verify(pending.secretBase32, code)) {
            gagal(user.username, user.id, "totp_enroll")
            throw ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.TOTP_REQUIRED, "Kode authenticator salah atau kedaluwarsa.")
        }
        users.saveTotpSecret(user.id, pii.encrypt(pending.secretBase32))
        audit.record(Actor.ADMIN, user.id, "admin_totp_enrolled", "admin:${user.username}")
        return sukses(user)
    }

    fun createAdmin(pembuat: AdminPrincipal, username: String, password: String, role: AdminRole): Long {
        val nama = username.trim()
        if (!USERNAME.matches(nama)) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Username 3–32 karakter: huruf, angka, titik, garis bawah, strip.")
        }
        cekPassword(password)
        if (users.byUsername(nama) != null) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Username '$nama' sudah dipakai.")
        }
        val id = users.insert(nama, hash(password), role)
        // Akun terbit TANPA secret TOTP → login pertamanya dipaksa enrol (lihat login()).
        audit.record(Actor.ADMIN, pembuat.id, "admin_created", "admin:$nama", mapOf("role" to role.dbValue))
        return id
    }

    fun cekPassword(password: String) {
        if (password.length < MIN_PASSWORD) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Password minimal $MIN_PASSWORD karakter.")
        }
    }

    // `PasswordEncoder.encode` bertipe nullable di Spring Security 7; implementasi BCrypt tak pernah
    // mengembalikan null, dan kalau suatu hari ia melakukannya, lebih baik meledak di sini daripada
    // menyimpan hash kosong yang cocok dengan apa pun.
    fun hash(password: String): String = requireNotNull(encoder.encode(password)) { "password encoder mengembalikan null" }

    private fun sukses(user: AdminUser): AdminPrincipal {
        users.touchLogin(user.id)
        audit.record(Actor.ADMIN, user.id, "admin_login", "admin:${user.username}", mapOf("role" to user.role.dbValue))
        return AdminPrincipal(user.id, user.username, user.role)
    }

    private fun gagal(username: String, adminId: Long?, tahap: String) {
        audit.record(Actor.ADMIN, adminId, EVENT_GAGAL, "admin:$username", mapOf("stage" to tahap))
    }

    // Rem brute-force tanpa tabel/cache baru: hitung kegagalan dari `audit_event` yang memang sudah
    // ditulis. TOTP 6 digit tanpa rem hanya sekuat 10^6 tebakan — dengan rem, penyerang yang sudah
    // memegang password pun mentok jauh sebelum menembusnya.
    //
    // ponytail: dihitung per-username, bukan per-IP. Per-IP butuh menyimpan IP (data pribadi tambahan)
    // dan tetap bisa diputar lewat proxy; per-username justru melindungi akun yang jadi sasaran.
    // Efek sampingnya: penyerang bisa mengunci akun operator selama 15 menit (DoS kecil, sadar dipilih).
    private fun tolakBilaTerlaluSeringGagal(username: String) {
        val gagal = jdbc.sql(
            "SELECT count(*) FROM audit_event WHERE event_type = ? AND target = ? AND created_at > now() - interval '15 minutes'",
        ).params(listOf(EVENT_GAGAL, "admin:$username")).query(Long::class.java).single()
        if (gagal >= maxFailed) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_ATTEMPTS,
                "Terlalu banyak percobaan login gagal. Coba lagi dalam 15 menit.",
            )
        }
    }

    private companion object {
        const val MIN_PASSWORD = 12
        const val EVENT_GAGAL = "admin_login_failed"
        val USERNAME = Regex("^[A-Za-z0-9._-]{3,32}$")

        // Hash bcrypt dari string acak yang tak dipakai akun mana pun — hanya untuk menyamakan waktu
        // respons saat username tak ditemukan.
        const val HASH_DUMMY = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    }
}
