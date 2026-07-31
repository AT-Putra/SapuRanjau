package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.tournament.PiiCipher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootApplication(scanBasePackages = ["com.koneksiglobal.sapuranjau"])
class AdminTestApp

// Bukti runtime T-040 di Postgres 18 asli: sesi + 2FA + RBAC + rem brute-force + kontrak transport
// `ra-data-simple-rest` (ADR-0013). Rem gagal-login dikecilkan jadi 3 supaya test tak perlu 10 ronde.
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "sapuranjau.tournament.tick.enabled=false", "sapuranjau.tournament.tnc-version=v1",
        "sapuranjau.pii.key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "sapuranjau.admin.max-failed-logins=3",
        // Akun bootstrap: dites eksplisit di bawah dengan memanggil runner-nya setelah tabel dikosongkan.
        "sapuranjau.admin.bootstrap.username=bootstrap-admin",
        "sapuranjau.admin.bootstrap.password=kata-sandi-panjang-1",
    ],
)
class AdminAuthTest {

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

    @Autowired private lateinit var bootstrap: AdminBootstrap

    @BeforeEach
    fun bersihkan() {
        jdbc.sql("TRUNCATE admin_user, audit_event RESTART IDENTITY CASCADE").update()
    }

    // ── Helper ───────────────────────────────────────────────────────────────────────────────────

    private val client get() = RestClient.create("http://localhost:$port")

    private data class Resp(val status: Int, val body: String?, val setCookie: String?, val contentRange: String?) {
        val cookie: String? get() = setCookie?.substringBefore(';')
        fun code(mapper: ObjectMapper): String? = body?.let { mapper.readValue(it, Problem::class.java).code }
        fun login(mapper: ObjectMapper): LoginBody = mapper.readValue(body!!, LoginBody::class.java)
    }

    private data class Problem(val code: String? = null, val detail: String? = null)
    private data class LoginBody(val status: String = "", val secret: String? = null, val role: String? = null)

    private fun post(path: String, body: Any? = null, cookie: String? = null, xhr: Boolean = true): Resp {
        val spec = client.post().uri(path).headers { h ->
            if (xhr) h.set("X-Requested-With", "XMLHttpRequest")
            cookie?.let { h.set("Cookie", it) }
        }
        return (if (body != null) spec.body(body) else spec).exchange { _, res -> resp(res) }
    }

    private fun put(path: String, body: Any, cookie: String?): Resp =
        client.put().uri(path).headers { h ->
            h.set("X-Requested-With", "XMLHttpRequest")
            cookie?.let { h.set("Cookie", it) }
        }.body(body).exchange { _, res -> resp(res) }

    private fun get(path: String, cookie: String? = null, bearer: String? = null, params: Map<String, String> = emptyMap()): Resp =
        client.get().uri(uriOf(path, params)).headers { h ->
            cookie?.let { h.set("Cookie", it) }
            bearer?.let { h.set("Authorization", "Bearer $it") }
        }.exchange { _, res -> resp(res) }

    // Query di-encode sendiri lalu dikirim sebagai URI utuh: `uri(String)` diperlakukan RestClient
    // sebagai template, dan `[`/`"` milik kontrak ra-data-simple-rest jadi ambigu di situ — persis
    // ambiguitas yang sempat membuat test ini lulus-palsu (range diabaikan diam-diam).
    private fun uriOf(path: String, params: Map<String, String>): URI {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        return URI.create("http://localhost:$port$path" + if (query.isEmpty()) "" else "?$query")
    }

    private fun resp(res: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse) = Resp(
        res.statusCode.value(),
        res.bodyTo(String::class.java),
        res.headers.getFirst("Set-Cookie"),
        res.headers.getFirst("Content-Range"),
    )

    private fun buatAdmin(username: String, password: String = PASSWORD, role: AdminRole = AdminRole.ADMIN): Long =
        users.insert(username, encoder.encode(password)!!, role)

    // Login penuh sampai punya sesi: enrol TOTP dulu (akun baru selalu belum punya), lalu pakai kodenya.
    private fun sesi(username: String, role: AdminRole = AdminRole.ADMIN): String {
        buatAdmin(username, role = role)
        val awal = post("/admin/api/login", mapOf("username" to username, "password" to PASSWORD))
        val secret = awal.login(mapper).secret!!
        val enrol = post(
            "/admin/api/totp/enroll",
            mapOf("code" to Totp.code(secret, Instant.now().epochSecond)),
            cookie = awal.cookie,
        )
        assertEquals(200, enrol.status)
        return enrol.cookie ?: awal.cookie!!
    }

