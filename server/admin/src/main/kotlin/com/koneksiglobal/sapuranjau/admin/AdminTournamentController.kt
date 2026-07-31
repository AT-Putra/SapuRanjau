package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import com.koneksiglobal.sapuranjau.tournament.PeriodService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4
import java.time.Instant
import java.time.OffsetDateTime

// Konfigurasi turnamen dari panel (T-042): periode, level, hadiah. Controller ini SENGAJA tipis —
// aturan periode (tumpang-tindih, satu aktif, tutup lebih awal) sudah hidup di `PeriodService`, dan
// menyalinnya ke lapisan HTTP berarti dua tempat yang suatu hari akan berbeda pendapat.
//
// Peran: `admin` & `moderator` boleh mengubah (operasi harian, ARCH §10); `finance` hanya membaca —
// ia butuh melihat periode untuk menangani klaim, bukan untuk menyetel permainan.
@RestController
class AdminTournamentController(
    private val jdbc: JdbcClient,
    private val periods: PeriodService,
    private val ra: RaQuery,
    private val audit: AuditService,
    private val json: ObjectMapper,
) {

    // ── periods ─────────────────────────────────────────────────────────────────────────────────

    data class PeriodDto(
        val id: String,
        val name: String?,
        val startsAt: Instant,
        val endsAt: Instant,
        val status: String,
        val levelCount: Int, // dipakai layar untuk memperingatkan periode ≫ estimasi habis-level (ADR-0024)
        val hasPrizeConfig: Boolean, // tanpa ini periode berakhir TANPA pemenang (WinnerService)
        val winnerCount: Int,
    )

    data class PeriodRequest(val name: String? = null, val startsAt: String = "", val endsAt: String = "")

    @GetMapping("/periods")
    fun periods(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
    ): ResponseEntity<List<PeriodDto>> {
        val h = ra.page(range)
        val s = ra.sort(sort, PERIOD_SORT, default = "starts_at")
        val isi = jdbc.sql("$PERIOD_SELECT ORDER BY p.${s.column} ${s.arah} OFFSET ? LIMIT ?")
            .params(h.offset, h.limit).query(::periodDto).list()
        return ResponseEntity.ok()
            .header("Content-Range", h.contentRange("periods", isi.size, hitung("period")))
            .body(isi)
    }

    @GetMapping("/periods/{id}")
    fun period(@PathVariable id: Long): PeriodDto = satuPeriode(id)

    @PostMapping("/periods")
    fun createPeriod(principal: AdminPrincipal, @RequestBody body: PeriodRequest): PeriodDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        val id = periods.create(body.name?.trim()?.ifBlank { null }, waktu(body.startsAt, "startsAt"), waktu(body.endsAt, "endsAt"))
        audit.record(Actor.ADMIN, principal.id, "period_created", "period:$id", mapOf("name" to body.name))
        return satuPeriode(id)
    }

    @PutMapping("/periods/{id}")
    fun updatePeriod(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: PeriodRequest): PeriodDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        periods.update(id, body.name?.trim()?.ifBlank { null }, waktu(body.startsAt, "startsAt"), waktu(body.endsAt, "endsAt"))
        audit.record(Actor.ADMIN, principal.id, "period_updated", "period:$id", mapOf("startsAt" to body.startsAt, "endsAt" to body.endsAt))
        return satuPeriode(id)
    }

    // Tutup lebih awal = aksi, bukan `PUT` resource: ia memicu finalisasi pemenang, penghangusan
    // nyawa, dan pengangkatan periode berikutnya (rollover). Menyembunyikan itu di balik edit
    // tanggal akan membuat satu klik keliru menghasilkan daftar pemenang final.
    @PostMapping("/periods/{id}/close")
    fun closePeriod(principal: AdminPrincipal, @PathVariable id: Long): PeriodDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        periods.closeNow(id)
        audit.record(Actor.ADMIN, principal.id, "period_closed_early", "period:$id")
        return satuPeriode(id)
    }

    // ── levels (level_config) ───────────────────────────────────────────────────────────────────

    data class LevelDto(
        val id: String,
        val periodId: String,
        val levelIndex: Int,
        val gridWidth: Int,
        val gridHeight: Int,
        val mineCount: Int,
        val baseScore: Int,
        val lifeCap: Int,
    )

    // PERINGATAN yang berlaku untuk SEMUA request DTO di server ini: nilai default Kotlin di bawah
    // TIDAK dipakai Jackson — `jackson-module-kotlin` tak ada di classpath, jadi field non-null yang
    // hilang dari JSON dibalas 400, bukan diisi default. Defaultnya cuma untuk pemanggil Kotlin.
    // Field yang benar-benar boleh dikosongkan harus bertipe NULLABLE (pola `PrizeClaimRequest`).
    data class LevelRequest(
        val periodId: String = "",
        val levelIndex: Int = 0,
        val gridWidth: Int = 9,
        val gridHeight: Int = 9,
        val mineCount: Int = 10,
        val baseScore: Int = 1000,
        val lifeCap: Int = 2,
    )

    @GetMapping("/levels")
    fun levels(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<LevelDto>> {
        val h = ra.page(range)
        val s = ra.sort(sort, LEVEL_SORT, default = "level_index")
        val periodId = ra.filter(filter)["periodId"]?.toLongOrNull()
        val where = if (periodId == null) "" else "WHERE period_id = $periodId" // nilai sudah jadi Long, bukan teks klien
        val isi = jdbc.sql("SELECT * FROM level_config $where ORDER BY ${s.column} ${s.arah} OFFSET ? LIMIT ?")
            .params(h.offset, h.limit).query(::levelDto).list()
        val total = if (periodId == null) hitung("level_config") else {
            jdbc.sql("SELECT count(*) FROM level_config WHERE period_id = ?").param(periodId).query(Long::class.java).single()
        }
        return ResponseEntity.ok().header("Content-Range", h.contentRange("levels", isi.size, total)).body(isi)
    }

    @GetMapping("/levels/{id}")
    fun level(@PathVariable id: Long): LevelDto = satuLevel(id)

    @PostMapping("/levels")
    fun createLevel(principal: AdminPrincipal, @RequestBody body: LevelRequest): LevelDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        cekLevel(body)
        val id = jdbc.sql(
            """
            INSERT INTO level_config (period_id, level_index, grid_width, grid_height, mine_count, base_score, life_cap)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
            """,
        ).params(
            body.periodId.toLongOrNull() ?: bad("periodId tak sah."), body.levelIndex, body.gridWidth,
            body.gridHeight, body.mineCount, body.baseScore, body.lifeCap,
        ).query(Long::class.java).single()
        audit.record(Actor.ADMIN, principal.id, "level_created", "level_config:$id", mapOf("periodId" to body.periodId, "index" to body.levelIndex))
        return satuLevel(id)
    }

    @PutMapping("/levels/{id}")
    fun updateLevel(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: LevelRequest): LevelDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        cekLevel(body)
        // Level yang SUDAH DIMAINKAN tak boleh berubah bentuknya: papan yang beredar dibuat dari
        // `(config, seed, klik pertama)` (ADR-0031) dan skornya di-re-sim dari konfigurasi ini
        // (ADR-0017). Menggeser grid/bom di tengah jalan membuat re-sim menolak permainan yang sah.
        if (jdbc.sql("SELECT 1 FROM board WHERE level_config_id = ? LIMIT 1").param(id).query(Int::class.java).optional().isPresent) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Level ini sudah dimainkan — bentuknya tak bisa diubah lagi.")
        }
        jdbc.sql(
            """
            UPDATE level_config SET level_index = ?, grid_width = ?, grid_height = ?, mine_count = ?,
                   base_score = ?, life_cap = ? WHERE id = ?
            """,
        ).params(body.levelIndex, body.gridWidth, body.gridHeight, body.mineCount, body.baseScore, body.lifeCap, id).update()
        audit.record(Actor.ADMIN, principal.id, "level_updated", "level_config:$id")
        return satuLevel(id)
    }

    @DeleteMapping("/levels/{id}")
    fun deleteLevel(principal: AdminPrincipal, @PathVariable id: Long): LevelDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        val level = satuLevel(id)
        val status = jdbc.sql("SELECT status FROM period WHERE id = ?").param(level.periodId.toLong())
            .query(String::class.java).single()
        if (status != "UPCOMING") {
            // FK `board.level_config_id` sudah menjaga level yang benar-benar dipakai; pagar ini
            // memberi alasan yang bisa dibaca manusia sebelum error constraint muncul.
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Level hanya bisa dihapus selama periodenya belum berjalan.")
        }
        jdbc.sql("DELETE FROM level_config WHERE id = ?").param(id).update()
        audit.record(Actor.ADMIN, principal.id, "level_deleted", "level_config:$id", mapOf("periodId" to level.periodId, "index" to level.levelIndex))
        return level // react-admin menuntut record yang dihapus dikembalikan
    }

    // ── prizes (prize_config) ───────────────────────────────────────────────────────────────────

    data class PrizeDto(val id: String, val periodId: String, val winnersCount: Int, val prizes: List<String>)
    data class PrizeRequest(val periodId: String = "", val winnersCount: Int = 3, val prizes: List<String> = emptyList())

    @GetMapping("/prizes")
    fun prizes(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<PrizeDto>> {
        val h = ra.page(range)
        val periodId = ra.filter(filter)["periodId"]?.toLongOrNull()
        val where = if (periodId == null) "" else "WHERE period_id = $periodId"
        val isi = jdbc.sql("SELECT * FROM prize_config $where ORDER BY period_id DESC OFFSET ? LIMIT ?")
            .params(h.offset, h.limit).query(::prizeDto).list()
        return ResponseEntity.ok()
            .header("Content-Range", h.contentRange("prizes", isi.size, hitung("prize_config")))
            .body(isi)
    }

    @GetMapping("/prizes/{id}")
    fun prize(@PathVariable id: Long): PrizeDto = satuPrize(id)

    @PostMapping("/prizes")
    fun createPrize(principal: AdminPrincipal, @RequestBody body: PrizeRequest): PrizeDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        cekPrize(body)
        val id = jdbc.sql("INSERT INTO prize_config (period_id, winners_count, prizes) VALUES (?, ?, ?::jsonb) RETURNING id")
            .params(body.periodId.toLongOrNull() ?: bad("periodId tak sah."), body.winnersCount, json.writeValueAsString(body.prizes))
            .query(Long::class.java).single()
        audit.record(Actor.ADMIN, principal.id, "prize_config_saved", "period:${body.periodId}", mapOf("winnersCount" to body.winnersCount))
        return satuPrize(id)
    }

    @PutMapping("/prizes/{id}")
    fun updatePrize(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: PrizeRequest): PrizeDto {
        principal.require(AdminRole.ADMIN, AdminRole.MODERATOR)
        cekPrize(body)
        val periodId = satuPrize(id).periodId
        // Hadiah periode yang pemenangnya sudah dipilih tak boleh berubah: daftar pemenang dibuat
        // dari `winners_count` (WinnerService.finalizePeriod), jadi mengubahnya sesudahnya berarti
        // janji hadiah yang tak cocok dengan siapa pun yang sudah diberi tahu.
        if (jdbc.sql("SELECT 1 FROM winner WHERE period_id = ? LIMIT 1").param(periodId.toLong()).query(Int::class.java).optional().isPresent) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Pemenang periode ini sudah final — konfigurasi hadiah tak bisa diubah.")
        }
        jdbc.sql("UPDATE prize_config SET winners_count = ?, prizes = ?::jsonb WHERE id = ?")
            .params(body.winnersCount, json.writeValueAsString(body.prizes), id).update()
        audit.record(Actor.ADMIN, principal.id, "prize_config_saved", "period:$periodId", mapOf("winnersCount" to body.winnersCount))
        return satuPrize(id)
    }

    // ── helper ──────────────────────────────────────────────────────────────────────────────────

    private fun cekLevel(body: LevelRequest) {
        if (body.levelIndex < 0) bad("levelIndex tak boleh negatif.")
        if (body.gridWidth !in 5..64 || body.gridHeight !in 5..64) bad("Grid di luar batas 5..64.")
        // Batas yang sama dengan pagar re-sim casual (T-024) supaya papan turnamen tak pernah lebih
        // besar dari yang bisa diverifikasi ulang server.
        if (body.gridWidth * body.gridHeight > 1024) bad("Grid melebihi 1024 sel.")
        // Generator no-guess butuh ruang: papan yang hampir seluruhnya bom tak punya pembukaan aman
        // (ADR-0031), dan yang nyaris tanpa bom bukan permainan.
        val sel = body.gridWidth * body.gridHeight
        if (body.mineCount < 1 || body.mineCount > sel * 30 / 100) bad("Jumlah bom harus 1..30% dari jumlah sel (batas kelayakan no-guess, ADR-0031).")
        if (body.baseScore < 1) bad("baseScore minimal 1.")
        if (body.lifeCap < 0) bad("lifeCap tak boleh negatif.")
    }

    private fun cekPrize(body: PrizeRequest) {
        if (body.winnersCount !in 3..10) bad("Jumlah pemenang harus 3..10 (ADR-0021).")
        // Satu hadiah per peringkat: daftar yang lebih pendek meninggalkan peringkat tanpa jawaban
        // atas "saya juara berapa, dapat apa" — dan itu ditanyakan pemain, bukan admin.
        if (body.prizes.size != body.winnersCount) bad("Daftar hadiah harus berisi ${body.winnersCount} baris, satu per peringkat.")
        if (body.prizes.any { it.isBlank() }) bad("Hadiah tiap peringkat wajib diisi.")
    }

    private fun satuPeriode(id: Long): PeriodDto =
        jdbc.sql("$PERIOD_SELECT WHERE p.id = ?").param(id).query(::periodDto).optional()
            .orElseThrow { tidakAda("Periode", id) }

    private fun satuLevel(id: Long): LevelDto =
        jdbc.sql("SELECT * FROM level_config WHERE id = ?").param(id).query(::levelDto).optional()
            .orElseThrow { tidakAda("Level", id) }

    private fun satuPrize(id: Long): PrizeDto =
        jdbc.sql("SELECT * FROM prize_config WHERE id = ?").param(id).query(::prizeDto).optional()
            .orElseThrow { tidakAda("Konfigurasi hadiah", id) }

    private fun periodDto(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = PeriodDto(
        rs.getString("id"), rs.getString("name"),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
        rs.getString("status"), rs.getInt("level_count"), rs.getBoolean("has_prize"),
        rs.getInt("winner_count"),
    )

    private fun levelDto(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = LevelDto(
        rs.getString("id"), rs.getString("period_id"), rs.getInt("level_index"),
        rs.getInt("grid_width"), rs.getInt("grid_height"), rs.getInt("mine_count"),
        rs.getInt("base_score"), rs.getInt("life_cap"),
    )

    private fun prizeDto(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = PrizeDto(
        rs.getString("id"), rs.getString("period_id"), rs.getInt("winners_count"),
        json.readValue(rs.getString("prizes"), Array<String>::class.java).toList(),
    )

    private fun hitung(tabel: String): Long = jdbc.sql("SELECT count(*) FROM $tabel").query(Long::class.java).single()

    private fun waktu(nilai: String, field: String): Instant = runCatching { OffsetDateTime.parse(nilai).toInstant() }
        .recoverCatching { Instant.parse(nilai) }
        .getOrElse { bad("$field harus waktu ISO-8601 lengkap dengan zona (mis. 2026-08-01T00:00:00+07:00).") }

    private fun bad(pesan: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, pesan)

    private fun tidakAda(apa: String, id: Long) = ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "$apa $id tak ada.")

    private companion object {
        val PERIOD_SORT = setOf("id", "name", "starts_at", "ends_at", "status")
        val LEVEL_SORT = setOf("id", "level_index", "grid_width", "grid_height", "mine_count", "base_score")

        // Kolom turunan yang dipakai layar periode: berapa level sudah disiapkan (peringatan
        // ADR-0024 "periode ∝ jumlah level"), apakah hadiahnya sudah dikonfigurasi (tanpa itu
        // periode berakhir tanpa pemenang), dan berapa pemenang yang sudah final.
        const val PERIOD_SELECT = """
            SELECT p.id, p.name, p.starts_at, p.ends_at, p.status,
                   (SELECT count(*) FROM level_config l WHERE l.period_id = p.id) AS level_count,
                   EXISTS (SELECT 1 FROM prize_config c WHERE c.period_id = p.id) AS has_prize,
                   (SELECT count(*) FROM winner w WHERE w.period_id = p.id AND w.status = 'active') AS winner_count
              FROM period p
        """
    }
}
