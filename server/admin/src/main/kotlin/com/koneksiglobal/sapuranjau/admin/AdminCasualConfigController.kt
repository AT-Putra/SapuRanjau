package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// Parameter earn nyawa casual dari panel (ADR-0023 memutuskan "admin-config", ADR-0045 memutuskan
// bentuknya; migrasi V24). Sebelum ini menyetel ekonomi nyawa berarti deploy ulang.
//
// Peran: **`admin` saja.** Ini bukan operasi harian seperti level/periode — ia menggeser ekonomi
// seluruh pemain sekaligus, dan menurunkan cap menyentuh lantai legal `01_GDD.md` §9.5 (jalur
// gratis harus memadai agar bayar = kenyamanan, bukan keharusan).
@RestController
class AdminCasualConfigController(
    private val jdbc: JdbcClient,
    private val audit: AuditService,
) {

    data class CasualConfigDto(
        val id: String,
        val rewardLives: Int,
        val capDaily: Int,
        val capWeekly: Int,
        val capMonthly: Int,
        val minMines: Int,
        val minDensity: Double,
        val updatedAt: Instant,
        val updatedBy: String?,
    )

    data class UpdateRequest(
        val rewardLives: Int = 1,
        val capDaily: Int = 1,
        val capWeekly: Int = 5,
        val capMonthly: Int = 10,
        val minMines: Int = 40,
        val minDensity: Double = 0.15,
    )

    // Satu baris, tapi tetap dilayani sebagai daftar supaya react-admin bisa memakainya tanpa
    // halaman khusus — `Content-Range` menyebut 1 dari 1.
    @GetMapping("/casual-config")
    fun list(principal: AdminPrincipal): ResponseEntity<List<CasualConfigDto>> {
        principal.require(AdminRole.ADMIN)
        val isi = listOf(baca())
        return ResponseEntity.ok().header("Content-Range", "casual-config 0-0/1").body(isi)
    }

    @GetMapping("/casual-config/{id}")
    fun one(principal: AdminPrincipal, @PathVariable id: Long): CasualConfigDto {
        principal.require(AdminRole.ADMIN)
        if (id != 1L) throw ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Konfigurasi earn casual hanya punya satu baris (id=1).")
        return baca()
    }

    @PutMapping("/casual-config/{id}")
    fun update(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: UpdateRequest): CasualConfigDto {
        principal.require(AdminRole.ADMIN)
        if (id != 1L) throw ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Konfigurasi earn casual hanya punya satu baris (id=1).")
        cek(body)
        val lama = baca()

        jdbc.sql(
            """
            UPDATE casual_earn_config SET reward_lives = ?, cap_daily = ?, cap_weekly = ?, cap_monthly = ?,
                   min_mines = ?, min_density = ?, updated_at = now(), updated_by = ? WHERE id = 1
            """,
        ).params(
            body.rewardLives,
            body.capDaily,
            body.capWeekly,
            body.capMonthly,
            body.minMines,
            body.minDensity,
            principal.id,
        ).update()

        // Audit memuat NILAI LAMA dan BARU: angka ekonomi yang berubah diam-diam adalah pertanyaan
        // pertama saat pemain protes "kok jatah saya berkurang". Ini angka, bukan PII — aman dicatat.
        audit.record(
            Actor.ADMIN,
            principal.id,
            "casual_config_updated",
            "casual_earn_config:1",
            mapOf(
                "dari" to mapOf(
                    "rewardLives" to lama.rewardLives,
                    "capDaily" to lama.capDaily,
                    "capWeekly" to lama.capWeekly,
                    "capMonthly" to lama.capMonthly,
                    "minMines" to lama.minMines,
                    "minDensity" to lama.minDensity,
                ),
                "jadi" to mapOf(
                    "rewardLives" to body.rewardLives,
                    "capDaily" to body.capDaily,
                    "capWeekly" to body.capWeekly,
                    "capMonthly" to body.capMonthly,
                    "minMines" to body.minMines,
                    "minDensity" to body.minDensity,
                ),
            ),
        )
        return baca()
    }

    // Divalidasi di sini SUPAYA pesannya bisa dibaca manusia. CHECK di basis data tetap ada dan
    // tetap yang terakhir menahan — ia menjaga tabel ini dari penyunting selain panel.
    private fun cek(b: UpdateRequest) {
        fun bad(pesan: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, pesan)
        if (b.rewardLives !in 1..3) bad("Reward per kemenangan harus 1–3 nyawa.")
        if (b.capDaily < 1 || b.capWeekly < 1 || b.capMonthly < 1) bad("Cap minimal 1 — cap 0 mematikan jalur nyawa gratis (GDD §9.5).")
        if (b.capWeekly < b.capDaily || b.capMonthly < b.capWeekly) {
            bad("Cap harus menaik: harian ≤ mingguan ≤ bulanan. Yang lebih kecil akan selalu menang dan sisanya jadi hiasan.")
        }
        if (b.minMines < 1) bad("Ambang jumlah bom minimal 1.")
        if (b.minDensity <= 0.0 || b.minDensity > 0.30) {
            bad("Ambang density harus di antara 0 dan 0,30 — di atas itu papan tak dijamin bisa dibuat no-guess (ADR-0031).")
        }
    }

    private fun baca(): CasualConfigDto =
        jdbc.sql("SELECT * FROM casual_earn_config WHERE id = 1").query { rs, _ ->
            CasualConfigDto(
                rs.getString("id"),
                rs.getInt("reward_lives"),
                rs.getInt("cap_daily"),
                rs.getInt("cap_weekly"),
                rs.getInt("cap_monthly"),
                rs.getInt("min_mines"),
                rs.getBigDecimal("min_density").toDouble(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"),
            )
        }.optional().orElseThrow {
            ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Baris konfigurasi earn casual tak ada (migrasi V24 belum jalan?).")
        }
}
