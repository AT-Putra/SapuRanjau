package com.koneksiglobal.sapuranjau.lives

import com.koneksiglobal.sapuranjau.engine.CellIndex
import com.koneksiglobal.sapuranjau.engine.LevelConfig
import com.koneksiglobal.sapuranjau.engine.MinesweeperEngine
import com.koneksiglobal.sapuranjau.engine.RevealResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootApplication(scanBasePackages = ["com.koneksiglobal.sapuranjau"])
class LivesTestApp

// Bukti runtime T-024 di Postgres 18 asli: earn nyawa casual (ADR-0023) — re-simulasi (seed, moves),
// ambang kesulitan, cap jendela-tetap, dan sinyal anomali.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CasualClaimTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcClient

    private val engine = MinesweeperEngine()

    // Ambang default = intermediate klasik 16×16/40 (15,6%); `mudah` sengaja di bawahnya.
    private val medium = LevelConfig(gridWidth = 16, gridHeight = 16, mineCount = 40)
    private val mudah = LevelConfig(gridWidth = 9, gridHeight = 9, mineCount = 10)
    private val first = CellIndex(0, 0)

    private data class Resp(val status: Int, val body: String?)

    @BeforeEach
    fun seedPeriode() {
        jdbc.sql("TRUNCATE audit_event, life_ledger, app_user, period RESTART IDENTITY CASCADE").update()
        jdbc.sql(
            "INSERT INTO period (name, starts_at, ends_at, status) " +
                "VALUES ('test', now(), now() + interval '30 days', 'ACTIVE')",
        ).update()
    }

    // ── Helper ───────────────────────────────────────────────────────────────────────────────────

    private val client get() = RestClient.create("http://localhost:$port")

    private fun claim(req: CasualClaimRequest, uid: String = "pemain-1"): CasualClaimResponse =
        client.post().uri("/v1/casual/claim")
            .header("Authorization", "Bearer dev:$uid")
            .body(req).retrieve().body(CasualClaimResponse::class.java)!!

    private fun claimRaw(req: CasualClaimRequest, uid: String = "pemain-1"): Resp =
        client.post().uri("/v1/casual/claim")
            .header("Authorization", "Bearer dev:$uid")
            .body(req)
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    private fun allCells(c: LevelConfig) =
        (0 until c.gridHeight).flatMap { y -> (0 until c.gridWidth).map { x -> CellIndex(x, y) } }

    // Peta bom dihitung lewat API publik engine (reveal pada bom = HitMine, tak mengubah papan).
    private fun minesOf(c: LevelConfig, seed: Long): Set<CellIndex> {
        val probe = engine.generate(c, seed, first)
        return allCells(c).filter { engine.reveal(probe, it) is RevealResult.HitMine }.toSet()
    }

    // Replay MENANG: buka semua sel aman sampai engine sendiri bilang LevelCleared. Sengaja bukan
    // jalur optimal → tak memicu sinyal `perfect_path`.
    private fun replayMenang(c: LevelConfig, seed: Long): List<CasualMove> {
        val mines = minesOf(c, seed)
        val board = engine.generate(c, seed, first)
        val out = mutableListOf(CasualMove(CasualAction.REVEAL, first.x, first.y))
        if (engine.reveal(board, first) is RevealResult.LevelCleared) return out
        for (cell in allCells(c)) {
            if (cell in mines || cell == first) continue
            val r = engine.reveal(board, cell)
            out += CasualMove(CasualAction.REVEAL, cell.x, cell.y)
            if (r is RevealResult.LevelCleared) break
        }
        return out
    }

    private fun permintaan(
        c: LevelConfig = medium,
        seed: Long = 42,
        moves: List<CasualMove> = replayMenang(c, seed),
        elapsedMs: Long = 300_000, // 5 menit — wajar utk 16×16, tak memicu `too_fast`
    ) = CasualClaimRequest(c.gridWidth, c.gridHeight, c.mineCount, seed, moves, elapsedMs)

    private fun nyawa(source: String): Int = jdbc.sql("SELECT count(*) FROM life_ledger WHERE source = ?")
        .param(source).query(Long::class.java).single().toInt()

    private fun auditCount(): Int = jdbc.sql("SELECT count(*) FROM audit_event").query(Long::class.java).single().toInt()

    // ── Test ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `menang casual di atas ambang memberi 1 FreeLife terikat periode`() {
        val r = claim(permintaan())
        assertEquals(ClaimResult.GRANTED, r.result)
        assertEquals(3, r.free, "2 FreeLife jatah periode + 1 hasil earn")
        assertEquals(0, r.paid)

        val row = jdbc.sql("SELECT type, expiry, period_id FROM life_ledger WHERE source = 'earn_casual'")
            .query().singleRow()
        assertEquals("free", row["type"])
        assertTrue(row["expiry"] != null, "FreeLife earn hangus di akhir periode (ADR-0008)")
        assertTrue(row["period_id"] != null, "terikat periode aktif")
        assertEquals(0, auditCount(), "permainan wajar tak menghasilkan flag anomali")
    }

    @Test
    fun `cap harian menolak klaim kedua di hari yang sama`() {
        assertEquals(ClaimResult.GRANTED, claim(permintaan()).result)

        val kedua = claim(permintaan(seed = 7))
        assertEquals(ClaimResult.CAP_DAILY, kedua.result)
        assertEquals(3, kedua.free, "tak ada nyawa tambahan")
        assertEquals(1, nyawa("earn_casual"))
    }

    // Cap harian juga yang membuat klaim ulang payload yang sama tak menggandakan nyawa —
    // tak perlu tabel dedupe terpisah (ADR-0023: 1/hari sudah mengikat).
    @Test
    fun `klaim ulang payload yang sama tak menggandakan nyawa`() {
        val req = permintaan()
        assertEquals(ClaimResult.GRANTED, claim(req).result)
        assertEquals(ClaimResult.CAP_DAILY, claim(req).result)
        assertEquals(1, nyawa("earn_casual"))
    }

    @Test
    fun `papan di bawah ambang kesulitan tak memberi nyawa`() {
        val r = claim(permintaan(c = mudah))
        assertEquals(ClaimResult.BELOW_THRESHOLD, r.result)
        assertEquals(0, nyawa("earn_casual"))
    }

    @Test
    fun `tanpa periode aktif tak ada nyawa yang dicetak`() {
        jdbc.sql("UPDATE period SET status = 'ENDED'").update()
        val r = claim(permintaan())
        assertEquals(ClaimResult.NO_ACTIVE_PERIOD, r.result)
        assertEquals(0, nyawa("earn_casual"))
        assertEquals(0, nyawa("grant_period"), "jatah periode pun tak terbit tanpa periode aktif")
    }

    @Test
    fun `replay yang kalah ditolak 400`() {
        val mine = minesOf(medium, 42).first()
        val moves = listOf(
            CasualMove(CasualAction.REVEAL, first.x, first.y),
            CasualMove(CasualAction.REVEAL, mine.x, mine.y),
        )
        val r = claimRaw(permintaan(moves = moves))
        assertEquals(400, r.status, "body: ${r.body}")
        assertTrue(r.body!!.contains("\"code\":\"VALIDATION\""), "body: ${r.body}")
        assertEquals(0, nyawa("earn_casual"))
    }

    @Test
    fun `replay yang tak menuntaskan level ditolak 400`() {
        val moves = listOf(CasualMove(CasualAction.REVEAL, first.x, first.y))
        assertEquals(400, claimRaw(permintaan(moves = moves)).status)
        assertEquals(0, nyawa("earn_casual"))
    }

    // Klaim datang dari klien tak tepercaya dan memicu generate+solve — papan raksasa/density
    // mustahil harus ditolak SEBELUM generator diputar (DoS murah).
    @Test
    fun `papan di luar batas aman ditolak 400 tanpa menjalankan generator`() {
        val raksasa = CasualClaimRequest(200, 200, 8000, 1, listOf(CasualMove(CasualAction.REVEAL, 0, 0)), 1000)
        assertEquals(400, claimRaw(raksasa).status)

        val padat = CasualClaimRequest(16, 16, 200, 1, listOf(CasualMove(CasualAction.REVEAL, 0, 0)), 1000)
        assertEquals(400, claimRaw(padat).status, "density 78% — tak bisa dijamin no-guess")
    }

    @Test
    fun `permainan mustahil-cepat ditandai AuditEvent tapi nyawanya tetap diberi`() {
        val r = claim(permintaan(elapsedMs = 0))
        assertEquals(ClaimResult.GRANTED, r.result, "menandai, bukan memblokir (ADR-0023)")

        val row = jdbc.sql("SELECT actor_type, actor_id, event_type, detail FROM audit_event").query().singleRow()
        // Flag anomali = pengamatan server, bukan tindakan pemain (T-027); pemainnya di `actor_id`.
        assertEquals("system", row["actor_type"])
        assertEquals("casual_claim_anomaly", row["event_type"])
        assertTrue(row["actor_id"] != null)
        assertTrue(row["detail"].toString().contains("too_fast"), "detail: ${row["detail"]}")
    }

    @Test
    fun `klaim tanpa bearer ditolak 401`() {
        val r = client.post().uri("/v1/casual/claim")
            .header("Content-Type", "application/json").body("{}")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
        assertEquals(401, r.status, "body: ${r.body}")
    }
}
