package com.koneksiglobal.sapuranjau.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress

// Server HTTP asli dari JDK (`com.sun.net.httpserver`) — cukup untuk memeriksa tiga hal yang benar
// -benar bisa rusak: token terpasang, JSON terurai, dan error server jadi ApiException yang benar.
// Tanpa MockWebServer: satu dependency test lebih sedikit untuk ~15 baris harness.
class ApiClientTest {

    private lateinit var server: HttpServer
    private var authHeader: String? = null
    private var requestBody: String = ""
    private var status = 200
    private var contentType = "application/json"
    private var body = "{}"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            authHeader = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun api() = SapuRanjauApi(ApiClient("http://127.0.0.1:${server.address.port}", DevTokenProvider("u1")))

    @Test
    fun `token terpasang dan field tak dikenal tidak mematikan parsing`() = runBlocking {
        body = """{"free":2,"paid":1,"nextExpiry":null,"kolomBaruDariServerNanti":"x"}"""

        val wallet = api().wallet()

        assertEquals("Bearer dev:u1", authHeader) // pasangan DevTokenVerifier di server
        assertEquals(2, wallet.free)
        assertEquals(1, wallet.paid)
        assertNull(wallet.nextExpiry)
    }

    @Test
    fun `klaim casual mengirim langkah dan nilai enum asing jatuh ke UNKNOWN`() = runBlocking {
        body = """{"result":"HADIAH_MODEL_BARU","free":3,"paid":0}"""

        val hasil = api().claimCasual(
            CasualClaim(9, 9, 10, seed = 42L, moves = listOf(CasualMove(CasualAction.REVEAL, 1, 2)), elapsedMs = 30_000),
        )

        assertTrue(requestBody.contains(""""seed":42"""), requestBody)
        assertTrue(requestBody.contains(""""action":"REVEAL""""), requestBody)
        // Kode enum yang belum dikenal APK ini = pesan biasa, BUKAN crash (APK terpasang tak bisa dipaksa update).
        assertEquals(ClaimResult.UNKNOWN, hasil.result)
        assertEquals(3, hasil.free)
    }

    // Pasangan (SKU, token) itulah yang mengikat pembelian di sisi Google — token `life_s` yang
    // diklaim sebagai `life_l` dibalas 404. Jadi keduanya WAJIB ikut di body, dan jumlah nyawa
    // dibaca dari balasan server, tak pernah dihitung klien (ADR-0011/0022).
    @Test
    fun `verifikasi pembelian mengirim SKU dan token, jumlah nyawa datang dari server`() = runBlocking {
        body = """{"status":"GRANTED","livesGranted":5,"free":2,"paid":5}"""

        val hasil = api().verifikasiPembelian("life_m", "dev-life_m-123")

        assertTrue(requestBody.contains(""""productId":"life_m""""), requestBody)
        assertTrue(requestBody.contains(""""purchaseToken":"dev-life_m-123""""), requestBody)
        assertEquals(PurchaseStatus.GRANTED, hasil.status)
        assertEquals(5, hasil.livesGranted)
        assertEquals(5, hasil.paid)
    }

    @Test
    fun `problem+json jadi ApiException dengan code yang dipakai memilih layar`() {
        status = 403
        contentType = "application/problem+json"
        body = """{"status":403,"detail":"Setujui S&K periode ini dulu.","code":"CONSENT_REQUIRED"}"""

        val e = assertThrows<ApiException> { runBlocking { api().tournamentStatus() } }

        assertEquals(403, e.status)
        assertEquals(ApiErrorCode.CONSENT_REQUIRED, e.code)
        assertEquals("Setujui S&K periode ini dulu.", e.detail)
    }

    @Test
    fun `error tanpa problem+json tetap jadi ApiException yang bisa ditampilkan`() {
        status = 502
        contentType = "text/html"
        body = "<html>bad gateway</html>"

        val e = assertThrows<ApiException> { runBlocking { api().wallet() } }

        assertEquals(502, e.status)
        assertEquals(ApiErrorCode.UNKNOWN, e.code)
        assertTrue(e.detail.contains("502"), e.detail)
    }
}
