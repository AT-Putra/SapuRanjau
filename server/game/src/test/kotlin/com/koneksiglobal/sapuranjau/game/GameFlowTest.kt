package com.koneksiglobal.sapuranjau.game

import com.koneksiglobal.sapuranjau.engine.CellIndex
import com.koneksiglobal.sapuranjau.engine.LevelConfig
import com.koneksiglobal.sapuranjau.engine.MinesweeperEngine
import com.koneksiglobal.sapuranjau.engine.RevealResult
import com.koneksiglobal.sapuranjau.lives.Wallet
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
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// App test-only utk mengangkat modul library `game` + edge HTTP `api` (keduanya bukan @SpringBootApplication).
// scanBasePackages = base → susunan bean identik server sungguhan (ServerApplication).
@SpringBootApplication(scanBasePackages = ["com.koneksiglobal.sapuranjau"])
class GameTestApp

// Bukti runtime T-022 di Postgres 18 asli: alur start → aksi → skor tercatat, durabilitas progres
// (ADR-0036: state selamat walau cache sesi hilang), one-shot (ADR-0024), provably-fair (ARCH §6.5).
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Tick periode dimatikan: rollover di latar tak boleh mengubah data di tengah test (T-026).
    properties = ["sapuranjau.tournament.tick.enabled=false", "sapuranjau.tournament.tnc-version=v1"],
)
class GameFlowTest {

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
    @Autowired private lateinit var game: GameService

    private val engine = MinesweeperEngine()
    private val cfg = LevelConfig(gridWidth = 5, gridHeight = 5, mineCount = 3) // kecil → generate cepat
    private val first = CellIndex(0, 0)

    private data class Resp(val status: Int, val body: String?)

    @BeforeEach
    fun seedPeriodDanLevel() {
        jdbc.sql(
            "TRUNCATE life_ledger, level_score, board, run, level_config, tournament_consent, " +
                "tournament_ban, audit_event, period, app_user RESTART IDENTITY CASCADE",
        ).update()
        val periodId = jdbc.sql(
            "INSERT INTO period (name, starts_at, ends_at, status) " +
                "VALUES ('test', now(), now() + interval '30 days', 'ACTIVE') RETURNING id",
        ).query(Long::class.java).single()
        repeat(2) { i -> // 2 level → uji `current_level` maju & `completed_all_at` (tie-breaker §8.2 #4)
            jdbc.sql(
                "INSERT INTO level_config (period_id, level_index, grid_width, grid_height, mine_count, base_score, life_cap) " +
                    "VALUES (?, ?, ?, ?, ?, 1000, 3)",
            ).params(periodId, i, cfg.gridWidth, cfg.gridHeight, cfg.mineCount).update()
        }
        game.evictAllSessions()
        // Gerbang S&K (ADR-0026, T-026) berlaku untuk SEMUA jalur turnamen: tanpa persetujuan,
        // `start` dibalas 403. Test alur permainan menyetujuinya di muka lewat endpoint sungguhan.
        listOf("pemain-1", "pemain-2").forEach { setujuiSK(it) }
    }

    // ── Helper HTTP ──────────────────────────────────────────────────────────────────────────────

    private val client get() = RestClient.create("http://localhost:$port")

    private fun setujuiSK(uid: String) {
        client.post().uri("/v1/tournament/consent").header("Authorization", "Bearer dev:$uid")
            .body(mapOf("tncVersion" to "v1")).retrieve().toBodilessEntity()
    }

    private fun start(uid: String = "pemain-1"): StartResponse = client.post().uri("/v1/tournament/level/start")
        .header("Authorization", "Bearer dev:$uid").retrieve().body(StartResponse::class.java)!!

    // Server mengundi seed via SecureRandom → tak deterministik. Test mengunci seed langsung di DB
    // (commit hash ikut disesuaikan) dan memilih seed yang klik-pertamanya TIDAK langsung menuntaskan
    // level (cascade penuh) — supaya alur aksi bisa diuji tanpa flaky.
    private fun startTerkunci(): StartResponse {
        val s = start()
        val seed = (1L..500L).first { engine.reveal(engine.generate(cfg, it, first), first) is RevealResult.Revealed }
        jdbc.sql("UPDATE board SET seed = ?, commit_hash = ? WHERE id = ?")
            .params(seed, hashOf(seed), s.boardId.toLong()).update()
        game.evictAllSessions()
        return start()
    }