    // ── Test ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `username atau password salah selalu 401 BAD_CREDENTIALS`() {
        buatAdmin("operator")
        val tanpaAkun = post("/admin/api/login", mapOf("username" to "hantu", "password" to PASSWORD))
        val salahPassword = post("/admin/api/login", mapOf("username" to "operator", "password" to "salah-sekali-x"))

        assertEquals(401, tanpaAkun.status)
        assertEquals(401, salahPassword.status)
        // Pesan & kode identik: balasan tak boleh membocorkan username mana yang ada.
        assertEquals("BAD_CREDENTIALS", tanpaAkun.code(mapper))
        assertEquals(tanpaAkun.code(mapper), salahPassword.code(mapper))
    }

    @Test
    fun `akun baru dipaksa enrol TOTP dan secretnya tersimpan TERENKRIPSI`() {
        val id = buatAdmin("operator")
        val awal = post("/admin/api/login", mapOf("username" to "operator", "password" to PASSWORD))

        assertEquals(200, awal.status)
        assertEquals("TOTP_SETUP_REQUIRED", awal.login(mapper).status)
        // Password benar saja BELUM memberi sesi: /me masih ditolak.
        assertEquals(401, get("/admin/api/me", cookie = awal.cookie).status)

        val secret = awal.login(mapper).secret!!
        val enrol = post("/admin/api/totp/enroll", mapOf("code" to Totp.code(secret, Instant.now().epochSecond)), cookie = awal.cookie)
        assertEquals(200, enrol.status)
        assertEquals(200, get("/admin/api/me", cookie = enrol.cookie ?: awal.cookie).status)

        val tersimpan = users.byId(id)!!.totpSecretEnc
        assertNotNull(tersimpan)
        // Kolomnya bukan secret polos — ia baru jadi secret setelah didekripsi kunci PII (V21).
        assertTrue(!String(tersimpan).contains(secret), "secret TOTP tak boleh tersimpan polos")
        assertEquals(secret, pii.decrypt(tersimpan))
    }

    @Test
    fun `login berikutnya menuntut kode authenticator yang benar`() {
        val cookieLama = sesi("operator")
        val secret = pii.decrypt(users.byUsername("operator")!!.totpSecretEnc!!)
        post("/admin/api/logout", cookie = cookieLama)

        val tanpaKode = post("/admin/api/login", mapOf("username" to "operator", "password" to PASSWORD))
        assertEquals(401, tanpaKode.status)
        assertEquals("TOTP_REQUIRED", tanpaKode.code(mapper))

        val kodeSalah = post("/admin/api/login", mapOf("username" to "operator", "password" to PASSWORD, "code" to "000000"))
        assertEquals(401, kodeSalah.status)

        val benar = post(
            "/admin/api/login",
            mapOf("username" to "operator", "password" to PASSWORD, "code" to Totp.code(secret, Instant.now().epochSecond)),
        )
        assertEquals(200, benar.status)
        assertEquals("OK", benar.login(mapper).status)
    }

    @Test
    fun `logout mematikan sesi`() {
        val cookie = sesi("operator")
        assertEquals(200, get("/admin/api/me", cookie = cookie).status)
        assertEquals(204, post("/admin/api/logout", cookie = cookie).status)
        assertEquals(401, get("/admin/api/me", cookie = cookie).status)
    }

    @Test
    fun `tanpa sesi tak ada yang bisa dibaca`() {
        assertEquals(401, get("/admin/api/me").status)
        assertEquals(401, get("/admin/api/admin-users").status)
    }

    @Test
    fun `permintaan tanpa header XHR ditolak sebagai CSRF`() {
        val cookie = sesi("operator")
        val ditolak = post("/admin/api/logout", cookie = cookie, xhr = false)
        assertEquals(403, ditolak.status)
        assertEquals("FORBIDDEN", ditolak.code(mapper))
        // Sesinya harus masih hidup — permintaan itu memang tak pernah dieksekusi.
        assertEquals(200, get("/admin/api/me", cookie = cookie).status)
    }

    @Test
    fun `RBAC - moderator tak boleh menyentuh daftar akun admin`() {
        val cookie = sesi("moderator-1", role = AdminRole.MODERATOR)
        val ditolak = get("/admin/api/admin-users", cookie = cookie)
        assertEquals(403, ditolak.status)
        assertEquals("FORBIDDEN", ditolak.code(mapper))
    }

    @Test
    fun `daftar akun mengikuti kontrak ra-data-simple-rest`() {
        val cookie = sesi("operator")
        buatAdmin("finance-1", role = AdminRole.FINANCE)
        buatAdmin("moderator-1", role = AdminRole.MODERATOR)

        val semua = get("/admin/api/admin-users", cookie = cookie)
        assertEquals(200, semua.status)
        assertEquals("admin-users 0-2/3", semua.contentRange)

        val halaman = get(
            "/admin/api/admin-users",
            cookie = cookie,
            params = mapOf("range" to "[0,1]", "sort" to """["username","DESC"]"""),
        )
        assertEquals("admin-users 0-1/3", halaman.contentRange)
        // sort DESC by username → operator dulu, lalu moderator-1.
        assertTrue(halaman.body!!.indexOf("operator") < halaman.body.indexOf("moderator-1"))
        // Hash password & secret TOTP tak pernah ikut keluar.
        assertTrue(!halaman.body.contains("passwordHash") && !halaman.body.contains("totpSecret"))
    }

    @Test
    fun `kolom sort di luar daftar putih tak sampai ke SQL`() {
        val cookie = sesi("operator")
        val nakal = get(
            "/admin/api/admin-users",
            cookie = cookie,
            params = mapOf("sort" to """["id;DROP TABLE admin_user","ASC"]"""),
        )
        assertEquals(200, nakal.status) // jatuh ke kolom default, bukan 500 atau tabel hilang
        assertEquals(1L, users.count())
    }

    @Test
    fun `akun admin terakhir tak bisa mengunci dirinya sendiri`() {
        val cookie = sesi("operator")
        val id = users.byUsername("operator")!!.id

        val diriSendiri = put("/admin/api/admin-users/$id", mapOf("role" to "admin", "disabled" to true), cookie)
        assertEquals(409, diriSendiri.status)

        // Lewat akun admin kedua: pagar "admin aktif terakhir" tetap berdiri untuk target lain.
        val id2 = buatAdmin("operator-2")
        assertEquals(200, put("/admin/api/admin-users/$id2", mapOf("role" to "moderator", "disabled" to false), cookie).status)
        assertEquals(AdminRole.MODERATOR, users.byId(id2)!!.role)
    }

    @Test
    fun `akun dibuat lewat panel lahir tanpa TOTP dan password pendek ditolak`() {
        val cookie = sesi("operator")
        val pendek = post("/admin/api/admin-users", mapOf("username" to "baru", "password" to "pendek", "role" to "finance"), cookie)
        assertEquals(400, pendek.status)
        assertEquals("VALIDATION", pendek.code(mapper))

        val dibuat = post("/admin/api/admin-users", mapOf("username" to "baru", "password" to PASSWORD, "role" to "finance"), cookie)
        assertEquals(200, dibuat.status)
        val baru = users.byUsername("baru")!!
        assertEquals(AdminRole.FINANCE, baru.role)
        assertNull(baru.totpSecretEnc, "akun baru wajib enrol TOTP sendiri saat login pertama")
    }

    @Test
    fun `gagal berulang direm dan jejaknya ada di audit`() {
        buatAdmin("operator")
        repeat(3) { post("/admin/api/login", mapOf("username" to "operator", "password" to "salah-sekali-x")) }

        val direm = post("/admin/api/login", mapOf("username" to "operator", "password" to PASSWORD))
        assertEquals(429, direm.status)
        assertEquals("TOO_MANY_ATTEMPTS", direm.code(mapper))

        val jejak = jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'admin_login_failed'")
            .query(Long::class.java).single()
        assertEquals(3L, jejak)
    }

    @Test
    fun `bootstrap hanya jalan saat tabel kosong`() {
        bootstrap.jalankan()
        val pertama = users.byUsername("bootstrap-admin")
        assertNotNull(pertama)
        assertEquals(AdminRole.ADMIN, pertama.role)
        assertNull(pertama.totpSecretEnc)

        // Idempoten: dipanggil lagi (restart/deploy) tak menambah akun kedua.
        bootstrap.jalankan()
        assertEquals(1L, users.count())
    }

    @Test
    fun `alamat panel menyajikan SPA-nya`() {
        // Spring hanya menyajikan index.html otomatis untuk root, jadi `/admin` sempat 404 padahal
        // berkasnya ada di dalam jar — ketahuan waktu dibuka di browser, bukan dari test mana pun.
        // (index.html di test-resources = pengganti hasil build Vite yang tinggal di `server/app`.)
        listOf("/admin", "/admin/", "/admin/index.html").forEach { path ->
            val res = get(path)
            assertEquals(200, res.status, "GET $path")
            assertTrue(res.body!!.contains("panel-admin-placeholder"), "GET $path bukan index.html panel")
        }
    }

    @Test
    fun `endpoint admin tak pernah terbit di bawah v1`() {
        // Kalau prefix `/v1` ikut menyapu paket admin (ApiWebConfig), URL ini akan menjawab 200/403
        // alih-alih 404 — dan halaman login admin akan menuntut ID token Firebase milik pemain.
        assertEquals(404, get("/v1/admin-users", bearer = "dev:pemain-1").status)
        assertEquals(404, get("/v1/me", bearer = "dev:pemain-1").status)
    }
}
