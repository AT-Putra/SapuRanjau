package com.koneksiglobal.sapuranjau.billing

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootApplication(scanBasePackages = ["com.koneksiglobal.sapuranjau"])
class BillingTestApp

// Play palsu yang bisa dikendalikan test — menggantikan `StubPlayPurchases` lewat @Primary.
class FakePlay : PlayPurchases {
    var dikenal = true
    var sudahDibayar = true
    var consumeCount = 0

    override fun verify(productId: String, purchaseToken: String): PlayPurchase? =
        if (dikenal) PlayPurchase("order-$purchaseToken", sudahDibayar) else null

    override fun consume(productId: String, purchaseToken: String) { consumeCount++ }

    override fun listVoided(since: Instant): List<VoidedPurchase> = emptyList()
}

@TestConfiguration
class FakePlayConfig {
    @Bean
    @Primary
    fun fakePlay() = FakePlay()
}

// Bukti runtime T-025 di Postgres 18 asli: verifikasi → grant PaidLife (ADR-0011/0022) dan
// penegakan void refund/chargeback (ADR-0025) — clawback + skor-0 + ban.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [BillingTestApp::class, FakePlayConfig::class])
class BillingTest {

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
    @Autowired private lateinit var billing: BillingService
    @Autowired private lateinit var play: FakePlay

    private data class Resp(val status: Int, val body: String?)

    @BeforeEach
    fun bersihkan() {
        jdbc.sql(
            "TRUNCATE tournament_ban, audit_event, life_ledger, purchase, run, period, app_user RESTART IDENTITY CASCADE",
        ).update()
        periodeBaru("P", 0, "ACTIVE")
        play.dikenal = true
        play.sudahDibayar = true
        play.consumeCount = 0
    }

    // ── Helper ───────────────────────────────────────────────────────────────────────────────────

    private val client get() = RestClient.create("http://localhost:$port")

    private fun periodeBaru(nama: String, mulaiHari: Int, status: String): Long = jdbc.sql(
        "INSERT INTO period (name, starts_at, ends_at, status) VALUES (?, now() + make_interval(days => ?), " +
            "now() + make_interval(days => ?), ?) RETURNING id",
    ).params(nama, mulaiHari, mulaiHari + 20, status).query(Long::class.java).single()

    private fun verify(productId: String, token: String, uid: String = "pemain-1"): VerifyResponse =
        client.post().uri("/v1/billing/verify")
            .header("Authorization", "Bearer dev:$uid")
            .body(VerifyRequest(productId, token))
            .retrieve().body(VerifyResponse::class.java)!!

