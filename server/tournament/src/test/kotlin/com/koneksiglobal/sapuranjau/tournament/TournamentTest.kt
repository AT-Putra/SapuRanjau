package com.koneksiglobal.sapuranjau.tournament

import com.koneksiglobal.sapuranjau.api.error.ApiException
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootApplication(scanBasePackages = ["com.koneksiglobal.sapuranjau"])
class TournamentTestApp

// Bukti runtime T-026 di Postgres 18 asli: siklus periode (ADR-0021), gerbang ban ordinal
// (ADR-0025/0038) & S&K (ADR-0026), pemenang + cooldown (ADR-0027), leaderboard (ADR-0009).
// Tick dimatikan — rollover() dipanggil langsung supaya waktunya deterministik.
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "sapuranjau.tournament.tick.enabled=false", "sapuranjau.tournament.tnc-version=v1",
        // Server menolak boot tanpa kunci PII (T-029) — test pun harus menyediakannya.
        "sapuranjau.pii.key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    ],
)
class TournamentTest {

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
    @Autowired private lateinit var periods: PeriodService
    @Autowired private lateinit var winners: WinnerService
    @Autowired private lateinit var pii: PiiCipher

    @BeforeEach
    fun bersihkan() {
        jdbc.sql(
            "TRUNCATE winner, prize_config, tournament_ban, tournament_consent, life_ledger, purchase, " +
                "run, audit_event, app_user, period RESTART IDENTITY CASCADE",
        ).update()
    }

    // ── Helper ───────────────────────────────────────────────────────────────────────────────────

    private val client get() = RestClient.create("http://localhost:$port")

    private data class Resp(val status: Int, val body: String?)

    private fun statusOf(uid: String = "pemain-1"): StatusResponse =
        client.get().uri("/v1/tournament/status").header("Authorization", "Bearer dev:$uid")
            .retrieve().body(StatusResponse::class.java)!!

    private fun consent(version: String, uid: String = "pemain-1"): Resp =
        client.post().uri("/v1/tournament/consent").header("Authorization", "Bearer dev:$uid")
            .body(ConsentRequest(version))
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    private fun leaderboard(query: String = "", uid: String = "pemain-1"): LeaderboardResponse =
        client.get().uri("/v1/leaderboard$query").header("Authorization", "Bearer dev:$uid")
            .retrieve().body(LeaderboardResponse::class.java)!!

    private fun klaim(body: Map<String, String>, uid: String = "pemain-1"): Resp =
        client.post().uri("/v1/prizes/claim").header("Authorization", "Bearer dev:$uid").body(body)
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    private fun inbox(uid: String = "pemain-1"): MessagesResponse =
        client.get().uri("/v1/messages").header("Authorization", "Bearer dev:$uid")
            .retrieve().body(MessagesResponse::class.java)!!

    private fun tandaiBaca(id: String, uid: String = "pemain-1"): Resp =
        client.post().uri("/v1/messages/$id/read").header("Authorization", "Bearer dev:$uid")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    // Pesan hanya bisa dibuat admin (ADR-0021) dan panelnya belum ada → test menaruhnya langsung.
    private fun pesan(userId: Long, body: String): Long =
        jdbc.sql("INSERT INTO message (user_id, admin_id, body) VALUES (?, 1, ?) RETURNING id")
            .params(userId, body).query(Long::class.java).single()

    // Satu pemenang aktif berhadiah, siap mengklaim.
    private fun juaraDenganHadiah(uid: String = "pemain-1"): Long {
        val p = periode("p", -10, 0, status = "ENDED")
        hadiah(p, 3)
        val u = pemain(uid)
        run(u, p, skor = 500)
        winners.finalizePeriod(p)
        return u
    }

    private fun setName(name: String, uid: String = "pemain-1"): Resp =
        client.put().uri("/v1/profile/display-name").header("Authorization", "Bearer dev:$uid")
            .body(DisplayNameRequest(name))
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    // Periode langsung lewat SQL: test butuh menyusun URUTAN (ordinal) yang tanggalnya sudah lewat,
    // sesuatu yang create() sengaja tak izinkan.
    private fun periode(name: String, mulaiHari: Int, selesaiHari: Int, status: String = "UPCOMING"): Long =
        jdbc.sql(
            "INSERT INTO period (name, starts_at, ends_at, status) VALUES " +
                "(?, now() + make_interval(days => ?), now() + make_interval(days => ?), ?) RETURNING id",
        ).params(name, mulaiHari, selesaiHari, status).query(Long::class.java).single()

