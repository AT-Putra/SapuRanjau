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
        lockUser(userId)
        jdbc.sql(
            "INSERT INTO life_ledger (user_id, type, source, period_id, expiry) " +
                "SELECT ?, 'free', 'grant_period', p.id, p.ends_at FROM period p, generate_series(1, ?) " +
                "WHERE p.status = 'ACTIVE' AND NOT EXISTS (" +
                "SELECT 1 FROM life_ledger l WHERE l.user_id = ? AND l.period_id = p.id AND l.source = 'grant_period')",
        ).params(userId, LIVES_PER_PERIOD, userId).update()
    }

    // Serialisasi per-pemain untuk semua jalur pencetakan nyawa: dua request bersamaan tak boleh
    // lolos cek yang sama lalu sama-sama mencetak. Lock baris `app_user` (bukan tabel) → hanya
    // pemain ini yang menunggu. Dipakai grant periode & klaim casual (T-024).
    @Transactional
    fun lockUser(userId: Long) {
        jdbc.sql("SELECT id FROM app_user WHERE id = ? FOR UPDATE").param(userId).query(Long::class.java).optional()
    }

    // 1 FreeLife hasil menang casual (ADR-0023) — terikat periode aktif & hangus bersamanya.
    // false = tak ada periode `ACTIVE` → tak ada yang dicetak (earn hanya saat periode berjalan).
    // Cap-nya BUKAN urusan sini: kebijakan earn ada di CasualClaimService.
    // `count` = reward per kemenangan, kini admin-config (ADR-0045); default 1 = angka ADR-0023.
    @Transactional
    fun grantEarnedCasual(userId: Long, count: Int = 1): Boolean {
        require(count > 0) { "reward nyawa harus > 0" }
        // Satu INSERT ber-`generate_series`: reward > 1 tetap satu perjalanan ke DB, dan tetap
        // satu baris per token nyawa (ADR-0008) supaya clawback/expiry bekerja per-token.
        return jdbc.sql(
            "INSERT INTO life_ledger (user_id, type, source, period_id, expiry) " +
                "SELECT ?, 'free', 'earn_casual', p.id, p.ends_at FROM period p, generate_series(1, ?) " +
                "WHERE p.status = 'ACTIVE'",
        ).params(userId, count).update() == count
    }

    // PaidLife hasil pembelian terverifikasi (ADR-0011/0022) — `expiry` NULL = carry-over lintas
    // periode (ADR-0008). Satu baris per token nyawa supaya clawback bisa menyasar sisa yang belum
    // terpakai. Dipanggil `server/billing` SETELAH Play mengonfirmasi purchase-nya.
    @Transactional
    fun grantPaid(userId: Long, purchaseId: Long, count: Int) {
        require(count > 0) { "count nyawa harus > 0" }
        jdbc.sql(
            "INSERT INTO life_ledger (user_id, type, source, purchase_id) " +
                "SELECT ?, 'paid', 'purchase', ? FROM generate_series(1, ?)",
        ).params(userId, purchaseId, count).update()
    }

    // Sapuan nyawa yang jatuh tempo — FreeLife terikat periode hangus bersama periodenya (ADR-0008,
    // `expiry = period.ends_at`). Dipanggil rollover periode (T-026). Ini HIGIENE, bukan koreksi
    // kebenaran: semua pembacaan sudah menyaring `expiry > now()`. Gunanya menjaga partial index
    // `life_available` tetap kecil di VM kecil (ADR-0015/0020).
    @Transactional
    fun expireLapsed(): Int =
        jdbc.sql("UPDATE life_ledger SET status = 'expired' WHERE status = 'available' AND expiry <= now()").update()

    // Clawback saat purchase di-void (ADR-0025): cabut nyawa dari purchase itu yang MASIH tersisa.
    // Yang sudah dipakai tetap `used` — tak ada saldo minus, lantai 0 datang dari filter `available`
    // ini sendiri, bukan dari aritmetika. Balas jumlah yang tercabut (0 = sudah habis dipakai).
    @Transactional
    fun clawbackPurchase(purchaseId: Long): Int =
        jdbc.sql("UPDATE life_ledger SET status = 'clawed_back' WHERE purchase_id = ? AND status = 'available'")
            .param(purchaseId).update()

    // Hitungan earn casual pada jendela reset TETAP (kalender), bukan rolling window — ADR-0023.
    // Batas hari/minggu/bulan dihitung di zona waktu pemain (Indonesia), bukan UTC: pemain melihat
    // jatah hariannya pulih tengah malam waktu setempat. Satu query, tiga hitungan.
    fun earnedCasualCounts(userId: Long, zone: String): EarnCounts =
        jdbc.sql(
            "SELECT count(*) FILTER (WHERE created_at >= date_trunc('day',   now() AT TIME ZONE :tz) AT TIME ZONE :tz) AS d, " +
                "count(*) FILTER (WHERE created_at >= date_trunc('week',  now() AT TIME ZONE :tz) AT TIME ZONE :tz) AS w, " +
                "count(*) FILTER (WHERE created_at >= date_trunc('month', now() AT TIME ZONE :tz) AT TIME ZONE :tz) AS m " +
                "FROM life_ledger WHERE user_id = :uid AND source = 'earn_casual'",
        ).param("uid", userId).param("tz", zone).query { rs, _ ->
            EarnCounts(rs.getInt("d"), rs.getInt("w"), rs.getInt("m"))
        }.single()

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
        const val LIVES_PER_PERIOD = 2 // GDD §7.2; tunable → admin-config saat panel ada (T-042).
    }
}

// Saldo nyawa aktif. `nextExpiry` = kedaluwarsa terdekat (null bila semua paid/carry-over).
data class Wallet(val free: Int, val paid: Int, val nextExpiry: Instant?)

// Nyawa hasil earn casual dalam jendela hari/minggu/bulan berjalan (ADR-0023).
data class EarnCounts(val daily: Int, val weekly: Int, val monthly: Int)