    private fun act(runId: String, level: Int, action: MoveAction, c: CellIndex, uid: String = "pemain-1"): Resp =
        client.post().uri("/v1/tournament/level/action")
            .header("Authorization", "Bearer dev:$uid")
            .body(ActionRequest(runId, level, action, CellDto(c.x, c.y)))
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    private fun actOk(runId: String, action: MoveAction, c: CellIndex): ActionResponse =
        client.post().uri("/v1/tournament/level/action")
            .header("Authorization", "Bearer dev:pemain-1")
            .body(ActionRequest(runId, 0, action, CellDto(c.x, c.y)))
            .retrieve().body(ActionResponse::class.java)!!

    private fun useLife(runId: String, level: Int = 0, uid: String = "pemain-1"): Resp =
        client.post().uri("/v1/tournament/life/use")
            .header("Authorization", "Bearer dev:$uid")
            .body(UseLifeRequest(runId, level))
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    private fun useLifeOk(runId: String, level: Int = 0): UseLifeResponse =
        client.post().uri("/v1/tournament/life/use")
            .header("Authorization", "Bearer dev:pemain-1")
            .body(UseLifeRequest(runId, level))
            .retrieve().body(UseLifeResponse::class.java)!!

    private fun wallet(uid: String = "pemain-1"): Wallet =
        client.get().uri("/v1/wallet")
            .header("Authorization", "Bearer dev:$uid").retrieve().body(Wallet::class.java)!!

    private fun revealSeed(boardId: String, uid: String = "pemain-1"): Resp =
        client.get().uri("/v1/tournament/level/$boardId/reveal")
            .header("Authorization", "Bearer dev:$uid")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    // Peta bom TAK pernah dikirim ke klien (05 §6) — test menghitung sendiri dari seed di DB, pakai
    // API publik engine: reveal pada bom = HitMine dan tidak mengubah papan.
    private fun minesOf(boardId: String): Set<CellIndex> {
        val probe = engine.generate(cfg, seedOf(boardId), first)
        return allCells().filter { engine.reveal(probe, it) is RevealResult.HitMine }.toSet()
    }

    private fun allCells() = (0 until cfg.gridHeight).flatMap { y -> (0 until cfg.gridWidth).map { x -> CellIndex(x, y) } }

    private fun seedOf(boardId: String): Long =
        jdbc.sql("SELECT seed FROM board WHERE id = ?").param(boardId.toLong()).query(Long::class.java).single()

    private fun statusBoard(boardId: String): String =
        jdbc.sql("SELECT status FROM board WHERE id = ?").param(boardId.toLong()).query(String::class.java).single()