    private fun pemain(uid: String): Long =
        jdbc.sql("INSERT INTO app_user (firebase_uid) VALUES (?) RETURNING id")
            .param(uid).query(Long::class.java).single()

    private fun run(userId: Long, periodId: Long, skor: Long, nyawa: Int = 0, waktuMs: Long = 1000): Long =
        jdbc.sql(
            "INSERT INTO run (user_id, period_id, total_score, lives_used, total_time_ms, total_moves) " +
                "VALUES (?, ?, ?, ?, ?, 10) RETURNING id",
        ).params(userId, periodId, skor, nyawa, waktuMs).query(Long::class.java).single()

    private fun hadiah(periodId: Long, slot: Int) {
        jdbc.sql("INSERT INTO prize_config (period_id, winners_count, prizes) VALUES (?, ?, '[]'::jsonb)")
            .params(periodId, slot).update()
    }

    private fun statusPeriode(id: Long): String =
        jdbc.sql("SELECT status FROM period WHERE id = ?").param(id).query(String::class.java).single()

    private fun juara(periodId: Long): List<Pair<Int, Long>> =
        jdbc.sql("SELECT rank, user_id FROM winner WHERE period_id = ? AND status = 'active' ORDER BY rank")
            .param(periodId).query { rs, _ -> rs.getInt("rank") to rs.getLong("user_id") }.list()

    // ── Siklus periode (ADR-0021/0040) ───────────────────────────────────────────────────────────

    @Test
    fun `rollover menutup periode yang habis dan mengangkat yang terjadwal`() {
        val lama = periode("lama", -10, 0, status = "ACTIVE").also { akhiriSekarang(it) }
        val baru = periode("baru", 0, 7)

        periods.rollover()

        assertEquals("ENDED", statusPeriode(lama))
        assertEquals("ACTIVE", statusPeriode(baru))
    }

    @Test
    fun `hanya satu periode terjadwal yang diangkat walau dua-duanya sudah waktunya`() {
        val duluan = periode("duluan", -1, 7)
        val belakangan = periode("belakangan", 0, 8)

        periods.rollover()

        assertEquals("ACTIVE", statusPeriode(duluan))
        assertEquals("UPCOMING", statusPeriode(belakangan))
    }

    @Test
    fun `create menolak rentang yang tumpang-tindih dengan periode terjadwal`() {
        val mulai = Instant.now().plus(1, ChronoUnit.DAYS)
        periods.create("p1", mulai, mulai.plus(7, ChronoUnit.DAYS))

        assertFailsWith<ApiException> {
            periods.create("p2", mulai.plus(3, ChronoUnit.DAYS), mulai.plus(9, ChronoUnit.DAYS))
        }
    }

    @Test
    fun `rollover menutup papan yatim dan menghanguskan nyawa yang jatuh tempo`() {
        val p = periode("p", -10, 0, status = "ACTIVE").also { akhiriSekarang(it) }
        val u = pemain("pemain-1")
        val r = run(u, p, skor = 100)
        jdbc.sql("INSERT INTO level_config (period_id, level_index, grid_width, grid_height, mine_count, base_score, life_cap) VALUES (?, 0, 9, 9, 10, 100, 1)")
            .param(p).update()
        jdbc.sql(
            "INSERT INTO board (run_id, level_config_id, seed, commit_hash) " +
                "SELECT ?, id, 1, 'x' FROM level_config WHERE period_id = ?",
        ).params(r, p).update()
        jdbc.sql(
            "INSERT INTO life_ledger (user_id, type, source, period_id, expiry) VALUES (?, 'free', 'grant_period', ?, now() - interval '1 hour')",
        ).params(u, p).update()

        periods.rollover()

        assertEquals("failed", jdbc.sql("SELECT status FROM board WHERE run_id = ?").param(r).query(String::class.java).single())
        assertEquals("expired", jdbc.sql("SELECT status FROM life_ledger WHERE user_id = ?").param(u).query(String::class.java).single())
    }

    // ── Gerbang (ADR-0021/0025/0026) ─────────────────────────────────────────────────────────────

    @Test
    fun `tanpa periode aktif, turnamen terkunci`() {
        assertEquals(TournamentStatus.LOCKED, statusOf().status)
    }

