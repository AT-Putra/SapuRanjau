package com.koneksiglobal.sapuranjau.admin

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

// Peran RBAC panel (ARCH §10). Nilai DB dijaga CHECK constraint (`08` §2.16) — enum ini yang membuat
// typo jadi error kompilasi, bukan error runtime Postgres (pola `Actor` di T-027).
//
// v1 sengaja hanya 3 peran datar tanpa matriks permission: yang benar-benar berbeda cuma siapa boleh
// membaca PII pemenang (`finance`) dan siapa boleh mengelola akun admin (`admin`). Matriks per-resource
// baru berbayar kalau operatornya sudah puluhan.
enum class AdminRole(val dbValue: String) {
    ADMIN("admin"), // penuh, termasuk mengelola akun admin lain
    FINANCE("finance"), // boleh mendekripsi PII klaim hadiah (ADR-0020) & menandai lunas
    MODERATOR("moderator"), // operasi harian: level, periode, pesan — tanpa PII & tanpa akun
    ;

    companion object {
        fun of(dbValue: String): AdminRole = entries.first { it.dbValue == dbValue }
    }
}

data class AdminUser(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val role: AdminRole,
    val totpSecretEnc: ByteArray?,
    val disabledAt: Instant?,
) {
    val totpEnrolled: Boolean get() = totpSecretEnc != null

    // data class + ByteArray: equals/hashCode bawaan membandingkan referensi array. Tak dipakai
    // membandingkan di mana pun, tapi biarkan konsisten daripada menyimpan jebakan senyap.
    override fun equals(other: Any?): Boolean = this === other || (other is AdminUser && other.id == id)
    override fun hashCode(): Int = id.hashCode()
}

@Repository
class AdminUsers(private val jdbc: JdbcClient) {

    fun byUsername(username: String): AdminUser? =
        jdbc.sql("$SELECT WHERE username = ?").param(username).query(::map).optional().orElse(null)

    fun byId(id: Long): AdminUser? =
        jdbc.sql("$SELECT WHERE id = ?").param(id).query(::map).optional().orElse(null)

    fun count(): Long = jdbc.sql("SELECT count(*) FROM admin_user").query(Long::class.java).single()

    // Halaman untuk list React-Admin (ADR-0013). `sort` sudah divalidasi terhadap daftar putih kolom
    // oleh pemanggil — string apa pun dari klien tak pernah sampai ke SQL tanpa lewat itu.
    fun page(sortColumn: String, ascending: Boolean, offset: Int, limit: Int): List<AdminUser> =
        jdbc.sql("$SELECT ORDER BY $sortColumn ${if (ascending) "ASC" else "DESC"} OFFSET ? LIMIT ?")
            .params(listOf(offset, limit)).query(::map).list()

    fun insert(username: String, passwordHash: String, role: AdminRole): Long =
        jdbc.sql("INSERT INTO admin_user (username, password_hash, role) VALUES (?, ?, ?) RETURNING id")
            .params(listOf(username, passwordHash, role.dbValue)).query(Long::class.java).single()

    fun saveTotpSecret(id: Long, secretEnc: ByteArray) {
        jdbc.sql("UPDATE admin_user SET totp_secret_enc = ? WHERE id = ?").params(listOf(secretEnc, id)).update()
    }

    // Reset 2FA (T-042): secret dibuang → login berikutnya dipaksa enrol ulang (AdminAuthService).
    // Satu-satunya pemulihan untuk operator yang kehilangan authenticator-nya; sebelum ini jalannya
    // cuma SQL manual di server produksi.
    fun clearTotpSecret(id: Long) {
        jdbc.sql("UPDATE admin_user SET totp_secret_enc = NULL WHERE id = ?").param(id).update()
    }

    fun touchLogin(id: Long) {
        jdbc.sql("UPDATE admin_user SET last_login_at = now() WHERE id = ?").param(id).update()
    }

    fun update(id: Long, role: AdminRole, disabled: Boolean) {
        jdbc.sql("UPDATE admin_user SET role = ?, disabled_at = ${if (disabled) "coalesce(disabled_at, now())" else "NULL"} WHERE id = ?")
            .params(listOf(role.dbValue, id)).update()
    }

    fun setPassword(id: Long, passwordHash: String) {
        // Ganti password = TOTP di-reset juga? TIDAK: keduanya faktor terpisah, dan me-reset TOTP
        // saat ganti password justru menurunkan 2FA jadi satu faktor untuk sesaat.
        jdbc.sql("UPDATE admin_user SET password_hash = ? WHERE id = ?").params(listOf(passwordHash, id)).update()
    }

    // Jumlah akun ADMIN yang masih aktif — penjaga agar panel tak bisa mengunci dirinya sendiri.
    fun activeAdminCount(): Long =
        jdbc.sql("SELECT count(*) FROM admin_user WHERE role = 'admin' AND disabled_at IS NULL")
            .query(Long::class.java).single()

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = AdminUser(
        id = rs.getLong("id"),
        username = rs.getString("username"),
        passwordHash = rs.getString("password_hash"),
        role = AdminRole.of(rs.getString("role")),
        totpSecretEnc = rs.getBytes("totp_secret_enc"),
        disabledAt = rs.getTimestamp("disabled_at")?.toInstant(),
    )

    private companion object {
        const val SELECT = "SELECT id, username, password_hash, role, totp_secret_enc, disabled_at FROM admin_user"
    }
}
