package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.tournament.PiiCipher
import com.koneksiglobal.sapuranjau.tournament.TournamentGate
import com.koneksiglobal.sapuranjau.tournament.TournamentStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Bukti runtime layar domain panel (T-042) di Postgres 18 asli: periode/level/hadiah, aksi pemenang,
// PII klaim + RBAC, pengampunan ban, reset 2FA. Yang diuji di sini ATURANNYA — bentuk transport
// (`range`/`sort`/`Content-Range`) sudah dibuktikan `AdminAuthTest`.
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "sapuranjau.tournament.tick.enabled=false", "sapuranjau.tournament.tnc-version=v1",
        "sapuranjau.pii.key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    ],
)
class AdminDomainTest {

    companion object {
        const val PASSWORD = "kata-sandi-panjang-1"

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

    @Autowired private lateinit var users: AdminUsers

    @Autowired private lateinit var encoder: PasswordEncoder

    @Autowired private lateinit var pii: PiiCipher

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var gate: TournamentGate

    @BeforeEach
    fun bersihkan() {
        jdbc.sql(
            "TRUNCATE admin_user, audit_event, tournament_ban, tournament_consent, prize_claim, winner, " +
                "prize_config, message, board, level_config, run, life_ledger, purchase, period, app_user " +
                "RESTART IDENTITY CASCADE",
        ).update()
        // Dikembalikan, bukan di-truncate: barisnya milik migrasi V24 (`CHECK (id = 1)`), dan ada
        // test yang menyetelnya — urutan test tak dijamin.
        jdbc.sql(
            "UPDATE casual_earn_config SET reward_lives = 1, cap_daily = 1, cap_weekly = 5, " +
                "cap_monthly = 10, min_mines = 40, min_density = 0.150 WHERE id = 1",
        ).update()
    }

    // ── Helper HTTP (pola AdminAuthTest) ────────────────────────────────────────────────────────

    private val client get() = RestClient.create("http://localhost:$port")

    private data class Resp(val status: Int, val body: String?, val setCookie: String?) {
        val cookie: String? get() = setCookie?.substringBefore(';')
    }

    private fun resp(res: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse) =
        Resp(res.statusCode.value(), res.bodyTo(String::class.java), res.headers.getFirst("Set-Cookie"))

    private fun post(path: String, body: Any? = null, cookie: String? = null): Resp {
        val spec = client.post().uri(path).headers { h ->
            h.set("X-Requested-With", "XMLHttpRequest")
            cookie?.let { h.set("Cookie", it) }
        }
        return (if (body != null) spec.body(body) else spec).exchange { _, res -> resp(res) }
    }

    private fun put(path: String, body: Any, cookie: String?): Resp =
        client.put().uri(path).headers { h ->
            h.set("X-Requested-With", "XMLHttpRequest")
            cookie?.let { h.set("Cookie", it) }
        }.body(body).exchange { _, res -> resp(res) }

    private fun get(path: String, cookie: String? = null, params: Map<String, String> = emptyMap()): Resp {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val uri = URI.create("http://localhost:$port$path" + if (query.isEmpty()) "" else "?$query")
        return client.get().uri(uri).headers { h -> cookie?.let { h.set("Cookie", it) } }
            .exchange { _, res -> resp(res) }
    }

    private fun json(body: String?): Map<*, *> = mapper.readValue(body!!, Map::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun daftar(body: String?): List<Map<String, Any?>> = mapper.readValue(body!!, List::class.java) as List<Map<String, Any?>>

    // Sesi penuh untuk peran tertentu (akun baru selalu wajib enrol TOTP dulu — T-040).
    private fun sesi(username: String, role: AdminRole = AdminRole.ADMIN): String {
        users.insert(username, encoder.encode(PASSWORD)!!, role)
        val awal = post("/admin/api/login", mapOf("username" to username, "password" to PASSWORD))
        val secret = json(awal.body)["secret"] as String
        val enrol = post("/admin/api/totp/enroll", mapOf("code" to Totp.code(secret, Instant.now().epochSecond)), cookie = awal.cookie)
        return enrol.cookie ?: awal.cookie!!
    }

    // ── Helper data pemain ──────────────────────────────────────────────────────────────────────

    private fun pemain(uid: String, nama: String? = null): Long =
        jdbc.sql("INSERT INTO app_user (firebase_uid, display_name) VALUES (?, ?) RETURNING id")
            .params(listOf(uid, nama)).query(Long::class.java).single()

    private fun periode(nama: String, mulaiHariLalu: Long, selesaiHariDepan: Long, status: String): Long {
        val id = jdbc.sql(
            "INSERT INTO period (name, starts_at, ends_at, status) VALUES (?, now() - make_interval(days => ?), now() + make_interval(days => ?), ?) RETURNING id",
        ).params(nama, mulaiHariLalu.toInt(), selesaiHariDepan.toInt(), status).query(Long::class.java).single()
        return id
    }

    private fun run(userId: Long, periodId: Long, skor: Long) {
        jdbc.sql("INSERT INTO run (user_id, period_id, total_score) VALUES (?, ?, ?)")
            .params(userId, periodId, skor).update()
    }

    private fun iso(t: Instant) = t.toString()

    // ── Periode ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `periode dibuat lewat panel, jadwalnya bisa diubah, dan tumpang-tindih ditolak`() {
        val cookie = sesi("bos")
        val mulai = Instant.now().plus(1, ChronoUnit.DAYS)
        val selesai = mulai.plus(14, ChronoUnit.DAYS)

        val dibuat = post(
            "/admin/api/periods",
            mapOf("name" to "Agustus", "startsAt" to iso(mulai), "endsAt" to iso(selesai)),
            cookie,
        )
        assertEquals(200, dibuat.status)
        val id = json(dibuat.body)["id"] as String
        // Periode baru SELALU lahir UPCOMING — yang mengangkatnya cuma rollover (ADR-0040).
        assertEquals("UPCOMING", json(dibuat.body)["status"])
        assertEquals(0, (json(dibuat.body)["levelCount"] as Number).toInt())
        assertEquals(false, json(dibuat.body)["hasPrizeConfig"])

        val diubah = put(
            "/admin/api/periods/$id",
            mapOf("name" to "Agustus (revisi)", "startsAt" to iso(mulai), "endsAt" to iso(selesai.plus(7, ChronoUnit.DAYS))),
            cookie,
        )
        assertEquals(200, diubah.status)
        assertEquals("Agustus (revisi)", json(diubah.body)["name"])

        // Rentang yang menabrak periode di atas ditolak — aturannya milik PeriodService, bukan layar.
        val bentrok = post(
            "/admin/api/periods",
            mapOf("name" to "Tabrakan", "startsAt" to iso(mulai.plus(1, ChronoUnit.DAYS)), "endsAt" to iso(selesai)),
            cookie,
        )
        assertEquals(409, bentrok.status)

        // Waktu tanpa zona ditolak: "00:00" tanpa keterangan zona adalah dua jam berbeda.
        val tanpaZona = post(
            "/admin/api/periods",
            mapOf("name" to "Ambigu", "startsAt" to "2026-09-01T00:00", "endsAt" to "2026-09-10T00:00"),
            cookie,
        )
        assertEquals(400, tanpaZona.status)
    }

    // ── Level ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `level menolak papan di luar batas kelayakan no-guess dan bisa disaring per periode`() {
        val cookie = sesi("bos")
        val p1 = periode("P1", 0, 10, "UPCOMING")
        val p2 = periode("P2", 0, 10, "ENDED")

        val ok = post(
            "/admin/api/levels",
            mapOf("periodId" to p1.toString(), "levelIndex" to 0, "gridWidth" to 9, "gridHeight" to 9, "mineCount" to 10, "baseScore" to 1000, "lifeCap" to 2),
            cookie,
        )
        assertEquals(200, ok.status)

        // 40 bom di 81 sel = 49% → di atas ambang kelayakan no-guess (ADR-0031): generator bisa
        // kehabisan percobaan dan itu baru ketahuan saat pemain menekan "Main".
        val terlaluPadat = post(
            "/admin/api/levels",
            mapOf(
                "periodId" to p1.toString(),
                "levelIndex" to 1,
                "gridWidth" to 9,
                "gridHeight" to 9,
                "mineCount" to 40,
                "baseScore" to 1000,
                "lifeCap" to 2,
            ),
            cookie,
        )
        assertEquals(400, terlaluPadat.status)
        // Ditolak oleh ATURAN-nya, bukan karena body-nya tak terbaca — kalau tidak, test ini lulus
        // palsu untuk alasan yang tak ada hubungannya dengan kelayakan papan.
        assertTrue(terlaluPadat.body!!.contains("no-guess"), terlaluPadat.body)

        // Semua field dikirim: nilai default Kotlin TIDAK dipakai Jackson (tak ada
        // jackson-module-kotlin) — field non-null yang hilang dibalas 400. Panel memang selalu
        // mengirim seluruh form, jadi kontraknya begitu, bukan "opsional".
        val diP2 = post(
            "/admin/api/levels",
            mapOf(
                "periodId" to p2.toString(),
                "levelIndex" to 0,
                "gridWidth" to 9,
                "gridHeight" to 9,
                "mineCount" to 10,
                "baseScore" to 1000,
                "lifeCap" to 2,
            ),
            cookie,
        )
        assertEquals(200, diP2.status, diP2.body)
        val disaring = get("/admin/api/levels", cookie, mapOf("filter" to """{"periodId":"$p1"}"""))
        assertEquals(1, daftar(disaring.body).size)

        // Level hanya bisa dihapus selama periodenya belum berjalan.
        val levelP2 = jdbc.sql("SELECT id FROM level_config WHERE period_id = ?").param(p2).query(Long::class.java).single()
        val ditolak = client.delete().uri("/admin/api/levels/$levelP2")
            .headers { h ->
                h.set("X-Requested-With", "XMLHttpRequest")
                h.set("Cookie", cookie)
            }
            .exchange { _, res -> resp(res) }
        assertEquals(409, ditolak.status)
    }