    @Test
    fun `S&K wajib disetujui tiap periode, dan lagi kalau versinya berubah`() {
        periode("p", 0, 7, status = "ACTIVE")

        assertEquals(TournamentStatus.CONSENT_REQUIRED, statusOf().status)
        assertEquals(200, consent("v1").status)
        assertEquals(TournamentStatus.OK, statusOf().status)

        // Naskah baru terbit → versi tersimpan tak lagi sama dengan versi berjalan.
        jdbc.sql("UPDATE tournament_consent SET tnc_version = 'v0'").update()
        assertEquals(TournamentStatus.CONSENT_REQUIRED, statusOf().status)

        // Jejak legalnya tetap ada meski barisnya di-upsert (ADR-0040).
        assertEquals(
            1,
            jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'tournament_consent'")
                .query(Int::class.java).single(),
        )
    }

    @Test
    fun `menyetujui versi S&K yang basi ditolak 409`() {
        periode("p", 0, 7, status = "ACTIVE")
        assertEquals(409, consent("v0").status)
    }

    @Test
    fun `ban ditegakkan ordinal - kena di P, P+1, P+2 lalu lepas di P+3`() {
        val u = pemain("pemain-1")
        val p = (1..4).map { periode("p$it", (it - 1) * 10, it * 10) }
        // period_end_id sengaja NULL: itu keadaan normal saat ban terbit (ADR-0038).
        jdbc.sql("INSERT INTO tournament_ban (user_id, reason, period_start_id) VALUES (?, 'refund', ?)")
            .params(u, p[0]).update()

        listOf(3, 2, 1).forEachIndexed { i, sisa ->
            aktifkan(p[i])
            val s = statusOf()
            assertEquals(TournamentStatus.BANNED, s.status, "periode ke-${i + 1}")
            assertEquals(sisa, s.banPeriodsLeft)
        }

        aktifkan(p[3])
        // Lepas dari ban → yang tersisa cuma gerbang S&K, bukan BANNED.
        assertEquals(TournamentStatus.CONSENT_REQUIRED, statusOf().status)
    }

    // ── Pemenang (ADR-0021/0009/0027) ────────────────────────────────────────────────────────────

    @Test
    fun `pemenang diambil sebanyak slot, urut tie-breaker nyawa saat skor seri`() {
        val p = periode("p", -10, 0, status = "ENDED")
        hadiah(p, 3)
        val a = pemain("a"); val b = pemain("b"); val c = pemain("c"); val d = pemain("d")
        run(a, p, skor = 500)
        run(b, p, skor = 300, nyawa = 2) // skor seri dengan c → kalah karena nyawa lebih banyak
        run(c, p, skor = 300, nyawa = 0)
        run(d, p, skor = 100)

        winners.finalizePeriod(p)

        assertEquals(listOf(1 to a, 2 to c, 3 to b), juara(p))
    }

    @Test
    fun `pemenang melewati yang kena ban, yang cooldown, dan yang skornya terkunci`() {
        val sebelum = periode("sebelum", -20, -10, status = "ENDED")
        val p = periode("p", -10, 0, status = "ENDED")
        hadiah(p, 3)
        val juaraLama = pemain("juara-lama"); val kenaBan = pemain("kena-ban")
        val terkunci = pemain("terkunci"); val bersih = pemain("bersih")

        jdbc.sql("INSERT INTO winner (period_id, user_id, rank) VALUES (?, ?, 1)").params(sebelum, juaraLama).update()
        jdbc.sql("INSERT INTO tournament_ban (user_id, reason, period_start_id) VALUES (?, 'refund', ?)")
            .params(kenaBan, p).update()

        run(juaraLama, p, skor = 900)
        run(kenaBan, p, skor = 800)
        val runTerkunci = run(terkunci, p, skor = 700)
        jdbc.sql("UPDATE run SET score_locked = true WHERE id = ?").param(runTerkunci).update()
        run(bersih, p, skor = 600)

        winners.finalizePeriod(p)

        assertEquals(listOf(1 to bersih), juara(p))
    }

    @Test
    fun `finalisasi idempoten - rollover berulang tak menggandakan pemenang`() {
        val p = periode("p", -10, 0, status = "ACTIVE").also { akhiriSekarang(it) }
        hadiah(p, 3)
        repeat(3) { run(pemain("p$it"), p, skor = (it + 1) * 100L) }

        periods.rollover()
        periods.rollover()

        assertEquals(3, juara(p).size)
    }

