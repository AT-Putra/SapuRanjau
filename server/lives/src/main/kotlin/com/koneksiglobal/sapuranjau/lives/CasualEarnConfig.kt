package com.koneksiglobal.sapuranjau.lives

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

// Parameter earn nyawa casual (ADR-0023 memutuskan "admin-config"; ADR-0045 memutuskan bentuknya).
// Satu baris di `casual_earn_config` — invariannya (cap menaik, density ≤ 30%) ditegakkan CHECK di
// basis data, bukan cuma di kode: panel bukan satu-satunya yang bisa menyentuh tabel ini.
data class CasualEarnConfig(
    val rewardLives: Int,
    val capDaily: Int,
    val capWeekly: Int,
    val capMonthly: Int,
    val minMines: Int,
    val minDensity: Double,
)

@Repository
class CasualEarnConfigRepo(private val jdbc: JdbcClient) {

    // Barisnya disisipkan migrasi V24 dan `CHECK (id = 1)` membuat baris kedua mustahil — kalau ia
    // tetap hilang, itu basis data yang dirusak tangan, dan menebak angka ekonomi diam-diam jauh
    // lebih buruk daripada berhenti.
    fun get(): CasualEarnConfig =
        jdbc.sql("SELECT reward_lives, cap_daily, cap_weekly, cap_monthly, min_mines, min_density FROM casual_earn_config WHERE id = 1")
            .query { rs, _ ->
                CasualEarnConfig(
                    rs.getInt("reward_lives"),
                    rs.getInt("cap_daily"),
                    rs.getInt("cap_weekly"),
                    rs.getInt("cap_monthly"),
                    rs.getInt("min_mines"),
                    rs.getBigDecimal("min_density").toDouble(),
                )
            }
            .optional()
            .orElseThrow { IllegalStateException("Baris casual_earn_config (id=1) tak ada — migrasi V24 belum jalan?") }
}