    // ── Hadiah ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `konfigurasi hadiah menuntut 3 sampai 10 pemenang dan satu hadiah per peringkat`() {
        val cookie = sesi("bos")
        val p = periode("P", 0, 10, "UPCOMING")

        assertEquals(
            400,
            post("/admin/api/prizes", mapOf("periodId" to p.toString(), "winnersCount" to 2, "prizes" to listOf("a", "b")), cookie).status,
        )
        // Jumlah pemenang sah, tapi peringkat ke-3 tak punya hadiah → ditolak.
        assertEquals(
            400,
            post("/admin/api/prizes", mapOf("periodId" to p.toString(), "winnersCount" to 3, "prizes" to listOf("HP", "Voucher")), cookie).status,
        )

        val ok = post(
            "/admin/api/prizes",
            mapOf("periodId" to p.toString(), "winnersCount" to 3, "prizes" to listOf("HP", "Voucher 500rb", "Voucher 100rb")),
            cookie,
        )
        assertEquals(200, ok.status)
        assertEquals(listOf("HP", "Voucher 500rb", "Voucher 100rb"), json(ok.body)["prizes"])
    }

    // ── Pemenang ────────────────────────────────────────────────────────────────────────────────

    private fun pemenang(periodId: Long, userId: Long, rank: Int): Long =
        jdbc.sql("INSERT INTO winner (period_id, user_id, rank) VALUES (?, ?, ?) RETURNING id")
            .params(periodId, userId, rank).query(Long::class.java).single()

    @Test
    fun `pesan admin sampai ke inbox pemain dan gugur-pemenang menyebut admin di audit`() {
        val cookie = sesi("bos")
        val p = periode("P", 1, 5, "ACTIVE")
        val u1 = pemain("uid-1", "Adita")
        val u2 = pemain("uid-2")
        run(u1, p, 500)
        run(u2, p, 400)
        val w1 = pemenang(p, u1, 1)
        pemenang(p, u2, 2)

        val kirim = post("/admin/api/winners/$w1/message", mapOf("body" to "Selamat! Isi form klaim ya."), cookie)
        assertEquals(200, kirim.status)
        val pesan = jdbc.sql("SELECT user_id, admin_id, body FROM message").query { rs, _ ->
            Triple(rs.getLong("user_id"), rs.getLong("admin_id"), rs.getString("body"))
        }.single()
        assertEquals(u1, pesan.first)
        assertEquals(1L, pesan.second) // admin yang login, bukan 'system'
        // Isi pesan TIDAK disalin ke audit (append-only): yang dicatat cuma id-nya.
        val detailAudit = jdbc.sql("SELECT detail::text FROM audit_event WHERE event_type = 'admin_message_sent'")
            .query(String::class.java).single()
        assertFalse(detailAudit.contains("Selamat"))

        // Alasan wajib (ADR-0021).
        assertEquals(400, post("/admin/api/winners/$w1/disqualify", mapOf("reason" to "  "), cookie).status)

        val gugur = post("/admin/api/winners/$w1/disqualify", mapOf("reason" to "Terbukti pakai emulator"), cookie)
        assertEquals(200, gugur.status)
        assertEquals("disqualified", json(gugur.body)["status"])
        // Peringkat 2 naik jadi 1 (WinnerService), dan jejaknya menyebut ADMIN — bukan 'system'.
        assertEquals(1, jdbc.sql("SELECT rank FROM winner WHERE user_id = ?").param(u2).query(Int::class.java).single())
        val actor = jdbc.sql("SELECT actor_type FROM audit_event WHERE event_type = 'winner_disqualified'")
            .query(String::class.java).single()
        assertEquals("admin", actor)
    }

    @Test
    fun `PII klaim hanya untuk admin dan finance, dan tiap pembacaannya berjejak`() {
        val bos = sesi("bos", AdminRole.ADMIN)
        val kasir = sesi("kasir", AdminRole.FINANCE)
        val moderator = sesi("mod", AdminRole.MODERATOR)
        val p = periode("P", 1, 5, "ACTIVE")
        val u = pemain("uid-1", "Adita")
        run(u, p, 500)
        val w = pemenang(p, u, 1)
        jdbc.sql("INSERT INTO prize_claim (winner_id, phone_enc, ewallet_enc) VALUES (?, ?, ?)")
            .params(w, pii.encrypt("081234567890"), pii.encrypt("gopay 081234567890")).update()

        assertEquals(403, get("/admin/api/winners/$w/claim", moderator).status)
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'prize_claim_pii_read'").query(Long::class.java).single())

        val dibaca = get("/admin/api/winners/$w/claim", kasir)
        assertEquals(200, dibaca.status)
        assertEquals("081234567890", json(dibaca.body)["phone"])

        // Jejaknya menyebut FIELD, tak pernah isinya (audit_event tak bisa dihapus — T-027).
        val detail = jdbc.sql("SELECT detail::text FROM audit_event WHERE event_type = 'prize_claim_pii_read'")
            .query(String::class.java).single()
        assertTrue(detail.contains("phone"))
        assertFalse(detail.contains("081234567890"))

        // Daftar pemenang TIDAK memuat PII sama sekali, hanya status klaimnya.
        val listPemenang = get("/admin/api/winners", bos, mapOf("filter" to """{"periodId":"$p"}"""))
        assertFalse(listPemenang.body!!.contains("081234567890"))
        assertEquals("pending", daftar(listPemenang.body)[0]["claimStatus"])

        val lunas = post("/admin/api/winners/$w/claim/status", mapOf("status" to "paid", "prizeValue" to "1500000.00"), kasir)
        assertEquals(200, lunas.status)
        val klaim = jdbc.sql("SELECT status, paid_by, paid_at, prize_value FROM prize_claim WHERE winner_id = ?").param(w)
            .query { rs, _ -> listOf(rs.getString("status"), rs.getLong("paid_by"), rs.getTimestamp("paid_at"), rs.getBigDecimal("prize_value")) }
            .single()
        assertEquals("paid", klaim[0])
        assertNotNull(klaim[2])
        assertEquals(0, java.math.BigDecimal("1500000.00").compareTo(klaim[3] as java.math.BigDecimal))
    }

    // ── Ban ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ban yang diampuni DITANDAI bukan dihapus, dan gerbang langsung melepas pemainnya`() {
        val cookie = sesi("bos")
        val p = periode("P", 1, 5, "ACTIVE")
        val u = pemain("uid-1")
        jdbc.sql("INSERT INTO tournament_consent (user_id, period_id, tnc_version) VALUES (?, ?, 'v1')").params(u, p).update()
        val ban = jdbc.sql("INSERT INTO tournament_ban (user_id, reason, period_start_id) VALUES (?, 'refund', ?) RETURNING id")
            .params(u, p).query(Long::class.java).single()

        assertEquals(TournamentStatus.BANNED, gate.check(u).status)
        // Alasan wajib — sanksi uang yang dicabut manusia harus bisa dijelaskan.
        assertEquals(400, post("/admin/api/bans/$ban/forgive", mapOf("reason" to ""), cookie).status)

        val ampun = post("/admin/api/bans/$ban/forgive", mapOf("reason" to "Salah tagih, sudah diklarifikasi bank"), cookie)
        assertEquals(200, ampun.status)

        // BARISNYA TETAP ADA. Kalau dihapus, rollover membaca purchase 'voided' tanpa ban sebagai
        // "belum tertangani" dan menerbitkan ban baru (T-026) — pemain kena lagi tanpa sebab.
        val baris = jdbc.sql("SELECT forgiven_at, forgiven_by, forgive_reason FROM tournament_ban WHERE id = ?").param(ban)
            .query { rs, _ -> Triple(rs.getTimestamp("forgiven_at"), rs.getLong("forgiven_by"), rs.getString("forgive_reason")) }
            .single()
        assertNotNull(baris.first)
        assertEquals(1L, baris.second)
        assertEquals("Salah tagih, sudah diklarifikasi bank", baris.third)
        assertEquals(TournamentStatus.OK, gate.check(u).status)

        // Idempoten: mengampuni dua kali tak menimpa catatan pertama.
        assertEquals(200, post("/admin/api/bans/$ban/forgive", mapOf("reason" to "lagi"), cookie).status)
        assertEquals(
            "Salah tagih, sudah diklarifikasi bank",
            jdbc.sql("SELECT forgive_reason FROM tournament_ban WHERE id = ?").param(ban).query(String::class.java).single(),
        )
    }

    // ── Audit & S&K ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `jejak audit bisa disaring dan versi S&K dilaporkan apa adanya`() {
        val cookie = sesi("bos")
        val p = periode("P", 0, 5, "UPCOMING")
        post("/admin/api/periods/$p/close", cookie = cookie)

        val semua = get("/admin/api/audit-events", cookie, mapOf("filter" to """{"actorType":"admin"}"""))
        assertEquals(200, semua.status)
        assertTrue(daftar(semua.body).all { it["actorType"] == "admin" })

        val satu = get("/admin/api/audit-events", cookie, mapOf("filter" to """{"eventType":"period_closed_early"}"""))
        assertEquals(1, daftar(satu.body).size)
        assertEquals("period:$p", daftar(satu.body)[0]["target"])

        // Versi S&K = properti aplikasi (ADR-0026): panel melaporkannya, tak menyuntingnya.
        val tnc = get("/admin/api/tnc", cookie)
        assertEquals("v1", json(tnc.body)["version"])
        assertEquals(false, json(tnc.body)["editable"])
    }

    // ── Laporan ─────────────────────────────────────────────────────────────────────────────────

    private fun beli(userId: Long, produk: String, lives: Int, status: String, hariLalu: Int) {
        jdbc.sql(
            "INSERT INTO purchase (user_id, product_id, purchase_token, lives_granted, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, now() - make_interval(days => ?))",
        ).params(userId, produk, "tok-${System.nanoTime()}", lives, status, hariLalu).update()
    }

    @Test
    fun `laporan penjualan merekap per paket dan per hari, dan moderator tak boleh membukanya`() {
        val kasir = sesi("kasir", AdminRole.FINANCE)
        val moderator = sesi("mod", AdminRole.MODERATOR)
        val u = pemain("uid-1", "Adita")
        beli(u, "life_m", 5, "granted", 0)
        beli(u, "life_m", 5, "granted", 0)
        beli(u, "life_l", 10, "voided", 1)

        // Laporan uang = peran uang (ARCH §10).
        assertEquals(403, get("/admin/api/sales", moderator).status)

        val daftar = get("/admin/api/sales", kasir)
        assertEquals(200, daftar.status)
        assertEquals(3, daftar(daftar.body).size)

        val ringkas = get("/admin/api/sales/summary", kasir)
        assertEquals(3, (json(ringkas.body)["transaksi"] as Number).toInt())
        assertEquals(20, (json(ringkas.body)["livesGranted"] as Number).toInt())
        assertEquals(1, (json(ringkas.body)["voided"] as Number).toInt())
        // Rupiah TIDAK dilaporkan: `purchase.amount` tak pernah diisi (harga hidup di Play Console,
        // ADR-0022). Layar wajib mengatakannya, bukan menampilkan nol yang terbaca "tak ada penjualan".
        assertEquals(false, json(ringkas.body)["uangTersedia"])

        // Deret harian = 2 hari berbeda (hari ini & kemarin), dikelompokkan di zona WIB.
        @Suppress("UNCHECKED_CAST")
        val harian = json(ringkas.body)["harian"] as List<Map<String, Any?>>
        assertEquals(2, harian.size)

        // Filter yang sama dipakai daftar DAN ringkasan — angka besar di atas tak boleh menjelaskan
        // baris yang berbeda dari tabel di bawahnya.
        val disaring = get("/admin/api/sales", kasir, mapOf("filter" to """{"productId":"life_m"}"""))
        assertEquals(2, daftar(disaring.body).size)
        val ringkasFilter = get("/admin/api/sales/summary", kasir, mapOf("filter" to """{"productId":"life_m"}"""))
        assertEquals(2, (json(ringkasFilter.body)["transaksi"] as Number).toInt())
    }

    @Test
    fun `laporan pemain merekap aktivitas tanpa membocorkan PII akun`() {
        val cookie = sesi("bos")
        val p = periode("P", 1, 5, "ACTIVE")
        val u = jdbc.sql("INSERT INTO app_user (firebase_uid, display_name, email) VALUES (?, ?, ?) RETURNING id")
            .params("uid-1", "Adita", "adita@contoh.test").query(Long::class.java).single()
        val lain = pemain("uid-2", "Budi")
        run(u, p, 700)
        beli(u, "life_m", 5, "granted", 0)
        jdbc.sql("INSERT INTO life_ledger (user_id, type, source) VALUES (?, 'free', 'earn_casual')").param(u).update()
        jdbc.sql("INSERT INTO tournament_ban (user_id, reason, period_start_id) VALUES (?, 'refund', ?)").params(lain, p).update()

        val semua = get("/admin/api/players", cookie)
        assertEquals(200, semua.status)
        assertEquals(2, daftar(semua.body).size)
        // Email & no. HP TIDAK ikut: laporan ini dibuka semua peran, sedangkan PII punya pintunya
        // sendiri (layar pemenang, peran finance, tiap bacaan ber-audit — ADR-0020).
        assertFalse(semua.body!!.contains("adita@contoh.test"))

        val adita = daftar(semua.body).first { it["displayName"] == "Adita" }
        assertEquals(1, (adita["runs"] as Number).toInt())
        assertEquals(700, (adita["bestScore"] as Number).toInt())
        assertEquals(1, (adita["purchases"] as Number).toInt())
        assertEquals(1, (adita["casualClaims"] as Number).toInt())
        assertEquals(false, adita["activeBan"])
        assertNotNull(adita["lastActivityAt"])

        // Saring "sedang kena ban" — ban yang diampuni tak lagi terhitung (V22).
        val kenaBan = get("/admin/api/players", cookie, mapOf("filter" to """{"banned":"true"}"""))
        assertEquals(1, daftar(kenaBan.body).size)
        assertEquals("Budi", daftar(kenaBan.body)[0]["displayName"])

        val cari = get("/admin/api/players", cookie, mapOf("filter" to """{"q":"adit"}"""))
        assertEquals(1, daftar(cari.body).size)
    }

    // ── Penghapusan akun (ADR-0044) ─────────────────────────────────────────────────────────────

    @Test
    fun `hapus akun mengaburkan identitas tapi menyisakan yang menopang orang lain`() {
        val cookie = sesi("bos")
        val p = periode("P", 1, 5, "ACTIVE")
        val u = jdbc.sql("INSERT INTO app_user (firebase_uid, display_name, email) VALUES (?, ?, ?) RETURNING id")
            .params("uid-1", "Adita", "adita@contoh.test").query(Long::class.java).single()
        run(u, p, 500)
        beli(u, "life_m", 5, "granted", 0)
        val w = pemenang(p, u, 1)
        jdbc.sql("INSERT INTO prize_claim (winner_id, phone_enc, prize_value, status) VALUES (?, ?, 1500000.00, 'paid')")
            .params(w, pii.encrypt("081234567890")).update()
        jdbc.sql("INSERT INTO message (user_id, admin_id, body) VALUES (?, 1, 'Selamat')").param(u).update()

        assertEquals(403, post("/admin/api/players/$u/delete", mapOf("reason" to "x"), sesi("mod", AdminRole.MODERATOR)).status)
        // Alasan/rujukan permintaan wajib: itu yang membuktikan penghapusan memang diminta pemiliknya.
        assertEquals(400, post("/admin/api/players/$u/delete", mapOf("reason" to " "), cookie).status)

        val hapus = post("/admin/api/players/$u/delete", mapOf("reason" to "Permintaan pemain, tiket #12"), cookie)
        assertEquals(200, hapus.status, hapus.body)

        // Identitas hilang…
        val akun = jdbc.sql("SELECT firebase_uid, email, display_name, deleted_at FROM app_user WHERE id = ?").param(u)
            .query { rs, _ -> listOf(rs.getString("firebase_uid"), rs.getString("email"), rs.getString("display_name"), rs.getTimestamp("deleted_at")) }
            .single()
        assertTrue((akun[0] as String).startsWith("deleted:"))
        assertNull(akun[1])
        assertNull(akun[2])
        assertNotNull(akun[3])

        // …PII klaim hilang, jejak pembukuannya tinggal…
        val klaim = jdbc.sql("SELECT phone_enc, ewallet_enc, address_enc, prize_value, status FROM prize_claim WHERE winner_id = ?")
            .param(w).query { rs, _ -> listOf(rs.getBytes("phone_enc"), rs.getBytes("ewallet_enc"), rs.getBytes("address_enc"), rs.getBigDecimal("prize_value"), rs.getString("status")) }
            .single()
        assertNull(klaim[0])
        assertNull(klaim[1])
        assertNull(klaim[2])
        assertNotNull(klaim[3])
        assertEquals("paid", klaim[4])

        // …kotak masuk hilang…
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM message WHERE user_id = ?").param(u).query(Long::class.java).single())

        // …tetapi yang menopang orang lain TETAP: peringkat periode lampau, daftar pemenang
        // (peringkat & cooldown peserta lain bergantung padanya), dan pembukuan pembelian.
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM run WHERE user_id = ?").param(u).query(Long::class.java).single())
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM winner WHERE user_id = ?").param(u).query(Long::class.java).single())
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM purchase WHERE user_id = ?").param(u).query(Long::class.java).single())

        // Jejaknya menyebut rujukan permintaan, TAK PERNAH data yang barusan dihapus — audit_event
        // tak bisa dihapus siapa pun (T-027), jadi menyalin PII ke sana membatalkan penghapusannya.
        val detail = jdbc.sql("SELECT detail::text FROM audit_event WHERE event_type = 'account_deleted'")
            .query(String::class.java).single()
        assertTrue(detail.contains("tiket #12"))
        assertFalse(detail.contains("081234567890"))
        assertFalse(detail.contains("adita@contoh.test"))

        // Idempotensi: penghapusan kedua ditolak, bukan menimpa apa pun.
        assertEquals(409, post("/admin/api/players/$u/delete", mapOf("reason" to "lagi"), cookie).status)
    }

    @Test
    fun `hapus akun ditunda selama sanksi berjalan dan selama klaim hadiah belum lunas`() {
        val cookie = sesi("bos")
        val p = periode("P", 1, 5, "ACTIVE")
        val u = pemain("uid-1", "Adita")
        val ban = jdbc.sql("INSERT INTO tournament_ban (user_id, reason, period_start_id) VALUES (?, 'refund', ?) RETURNING id")
            .params(u, p).query(Long::class.java).single()

        // Tanpa pagar ini, hapus-akun = jalan keluar dari ban 3 periode (ADR-0025): masuk lagi
        // dengan akun Google yang sama menghasilkan firebase_uid baru dan catatan bersih.
        val ditolak = post("/admin/api/players/$u/delete", mapOf("reason" to "permintaan pemain"), cookie)
        assertEquals(409, ditolak.status)
        assertTrue(ditolak.body!!.contains("sanksi"))

        post("/admin/api/bans/$ban/forgive", mapOf("reason" to "salah tagih"), cookie)

        // Pagar kedua: hadiah tak bisa dikirim ke akun yang tak lagi menunjuk siapa pun.
        val w = pemenang(p, u, 1)
        jdbc.sql("INSERT INTO prize_claim (winner_id, phone_enc, status) VALUES (?, ?, 'pending')")
            .params(w, pii.encrypt("08123")).update()
        val ditolak2 = post("/admin/api/players/$u/delete", mapOf("reason" to "permintaan pemain"), cookie)
        assertEquals(409, ditolak2.status)
        assertTrue(ditolak2.body!!.contains("lunas"))

        jdbc.sql("UPDATE prize_claim SET status = 'paid' WHERE winner_id = ?").param(w).update()
        assertEquals(200, post("/admin/api/players/$u/delete", mapOf("reason" to "permintaan pemain"), cookie).status)
    }

    // ── Ekonomi nyawa casual (ADR-0045) ─────────────────────────────────────────────────────────

    @Test
    fun `parameter earn casual disetel dari panel, dengan pagar dan jejak dari-jadi`() {
        val bos = sesi("bos")
        // Menggeser ekonomi semua pemain sekaligus → peran admin saja, bukan operasi harian.
        assertEquals(403, put("/admin/api/casual-config/1", mapOf("rewardLives" to 1, "capDaily" to 1, "capWeekly" to 5, "capMonthly" to 10, "minMines" to 40, "minDensity" to 0.15), sesi("mod", AdminRole.MODERATOR)).status)

        // Cap menurun = salah ketik, bukan kebijakan: yang lebih kecil akan selalu menang.
        val menurun = put("/admin/api/casual-config/1", mapOf("rewardLives" to 1, "capDaily" to 5, "capWeekly" to 2, "capMonthly" to 10, "minMines" to 40, "minDensity" to 0.15), bos)
        assertEquals(400, menurun.status)
        assertTrue(menurun.body!!.contains("menaik"))

        // Di atas 0,30 generator no-guess tak dijamin bisa membuat papannya (ADR-0031).
        assertEquals(400, put("/admin/api/casual-config/1", mapOf("rewardLives" to 1, "capDaily" to 1, "capWeekly" to 5, "capMonthly" to 10, "minMines" to 40, "minDensity" to 0.5), bos).status)
        // Cap 0 mematikan jalur nyawa gratis — lantai legal GDD §9.5.
        assertEquals(400, put("/admin/api/casual-config/1", mapOf("rewardLives" to 1, "capDaily" to 0, "capWeekly" to 5, "capMonthly" to 10, "minMines" to 40, "minDensity" to 0.15), bos).status)

        val ok = put("/admin/api/casual-config/1", mapOf("rewardLives" to 2, "capDaily" to 2, "capWeekly" to 6, "capMonthly" to 12, "minMines" to 30, "minDensity" to 0.12), bos)
        assertEquals(200, ok.status, ok.body)
        assertEquals(2, (json(ok.body)["capDaily"] as Number).toInt())

        // Jejaknya memuat NILAI LAMA dan BARU: "kok jatah saya berkurang" adalah pertanyaan yang
        // hanya bisa dijawab kalau perubahannya bisa dilihat, bukan cuma keadaan akhirnya.
        // Diperiksa sebagai NILAI, bukan sebagai teks: `jsonb` mencetak dengan spasi setelah titik
        // dua dan urutan kunci sesuka Postgres — assertion atas string akan pecah tanpa ada yang
        // salah dengan datanya.
        val detail = jdbc.sql("SELECT detail::text FROM audit_event WHERE event_type = 'casual_config_updated'")
            .query(String::class.java).single()
        val jejak = mapper.readValue(detail, Map::class.java)
        assertEquals(1, ((jejak["dari"] as Map<*, *>)["capDaily"] as Number).toInt())
        assertEquals(2, ((jejak["jadi"] as Map<*, *>)["capDaily"] as Number).toInt())
        assertEquals(40, ((jejak["dari"] as Map<*, *>)["minMines"] as Number).toInt())
        assertEquals(30, ((jejak["jadi"] as Map<*, *>)["minMines"] as Number).toInt())

        // Hanya ada satu baris konfigurasi; id lain bukan "belum dibuat", melainkan tak ada.
        assertEquals(404, get("/admin/api/casual-config/2", bos).status)
    }

    // ── Reset 2FA ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reset 2FA memaksa enrol ulang dan akun sendiri ditolak`() {
        val cookie = sesi("bos")
        val korban = users.insert("kasir", encoder.encode(PASSWORD)!!, AdminRole.FINANCE)
        // Enrol dulu supaya ada yang bisa dihapus.
        val awal = post("/admin/api/login", mapOf("username" to "kasir", "password" to PASSWORD))
        val secret = json(awal.body)["secret"] as String
        post("/admin/api/totp/enroll", mapOf("code" to Totp.code(secret, Instant.now().epochSecond)), cookie = awal.cookie)
        assertNotNull(users.byId(korban)!!.totpSecretEnc)

        // Akun sendiri: satu-satunya yang terbantu adalah sesi curian.
        assertEquals(409, post("/admin/api/admin-users/1/reset-totp", cookie = cookie).status)

        val reset = post("/admin/api/admin-users/$korban/reset-totp", cookie = cookie)
        assertEquals(200, reset.status)
        assertNull(users.byId(korban)!!.totpSecretEnc)

        // Login berikutnya kembali ke jalur enrolment — dengan secret BARU, bukan yang lama.
        val lagi = post("/admin/api/login", mapOf("username" to "kasir", "password" to PASSWORD))
        assertEquals("TOTP_SETUP_REQUIRED", json(lagi.body)["status"])
        assertFalse(secret == json(lagi.body)["secret"])
    }
}