    private fun verifyRaw(productId: String, token: String, uid: String = "pemain-1"): Resp =
        client.post().uri("/v1/billing/verify")
            .header("Authorization", "Bearer dev:$uid")
            .body(VerifyRequest(productId, token))
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }

    private fun nyawa(status: String): Int = jdbc.sql("SELECT count(*) FROM life_ledger WHERE type = 'paid' AND status = ?")
        .param(status).query(Long::class.java).single().toInt()

    private fun userId(uid: String = "pemain-1"): Long =
        jdbc.sql("SELECT id FROM app_user WHERE firebase_uid = ?").param(uid).query(Long::class.java).single()

    // ── Verifikasi & grant ───────────────────────────────────────────────────────────────────────

    @Test
    fun `verifikasi pembelian menerbitkan nyawa sesuai isi paket SKU`() {
        val r = verify("life_m", "tok-1")
        assertEquals(PurchaseStatus.GRANTED, r.status)
        assertEquals(5, r.livesGranted, "life_m = 5 nyawa (ADR-0022)")
        assertEquals(5, r.paid)
        assertEquals(2, r.free, "jatah 2 FreeLife periode tak terganggu")
        assertEquals(5, nyawa("available"))
        assertEquals(1, play.consumeCount, "consumable di-consume di Play (ARCH §8)")

        val row = jdbc.sql("SELECT status, lives_granted, verified_at FROM purchase").query().singleRow()
        assertEquals("granted", row["status"])
        assertEquals(5, row["lives_granted"])
        assertTrue(row["verified_at"] != null)
    }

    // Isi paket dibaca dari SKU milik server, BUKAN dari body — klien tak bisa menaikkan sendiri.
    @Test
    fun `SKU menentukan jumlah nyawa, bukan klien`() {
        assertEquals(1, verify("life_s", "tok-s").livesGranted)
        assertEquals(10, verify("life_l", "tok-l").livesGranted)
        assertEquals(11, nyawa("available"))
    }

    @Test
    fun `klaim ulang token yang sama tak menerbitkan nyawa dua kali`() {
        assertEquals(5, verify("life_m", "tok-1").livesGranted)

        val ulang = verify("life_m", "tok-1")
        assertEquals(PurchaseStatus.GRANTED, ulang.status, "balasan idempoten, bukan error")
        assertEquals(5, ulang.paid, "tetap 5, bukan 10")
        assertEquals(1, jdbc.sql("SELECT count(*) FROM purchase").query(Long::class.java).single().toInt())
    }

    @Test
    fun `SKU tak dikenal ditolak 400`() {
        val r = verifyRaw("life_xl", "tok-x")
        assertEquals(400, r.status, "body: ${r.body}")
        assertEquals(0, jdbc.sql("SELECT count(*) FROM purchase").query(Long::class.java).single().toInt())
    }

    // Verifikasi gagal harus TAK meninggalkan jejak: baris `pending` ikut ter-rollback supaya token
    // yang sama masih bisa dicoba lagi (Play kadang telat konsisten).
    @Test
    fun `pembelian yang tak dikenal Google ditolak tanpa meninggalkan baris purchase`() {
        play.dikenal = false
        assertEquals(400, verifyRaw("life_m", "tok-palsu").status)
        assertEquals(0, jdbc.sql("SELECT count(*) FROM purchase").query(Long::class.java).single().toInt())
        assertEquals(0, nyawa("available"))

        play.dikenal = true
        assertEquals(5, verify("life_m", "tok-palsu").livesGranted, "token yang sama boleh dicoba lagi")
    }

    @Test
    fun `pembelian yang belum PURCHASED ditolak`() {
        play.sudahDibayar = false
        assertEquals(400, verifyRaw("life_m", "tok-pending").status)
        assertEquals(0, nyawa("available"))
    }

    // ── Void: refund / chargeback (ADR-0025) ─────────────────────────────────────────────────────

    @Test
    fun `void mencabut nyawa sisa, menolkan skor periode, dan menerbitkan ban`() {
        verify("life_m", "tok-1")
        val uid = userId()
        // 2 dari 5 nyawa sudah terpakai + run berjalan dengan skor.
        jdbc.sql("UPDATE life_ledger SET status = 'used' WHERE id IN (SELECT id FROM life_ledger WHERE type = 'paid' LIMIT 2)").update()
        jdbc.sql("INSERT INTO run (user_id, period_id, total_score) VALUES (?, (SELECT id FROM period WHERE status = 'ACTIVE'), 9000)")
            .param(uid).update()

        assertTrue(billing.applyVoid("tok-1", VoidReason.REFUND))

        assertEquals(3, nyawa("clawed_back"), "hanya sisa yang dicabut")
        assertEquals(2, nyawa("used"), "yang sudah dipakai tetap terpakai — lantai 0, tanpa saldo minus")

        val run = jdbc.sql("SELECT total_score, score_locked FROM run").query().singleRow()
        assertEquals(0L, run["total_score"])
        assertEquals(true, run["score_locked"])

        val ban = jdbc.sql("SELECT reason, period_start_id, period_end_id FROM tournament_ban").query().singleRow()
        assertEquals("refund", ban["reason"])
        assertTrue(ban["period_start_id"] != null)

        assertEquals("voided", jdbc.sql("SELECT status FROM purchase").query(String::class.java).single())
        assertEquals(1, jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'purchase_voided'")
            .query(Long::class.java).single().toInt())
    }

    @Test
    fun `void kedua atas purchase yang sama tak menghukum dua kali`() {
        verify("life_m", "tok-1")
        assertTrue(billing.applyVoid("tok-1", VoidReason.REFUND))
        assertTrue(!billing.applyVoid("tok-1", VoidReason.CHARGEBACK), "sudah voided → tak diproses lagi")
        assertEquals(1, jdbc.sql("SELECT count(*) FROM tournament_ban").query(Long::class.java).single().toInt())
    }

    @Test
    fun `void token yang tak dikenal tak melakukan apa-apa`() {
        assertTrue(!billing.applyVoid("tok-entah", VoidReason.REFUND))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM tournament_ban").query(Long::class.java).single().toInt())
    }

    // Jendela ban = P, P+1, P+2. Saat void terjadi, P+2 lazimnya BELUM dibuat admin (ADR-0021 tanpa
    // cadence) → kolomnya NULL, bukan diisi nilai karangan (ADR-0038).
    @Test
    fun `period_end_id NULL saat P+2 belum dibuat`() {
        verify("life_m", "tok-1")
        billing.applyVoid("tok-1", VoidReason.REFUND)
        assertNull(jdbc.sql("SELECT period_end_id FROM tournament_ban").query(Long::class.java).optional().orElse(null))
    }

    @Test
    fun `period_end_id terisi P+2 saat periodenya sudah ada`() {
        periodeBaru("P+1", 30, "UPCOMING")
        val p2 = periodeBaru("P+2", 60, "UPCOMING")
        verify("life_m", "tok-1")
        billing.applyVoid("tok-1", VoidReason.CHARGEBACK)
        assertEquals(p2, jdbc.sql("SELECT period_end_id FROM tournament_ban").query(Long::class.java).single())
    }

    // Tanpa periode mana pun, `period_start_id NOT NULL` membuat ban tak bisa dicatat. Void &
    // clawback tetap wajib jalan, dan sanksinya tersurat di audit untuk diterbitkan manual.
    @Test
    fun `tanpa periode aktif, void tetap clawback dan menandai ban tertunda di audit`() {
        verify("life_m", "tok-1")
        jdbc.sql("UPDATE period SET status = 'ENDED'").update()

        assertTrue(billing.applyVoid("tok-1", VoidReason.REFUND))
        assertEquals(5, nyawa("clawed_back"))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM tournament_ban").query(Long::class.java).single().toInt())
        assertEquals(1, jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'purchase_voided_ban_deferred'")
            .query(Long::class.java).single().toInt())
    }

    @Test
    fun `endpoint billing tanpa bearer ditolak 401`() {
        val r = client.post().uri("/v1/billing/verify")
            .header("Content-Type", "application/json").body("{}")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
        assertEquals(401, r.status, "body: ${r.body}")
    }
}
