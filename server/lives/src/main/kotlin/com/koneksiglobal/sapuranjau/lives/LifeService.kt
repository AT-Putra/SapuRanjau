package com.koneksiglobal.sapuranjau.lives

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// Dompet nyawa (T-023) — `life_ledger` satu baris per token (ADR-0008, docs/08 §2.8).
// Tiga operasi = tiga SQL: grant, saldo, konsumsi. Tak ada entity Spring Data di sini; tak ada yang
// perlu di-CRUD, dan aturan FIFO-expiry hidup di ORDER BY yang tak bisa diungkap repository turunan.
@Service
class LifeService(private val jdbc: JdbcClient) {

    // Saldo nyawa AKTIF (docs/05 §3 `GET /wallet`). Grant periode dijalankan dulu supaya pemain baru
    // langsung melihat 2 FreeLife-nya, bukan setelah kematian pertama.
    @Transactional
    fun wallet(userId: Long): Wallet {
        grantPeriodLives(userId)
        return jdbc.sql(
            "SELECT count(*) FILTER (WHERE type = 'free') AS free, " +
                "count(*) FILTER (WHERE type = 'paid') AS paid, min(expiry) AS next_expiry " +
                "FROM life_ledger WHERE user_id = ? AND status = 'available' AND (expiry IS NULL OR expiry > now())",
        ).param(userId).query { rs, _ ->
            Wallet(rs.getInt("free"), rs.getInt("paid"), rs.getTimestamp("next_expiry")?.toInstant())
        }.single()
    }

    // Konsumsi satu nyawa, FIFO-expiry (ADR-0008): `expiry NULLS LAST` = yang paling cepat hangus
    // dipakai lebih dulu → free (terikat periode) sebelum paid (carry-over). Satu UPDATE atomik lewat
    // partial index `life_available`; `SKIP LOCKED` supaya dua request paralel tak berebut baris yang
    // sama (yang kedua mengambil token berikutnya, bukan menunggu). false = dompet kosong.
    @Transactional
    fun consumeOne(userId: Long, runId: Long): Boolean {
        grantPeriodLives(userId)
        return jdbc.sql(
            "UPDATE life_ledger SET status = 'used', used_at = now(), used_in_run_id = ? WHERE id = (" +
                "SELECT id FROM life_ledger " +
                "WHERE user_id = ? AND status = 'available' AND (expiry IS NULL OR expiry > now()) " +
                "ORDER BY expiry NULLS LAST, id LIMIT 1 FOR UPDATE SKIP LOCKED)",
        ).params(runId, userId).update() == 1
    }

    // 2 FreeLife tiap periode (GDD §7.2) diberikan MALAS — saat pemain pertama menyentuh dompetnya di
    // periode itu. Tanpa batch job atas seluruh basis pemain, dan otomatis benar untuk yang mendaftar
    // di tengah periode. Hangus di akhir periode lewat `expiry = period.ends_at` (ADR-0008).
    @Transactional
    fun grantPeriodLives(userId: Long) {
        // Serialisasi per-pemain: dua request bersamaan tak boleh menghasilkan 4 nyawa. Lock baris
        // app_user (bukan tabel) → hanya pemain ini yang menunggu.
        jdbc.sql("SELECT id FROM app_user WHERE id = ? FOR UPDATE").param(userId).query(Long::class.java).optional()

        jdbc.sql(
            "INSERT INTO life_ledger (user_id, type, source, period_id, expiry) " +
                "SELECT ?, 'free', 'grant_period', p.id, p.ends_at FROM period p, generate_series(1, ?) " +
                "WHERE p.status = 'ACTIVE' AND NOT EXISTS (" +
                "SELECT 1 FROM life_ledger l WHERE l.user_id = ? AND l.period_id = p.id AND l.source = 'grant_period')",
        ).params(userId, LIVES_PER_PERIOD, userId).update()
    }

    // Resolusi Firebase UID → id pemain. Baris dibuat saat pertama terlihat, sama seperti jalur
    // `game` (ADR-0030: casual tanpa login, akun lahir saat pemain menyentuh fitur online).
    // ponytail: 4 baris duplikat dengan `game`. Modul `user` bersama baru berbayar kalau ada modul
    // ketiga yang butuh — T-024/T-025 memanggil lewat LifeService, bukan mengulang ini.
    @Transactional
    fun userIdOf(firebaseUid: String): Long {
        jdbc.sql("INSERT INTO app_user (firebase_uid) VALUES (?) ON CONFLICT (firebase_uid) DO NOTHING")
            .param(firebaseUid).update()
        return jdbc.sql("SELECT id FROM app_user WHERE firebase_uid = ?")
            .param(firebaseUid).query(Long::class.java).single()
    }

    private companion object {
        const val LIVES_PER_PERIOD = 2 // GDD §7.2; tunable → admin-config saat T-026 memegang periode.
    }
}

// Saldo nyawa aktif. `nextExpiry` = kedaluwarsa terdekat (null bila semua paid/carry-over).
data class Wallet(val free: Int, val paid: Int, val nextExpiry: Instant?)