    @Test
    fun `gugurkan pemenang - yang di bawah naik satu tingkat, kandidat berikutnya masuk`() {
        val p = periode("p", -10, 0, status = "ENDED")
        hadiah(p, 3) // min 3 slot (ADR-0021)
        val a = pemain("a"); val b = pemain("b"); val c = pemain("c"); val d = pemain("d")
        run(a, p, skor = 400); run(b, p, skor = 300); run(c, p, skor = 200); run(d, p, skor = 100)
        winners.finalizePeriod(p)
        val winnerB = jdbc.sql("SELECT id FROM winner WHERE period_id = ? AND user_id = ?")
            .params(p, b).query(Long::class.java).single()

        winners.disqualify(winnerB, "terbukti pakai bot")

        // c naik ke 2, dan slot 3 yang kosong diisi kandidat eligible berikutnya (d).
        assertEquals(listOf(1 to a, 2 to c, 3 to d), juara(p))
        assertEquals(
            1,
            jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'winner_disqualified'")
                .query(Int::class.java).single(),
        )
    }

    @Test
    fun `gugur tanpa alasan ditolak`() {
        val p = periode("p", -10, 0, status = "ENDED")
        hadiah(p, 3)
        run(pemain("a"), p, skor = 300)
        winners.finalizePeriod(p)
        val id = jdbc.sql("SELECT id FROM winner").query(Long::class.java).single()

        assertFailsWith<ApiException> { winners.disqualify(id, "   ") }
    }

    // ── Ban tertunda (T-025 → ADR-0038) ──────────────────────────────────────────────────────────

    @Test
    fun `ban yang tertunda terbit saat ada periode aktif, dan hanya sekali`() {
        val u = pemain("pemain-1")
        jdbc.sql(
            "INSERT INTO purchase (user_id, purchase_token, product_id, lives_granted, status, void_reason) " +
                "VALUES (?, 'tok-1', 'life_s', 1, 'voided', 'refund')",
        ).param(u).update()
        val p = periode("p", -1, 7)

        periods.rollover()
        periods.rollover()

        val ban = jdbc.sql("SELECT period_start_id FROM tournament_ban WHERE user_id = ?")
            .param(u).query(Long::class.java).list()
        assertEquals(listOf(p), ban)
        assertEquals(TournamentStatus.BANNED, statusOf().status)
    }

    // ── Leaderboard & nama tampilan (ADR-0009/0039) ──────────────────────────────────────────────

    @Test
    fun `leaderboard urut skor, paginasi, dan menandai baris sendiri`() {
        val p = periode("p", 0, 7, status = "ACTIVE")
        val aku = pemain("pemain-1")
        run(pemain("juara"), p, skor = 900)
        run(aku, p, skor = 500)
        run(pemain("buncit"), p, skor = 100)

        val h1 = leaderboard("?page=0&size=2")
        assertEquals(listOf(1, 2), h1.entries.map { it.rank })
        assertEquals(listOf(900L, 500L), h1.entries.map { it.totalScore })
        assertTrue(h1.entries[1].me)
        assertEquals("Pemain #$aku", h1.entries[1].name) // fallback saat belum menyetel nama

        val h2 = leaderboard("?page=1&size=2")
        assertEquals(listOf(3), h2.entries.map { it.rank })
    }

    @Test
    fun `nama tampilan tersimpan dan dipakai leaderboard`() {
        val p = periode("p", 0, 7, status = "ACTIVE")
        run(pemain("pemain-1"), p, skor = 500)

        assertEquals(200, setName("Adit Sapu").status)
        assertEquals("Adit Sapu", leaderboard().entries.single().name)
    }

    @Test
    fun `nama tampilan cacat ditolak 400`() {
        periode("p", 0, 7, status = "ACTIVE")
        listOf("a", "x".repeat(21), "baris\nbaru", "  ").forEach {
            assertEquals(400, setName(it).status, "nama: '$it'")
        }
        assertNull(
            jdbc.sql("SELECT display_name FROM app_user WHERE firebase_uid = 'pemain-1'")
                .query(String::class.java).optional().orElse(null),
        )
    }

    // ── Klaim hadiah & inbox (T-029, ADR-0021) ───────────────────────────────────────────────────

    @Test
    fun `pemenang mengklaim hadiah - PII tersimpan terenkripsi, audit tanpa isinya`() {
        val u = juaraDenganHadiah()

        val r = klaim(mapOf("phone" to "081234567890", "ewallet" to "081234567890 (DANA)"))
        assertEquals(200, r.status, "body: ${r.body}")

        val row = jdbc.sql("SELECT phone_enc, ewallet_enc, address_enc, status FROM prize_claim").query().singleRow()
        assertEquals("pending", row["status"])
        assertEquals(null, row["address_enc"], "alamat tak diisi → tetap NULL")
        val phoneEnc = String(row["phone_enc"] as ByteArray, Charsets.ISO_8859_1)
        assertTrue(!phoneEnc.contains("081234567890"), "nomor HP tak boleh tersimpan apa adanya")
        // Kunci uji ada di properti test → nilai aslinya harus kembali utuh.
        assertEquals("081234567890", pii.decrypt(row["phone_enc"] as ByteArray))

        val detail = jdbc.sql("SELECT detail::text AS d FROM audit_event WHERE event_type = 'prize_claim_saved'")
            .query(String::class.java).single()
        assertTrue(!detail.contains("081234567890"), "audit append-only tak boleh jadi salinan PII: $detail")
        assertTrue(detail.contains("phone") && detail.contains("ewallet"), "detail: $detail")
    }