    private fun hashOf(seed: Long): String {
        val bytes = ByteArray(8) { i -> (seed ushr (56 - 8 * i)).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // Menuntaskan level 0 sampai bersih; balas respons aksi terakhir.
    private fun tuntaskanLevel(s: StartResponse): ActionResponse {
        actOk(s.runId, MoveAction.REVEAL, first)
        val mines = minesOf(s.boardId)
        var last: ActionResponse? = null
        for (c in allCells().filter { it !in mines }) {
            last = actOk(s.runId, MoveAction.REVEAL, c)
            if (last.status == LevelStatus.LEVEL_CLEARED) break
        }
        return last!!
    }

    // ── Test ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `start membalas commit hash dan dimensi grid, tanpa peta bom`() {
        val s = start()
        assertEquals(0, s.levelIndex)
        assertEquals(cfg.gridWidth, s.gridWidth)
        assertEquals(cfg.mineCount, s.mineCount)
        assertEquals(64, s.commitHash.length, "commit = SHA-256 hex")
        assertTrue(s.revealed.isEmpty() && s.flags.isEmpty(), "level baru: belum ada yang terbuka")
        assertEquals(0, s.movesCount)
    }

    @Test
    fun `start dua kali = resume level yang sama (seed & commit tak berubah)`() {
        val a = start()
        val b = start()
        assertEquals(a.boardId, b.boardId)
        assertEquals(a.commitHash, b.commitHash)
    }

    @Test
    fun `aksi pertama wajib REVEAL — papan baru terwujud saat klik pertama`() {
        val s = start()
        assertEquals(400, act(s.runId, 0, MoveAction.FLAG, first).status)
    }

    @Test
    fun `level tuntas mencatat level_score, memajukan run, dan menutup board`() {
        val s = startTerkunci()
        val last = tuntaskanLevel(s)

        assertEquals(LevelStatus.LEVEL_CLEARED, last.status)
        assertTrue(last.score!! > 0, "skor level > 0")

        val row = jdbc.sql("SELECT score, par_moves, moves_count FROM level_score WHERE run_id = ?")
            .param(s.runId.toLong()).query().singleRow()
        assertEquals(last.score, row["score"])
        assertTrue((row["par_moves"] as Int) > 0, "par per-papan terhitung (ADR-0017)")
        assertEquals(last.movesCount, row["moves_count"])

        val run = jdbc.sql("SELECT current_level, total_score, completed_all_at FROM run WHERE id = ?")
            .param(s.runId.toLong()).query().singleRow()
        assertEquals(1, run["current_level"], "one-shot: run maju ke level berikutnya")
        assertEquals(last.score.toLong(), run["total_score"])
        assertEquals(null, run["completed_all_at"], "baru 1 dari 2 level")

        assertEquals("cleared", statusBoard(s.boardId))
        assertEquals(1, start().levelIndex, "level 0 tak bisa diulang (one-shot, ADR-0024)")
    }

    @Test
    fun `progres selamat walau cache sesi hilang (ADR-0036)`() {
        val s = startTerkunci()
        val terbuka = actOk(s.runId, MoveAction.REVEAL, first).cells.size
        val mines = minesOf(s.boardId).toList()
        actOk(s.runId, MoveAction.FLAG, mines[0])

        game.evictAllSessions() // setara restart server: memori kosong, DB tetap sumber kebenaran

        val resume = start()
        assertEquals(s.boardId, resume.boardId)
        assertEquals(terbuka, resume.revealed.size, "state terbuka dibangun ulang dari log langkah")
        assertEquals(listOf(CellDto(mines[0].x, mines[0].y)), resume.flags)
        assertEquals(1, resume.movesCount, "flag tak dihitung langkah (ADR-0018)")

        // Papan hasil replay identik: flag masih melindungi selnya, dan bom lain tetap bom.
        assertEquals(ActionResult.NO_OP, actOk(s.runId, MoveAction.REVEAL, mines[0]).result)
        assertEquals(ActionResult.HIT_MINE, actOk(s.runId, MoveAction.REVEAL, mines[1]).result)
    }

    @Test
    fun `kena bom menahan level menunggu nyawa, board tetap aktif`() {
        val s = startTerkunci()
        actOk(s.runId, MoveAction.REVEAL, first)
        val mine = minesOf(s.boardId).first()

        val hit = actOk(s.runId, MoveAction.REVEAL, mine)
        assertEquals(ActionResult.HIT_MINE, hit.result)
        assertEquals(LevelStatus.HIT_MINE, hit.status)
        // Level belum gugur & belum diskor: menunggu pemakaian nyawa (ARCH §6.3, T-023).
        assertEquals("active", statusBoard(s.boardId))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM level_score").query(Long::class.java).single().toInt())
        assertEquals(409, act(s.runId, 0, MoveAction.REVEAL, CellIndex(4, 4)).status, "aksi lain ditolak")
        assertTrue(start().awaitingLife)
    }

    // ── Nyawa (T-023, ADR-0008/0037) ─────────────────────────────────────────────────────────────

    // Menghidupkan level sampai kena bom ke-`ke`; balas daftar bom papan itu.
    private fun sampaiKenaBom(s: StartResponse, ke: Int = 0): List<CellIndex> {
        val mines = minesOf(s.boardId).toList()
        if (ke == 0) actOk(s.runId, MoveAction.REVEAL, first)
        assertEquals(ActionResult.HIT_MINE, actOk(s.runId, MoveAction.REVEAL, mines[ke]).result)
        return mines
    }

    @Test
    fun `pakai nyawa melanjutkan level di tempat dan menandai bom yang meledak`() {
        val s = startTerkunci()
        val mines = sampaiKenaBom(s)

        val r = useLifeOk(s.runId)
        assertEquals(1, r.livesUsed)
        assertEquals(3, r.lifeCap)
        assertEquals(1, r.freeLives, "2 FreeLife periode (GDD §7.2) dikurangi 1")
        assertEquals(0, r.paidLives)

        // Level lanjut DI TEMPAT (bukan rewind/restart): aksi diterima lagi, board tetap aktif.
        assertEquals("active", statusBoard(s.boardId))
        val lanjut = allCells().first { it !in mines && actOk(s.runId, MoveAction.REVEAL, it).result == ActionResult.REVEALED }
        assertTrue(lanjut !in mines)
        // Bom penyebab mati kini terflag → tak bisa membunuh dua kali dengan nyawa yang sama.
        assertEquals(ActionResult.NO_OP, actOk(s.runId, MoveAction.REVEAL, mines[0]).result)
        assertEquals(1, start().flags.count { it.x == mines[0].x && it.y == mines[0].y })

        val used = jdbc.sql("SELECT type, used_in_run_id FROM life_ledger WHERE status = 'used'").query().singleRow()
        assertEquals("free", used["type"])
        assertEquals(s.runId.toLong(), used["used_in_run_id"])
    }

    // FIFO-expiry (ADR-0008): yang paling cepat hangus dipakai dulu. Nyawa `paid` sengaja dibuat
    // LEBIH DULU (id lebih kecil) — kalau urutannya jatuh ke id, paid yang terbakar dan test gagal.
    @Test
    fun `nyawa dipakai FIFO-expiry — free hangus lebih dulu daripada paid carry-over`() {
        val s = startTerkunci()
        val userId = jdbc.sql("SELECT id FROM app_user WHERE firebase_uid = 'pemain-1'").query(Long::class.java).single()
        jdbc.sql("INSERT INTO life_ledger (user_id, type, source, expiry) VALUES (?, 'paid', 'purchase', NULL)")
            .param(userId).update()
        sampaiKenaBom(s)

        val r = useLifeOk(s.runId)
        assertEquals(1, r.freeLives)
        assertEquals(1, r.paidLives, "paid tak tersentuh selama masih ada free")
        assertEquals("free", jdbc.sql("SELECT type FROM life_ledger WHERE status = 'used'").query(String::class.java).single())
    }

    @Test
    fun `nyawa habis membalas 409 dan level tetap menunggu nyawa`() {
        val s = startTerkunci()
        val mines = sampaiKenaBom(s)
        useLifeOk(s.runId)
        assertEquals(ActionResult.HIT_MINE, actOk(s.runId, MoveAction.REVEAL, mines[1]).result)
        assertEquals(2, useLifeOk(s.runId).livesUsed)
        assertEquals(ActionResult.HIT_MINE, actOk(s.runId, MoveAction.REVEAL, mines[2]).result)

        val habis = useLife(s.runId) // 2 FreeLife periode sudah terbakar, grant tak berulang
        assertEquals(409, habis.status, "body: ${habis.body}")
        assertTrue(habis.body!!.contains("\"code\":\"CONFLICT\""), "body: ${habis.body}")
        assertTrue(start().awaitingLife, "tetap menunggu nyawa — tak ada nyawa hantu")
        assertEquals(0, wallet().free + wallet().paid)
        assertEquals(2, jdbc.sql("SELECT lives_used FROM run WHERE id = ?").param(s.runId.toLong())
            .query(Int::class.java).single(), "tie-breaker ADR-0009 menghitung nyawa walau level belum tuntas")
    }

    @Test
    fun `progres setelah pakai nyawa selamat walau cache sesi hilang (ADR-0036)`() {
        val s = startTerkunci()
        val mines = sampaiKenaBom(s)
        useLifeOk(s.runId)

        game.evictAllSessions() // setara restart server: state dibangun ulang dari log langkah

        val resume = start()
        assertTrue(!resume.awaitingLife, "USE_LIFE di log → pemain hidup lagi setelah replay")
        assertEquals(listOf(CellDto(mines[0].x, mines[0].y)), resume.flags)
        assertEquals(2, resume.movesCount, "reveal pertama + reveal bom; USE_LIFE 0 langkah (ADR-0018)")
        assertEquals(ActionResult.NO_OP, actOk(s.runId, MoveAction.REVEAL, mines[0]).result)
    }

    // Penalti nyawa ADR-0017 benar-benar sampai ke scoring: lifeCap=1 → 1 nyawa = skor level 0.
    @Test
    fun `nyawa tercatat di level_score dan menolkan skor saat mencapai lifeCap`() {
        jdbc.sql("UPDATE level_config SET life_cap = 1 WHERE level_index = 0").update()
        val s = startTerkunci()
        sampaiKenaBom(s)
        useLifeOk(s.runId)

        assertEquals(LevelStatus.LEVEL_CLEARED, tuntaskanLevel(s).status)

        val row = jdbc.sql("SELECT lives_used, score FROM level_score WHERE run_id = ?")
            .param(s.runId.toLong()).query().singleRow()
        assertEquals(1, row["lives_used"])
        assertEquals(0, row["score"], "livesUsed >= lifeCap → penalti nol (ADR-0017)")
        assertEquals(1, jdbc.sql("SELECT lives_used FROM run WHERE id = ?").param(s.runId.toLong())
            .query(Int::class.java).single())
    }

    @Test
    fun `wallet memberi 2 FreeLife sekali per periode (grant malas idempoten)`() {
        val a = wallet()
        assertEquals(2, a.free, "GDD §7.2")
        assertEquals(0, a.paid)
        assertTrue(a.nextExpiry != null, "FreeLife hangus di akhir periode (ADR-0008)")

        assertEquals(2, wallet().free, "panggilan kedua tak mencetak nyawa baru")
        assertEquals(2, jdbc.sql("SELECT count(*) FROM life_ledger").query(Long::class.java).single().toInt())
    }

    // USE_LIFE hidup di log langkah, bukan di alfabet aksi klien: lewat /action ia akan melewati
    // pemotongan nyawa (ADR-0037).
    @Test
    fun `USE_LIFE lewat endpoint action ditolak 400`() {
        val s = startTerkunci()
        sampaiKenaBom(s)
        val r = act(s.runId, 0, MoveAction.USE_LIFE, first)
        assertEquals(400, r.status, "body: ${r.body}")
        assertTrue(start().awaitingLife)
    }

    @Test
    fun `flag gratis-langkah, flag di sel terbuka = NO_OP`() {
        val s = startTerkunci()
        val opened = actOk(s.runId, MoveAction.REVEAL, first)
        assertEquals(1, opened.movesCount)

        val tertutup = allCells().first { c -> opened.cells.none { it.x == c.x && it.y == c.y } }
        assertEquals(ActionResult.FLAGGED, actOk(s.runId, MoveAction.FLAG, tertutup).result)
        assertEquals(ActionResult.UNFLAGGED, actOk(s.runId, MoveAction.FLAG, tertutup).result)

        val terbuka = CellIndex(opened.cells[0].x, opened.cells[0].y)
        val terakhir = actOk(s.runId, MoveAction.FLAG, terbuka)
        assertEquals(ActionResult.NO_OP, terakhir.result, "sel terbuka tak bisa diflag")
        assertEquals(1, terakhir.movesCount, "flag & no-op tak menambah langkah (ADR-0018)")
    }

    @Test
    fun `seed hanya diungkap setelah level selesai, dan cocok dengan commit hash`() {
        val s = startTerkunci()
        assertEquals(409, revealSeed(s.boardId).status, "board masih aktif → seed = peta bom")

        tuntaskanLevel(s)

        val r = client.get().uri("/v1/tournament/level/${s.boardId}/reveal")
            .header("Authorization", "Bearer dev:pemain-1").retrieve().body(RevealSeedResponse::class.java)!!
        assertEquals(s.commitHash, r.commitHash)
        assertEquals(hashOf(r.seed.toLong()), r.commitHash, "commit-reveal terverifikasi (ARCH §6.5)")
    }

    // Mengunci "prefix /v1 = aman-default" (ApiWebConfig): kalau predikat prefix rusak, controller
    // modul feature jatuh ke `/tournament/...` yang TAK dijaga BearerAuthFilter → test ini gagal.
    @Test
    fun `endpoint game tanpa bearer ditolak 401`() {
        val r = client.post().uri("/v1/tournament/level/start")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
        assertEquals(401, r.status, "body: ${r.body}")
        assertTrue(r.body!!.contains("\"code\":\"UNAUTHENTICATED\""), "body: ${r.body}")
    }

    // Body cacat = salah klien (400 VALIDATION), bukan 500. Menjaga dua hal sekaligus: advice tak
    // menelan exception MVC standar, dan Jackson menegakkan non-null Kotlin (butuh module kotlin).
    @Test
    fun `body aksi tanpa field wajib ditolak 400`() {
        val r = client.post().uri("/v1/tournament/level/action")
            .header("Authorization", "Bearer dev:pemain-1")
            .header("Content-Type", "application/json")
            .body("{}")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
        assertEquals(400, r.status, "body: ${r.body}")
        assertTrue(r.body!!.contains("\"code\":\"VALIDATION\""), "body: ${r.body}")
    }

    // Tanpa module Kotlin, Jackson mengisi primitif non-null yang absen dengan 0 diam-diam →
    // `levelIndex` hilang dibaca sbg level 0 (bisa beraksi di level yang salah, one-shot ADR-0024).
    @Test
    fun `body aksi tanpa levelIndex ditolak, bukan diam-diam dianggap level 0`() {
        val s = start()
        val r = client.post().uri("/v1/tournament/level/action")
            .header("Authorization", "Bearer dev:pemain-1")
            .header("Content-Type", "application/json")
            .body("""{"runId":"${s.runId}","action":"REVEAL","cell":{"x":0,"y":0}}""")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
        assertEquals(400, r.status, "body: ${r.body}")
    }

    @Test
    fun `run pemain lain tak bisa disentuh`() {
        val s = start("pemain-1")
        assertEquals(404, revealSeed(s.boardId, uid = "pemain-2").status)
        assertEquals(404, act(s.runId, 0, MoveAction.REVEAL, first, uid = "pemain-2").status)
    }

    // ── Gerbang turnamen (T-026) ─────────────────────────────────────────────────────────────────

    @Test
    fun `tanpa menyetujui S&K, level tak bisa dimulai`() {
        val r = startRaw("pemain-baru") // belum lewat POST /tournament/consent
        assertEquals(403, r.status, "body: ${r.body}")
        assertTrue(r.body!!.contains("\"code\":\"CONSENT_REQUIRED\""), "body: ${r.body}")
    }

    @Test
    fun `pemain yang kena ban tak bisa memulai level`() {
        start() // pastikan barisnya ada
        jdbc.sql(
            "INSERT INTO tournament_ban (user_id, reason, period_start_id) " +
                "SELECT u.id, 'refund', p.id FROM app_user u, period p WHERE u.firebase_uid = 'pemain-1' AND p.status = 'ACTIVE'",
        ).update()

        val r = startRaw("pemain-1")
        assertEquals(403, r.status, "body: ${r.body}")
        assertTrue(r.body!!.contains("\"code\":\"BANNED\""), "body: ${r.body}")
    }

    // Lubang yang ditutup T-026: `action` sengaja TIDAK digerbang (jalur panas), jadi guardnya ada di
    // corong skor. Tanpa itu, void di tengah level tetap menambah skor ke run yang sudah dikunci.
    @Test
    fun `run terkunci di tengah level - level tercatat tapi skornya tak dikreditkan`() {
        val s = startTerkunci()
        actOk(s.runId, MoveAction.REVEAL, first)
        jdbc.sql("UPDATE run SET score_locked = true WHERE id = ?").param(s.runId.toLong()).update()

        tuntaskanLevel(s)

        val run = jdbc.sql("SELECT current_level, total_score FROM run WHERE id = ?")
            .param(s.runId.toLong()).query().singleRow()
        assertEquals(0L, run["total_score"], "skor run terkunci tak boleh naik (ADR-0025)")
        assertEquals(0, run["current_level"])
        assertTrue(
            jdbc.sql("SELECT count(*) FROM level_score WHERE run_id = ?")
                .param(s.runId.toLong()).query(Int::class.java).single() == 1,
            "level_score tetap ditulis: itu fakta permainan, bukan hadiah",
        )
    }

    @Test
    fun `periode berakhir di tengah level - skor tak masuk ke agregat`() {
        val s = startTerkunci()
        actOk(s.runId, MoveAction.REVEAL, first)
        jdbc.sql("UPDATE period SET status = 'ENDED' WHERE status = 'ACTIVE'").update()

        tuntaskanLevel(s)

        assertEquals(
            0L,
            jdbc.sql("SELECT total_score FROM run WHERE id = ?")
                .param(s.runId.toLong()).query(Long::class.java).single(),
            "pemenang periode sudah ditentukan — agregatnya tak boleh bergerak lagi",
        )
    }

    private fun startRaw(uid: String): Resp = client.post().uri("/v1/tournament/level/start")
        .header("Authorization", "Bearer dev:$uid")
        .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
}
