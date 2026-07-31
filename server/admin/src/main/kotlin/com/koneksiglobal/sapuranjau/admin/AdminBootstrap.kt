package com.koneksiglobal.sapuranjau.admin

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

@Configuration
class AdminBeans {
    // BCrypt dari `spring-security-crypto` — kelas mandiri, tanpa auto-config apa pun (starter-security
    // sengaja TIDAK dipasang; lihat build.gradle.kts). Kekuatan default (2^10) memakan ~100 ms per
    // verifikasi: cukup pahit untuk penebak, tak terasa untuk 1–3 operator.
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}

// Akun admin pertama. Panel tak punya halaman "daftar" — akun dibuat oleh akun lain (T-040), jadi
// harus ada satu yang lahir dari luar sistem.
//
// Dijalankan HANYA saat tabel `admin_user` kosong, dan hanya kalau propertinya diisi. Kredensial
// default yang ter-commit sengaja tak ada (gate `RELEASE` §4): server yang lupa dikonfigurasi berakhir
// tanpa admin sama sekali — merepotkan, tapi bukan pintu terbuka.
@Component
class AdminBootstrap(
    private val users: AdminUsers,
    private val encoder: PasswordEncoder,
    @Value("\${sapuranjau.admin.bootstrap.username:}") private val username: String,
    @Value("\${sapuranjau.admin.bootstrap.password:}") private val password: String,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) = jalankan()

    // Terpisah dari run() supaya bisa dipanggil test tanpa merakit ApplicationArguments palsu.
    fun jalankan() {
        if (users.count() > 0) return
        if (username.isBlank() || password.isBlank()) {
            log.warn(
                "Tabel admin_user KOSONG dan sapuranjau.admin.bootstrap.* tak diisi — panel /admin " +
                    "tak bisa dimasuki siapa pun. Isi username+password sekali, lalu cabut lagi dari env.",
            )
            return
        }
        // Gagal-cepat, bukan lewat diam-diam: password bootstrap yang lemah adalah satu-satunya
        // penjaga panel pada boot pertama, dan kalau ia ditolak tanpa suara operator baru tahu saat
        // mencoba login (dan tak tahu kenapa).
        check(password.length >= MIN_PASSWORD) {
            "sapuranjau.admin.bootstrap.password terlalu pendek (minimal $MIN_PASSWORD karakter)."
        }
        val id = users.insert(username.trim(), requireNotNull(encoder.encode(password)), AdminRole.ADMIN)
        log.warn(
            "Akun admin awal '{}' dibuat (id={}). SEGERA: login ke /admin, selesaikan enrolment TOTP, " +
                "lalu hapus sapuranjau.admin.bootstrap.* dari env-file.",
            username.trim(),
            id,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(AdminBootstrap::class.java)
        const val MIN_PASSWORD = 12
    }
}