    @Test
    fun `klaim tanpa e-wallet maupun alamat ditolak — tak ada yang bisa dibayar`() {
        juaraDenganHadiah()
        assertEquals(400, klaim(mapOf("phone" to "081234567890")).status)
    }

    @Test
    fun `nomor HP ngawur ditolak`() {
        juaraDenganHadiah()
        assertEquals(400, klaim(mapOf("phone" to "bukan-nomor", "ewallet" to "x")).status)
    }

    @Test
    fun `yang belum pernah menang tak bisa mengklaim`() {
        periode("p", 0, 7, status = "ACTIVE")
        assertEquals(409, klaim(mapOf("phone" to "081234567890", "ewallet" to "DANA")).status)
    }

    @Test
    fun `klaim boleh diperbaiki selama pending, beku setelah admin memprosesnya`() {
        juaraDenganHadiah()
        klaim(mapOf("phone" to "081200000000", "ewallet" to "salah ketik"))
        klaim(mapOf("phone" to "081211111111", "ewallet" to "DANA benar"))

        val row = jdbc.sql("SELECT count(*) AS n, max(id) AS id FROM prize_claim").query().singleRow()
        assertEquals(1L, row["n"], "perbaikan = update baris yang sama, bukan klaim kedua")
        assertEquals(
            "081211111111",
            pii.decrypt(
                jdbc.sql("SELECT phone_enc FROM prize_claim WHERE id = ?").param(row["id"])
                    .query(ByteArray::class.java).single(),
            ),
        )

        jdbc.sql("UPDATE prize_claim SET status = 'paid'").update()
        assertEquals(409, klaim(mapOf("phone" to "081299999999", "ewallet" to "curang")).status)
    }

    @Test
    fun `pemenang yang digugurkan tak bisa mengklaim`() {
        juaraDenganHadiah()
        jdbc.sql("UPDATE winner SET status = 'disqualified', disqualify_reason = 'bot'").update()
        assertEquals(409, klaim(mapOf("phone" to "081234567890", "ewallet" to "DANA")).status)
    }

    @Test
    fun `inbox hanya menampilkan pesan sendiri dan menghitung yang belum dibaca`() {
        val aku = pemain("pemain-1")
        val lain = pemain("pemain-2")
        pesan(aku, "pesan lama")
        pesan(aku, "pesan baru")
        pesan(lain, "bukan untukmu")

        val r = inbox()
        assertEquals(listOf("pesan baru", "pesan lama"), r.messages.map { it.body }, "terbaru dulu")
        assertEquals(2, r.unread)

        val id = r.messages.first().id
        assertEquals(200, tandaiBaca(id).status)
        assertEquals(1, inbox().unread)
        assertEquals(200, tandaiBaca(id).status, "menandai ulang tetap 200 (idempoten)")
    }

    @Test
    fun `pesan milik pemain lain dibalas 404`() {
        val lain = pemain("pemain-2")
        pemain("pemain-1")
        val id = pesan(lain, "rahasia")
        assertEquals(404, tandaiBaca(id.toString()).status)
    }

    @Test
    fun `endpoint turnamen tanpa Bearer ditolak 401`() {
        val r = client.get().uri("/v1/tournament/status")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
        assertEquals(401, r.status)
    }

    // Majukan jam berakhir supaya rollover melihatnya sebagai periode yang sudah habis.
    private fun akhiriSekarang(periodId: Long) {
        jdbc.sql("UPDATE period SET ends_at = now() - interval '1 minute' WHERE id = ?").param(periodId).update()
    }

    // Pindahkan periode aktif ke `id` (yang lama ditutup) — menyusun jarak ordinal utk uji ban.
    private fun aktifkan(id: Long) {
        jdbc.sql("UPDATE period SET status = 'ENDED' WHERE status = 'ACTIVE'").update()
        jdbc.sql("UPDATE period SET status = 'ACTIVE' WHERE id = ?").param(id).update()
    }
}
