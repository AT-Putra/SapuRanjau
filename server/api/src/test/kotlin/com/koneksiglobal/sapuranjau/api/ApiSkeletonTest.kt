package com.koneksiglobal.sapuranjau.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// App test-only utk mengangkat context modul library `api` (tak punya @SpringBootApplication sendiri).
// Scan `...api.*` → DevTokenVerifier aktif (firebase.enabled absen). RANDOM_PORT = server nyata →
// menguji stack penuh (filter auth + prefix /v1 + ProblemDetail advice), bukan cuma unit.
@SpringBootApplication
class ApiTestApp

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSkeletonTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    private data class Resp(val status: Int, val body: String?)

    private fun getStatus(auth: String?): Resp {
        var spec = RestClient.create("http://localhost:$port").get().uri("/v1/tournament/status")
        if (auth != null) spec = spec.header("Authorization", auth)
        return spec.exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
    }

    @Test
    fun `tanpa bearer, endpoint v1 tolak 401 problem+json dg code`() {
        val r = getStatus(null)
        assertEquals(401, r.status)
        assertTrue(r.body!!.contains("\"code\":\"UNAUTHENTICATED\""), "body: ${r.body}")
    }

    @Test
    fun `bearer valid, dapat 200 status OK di bawah prefix v1`() {
        val r = getStatus("Bearer dev:user-123")
        assertEquals(200, r.status)
        assertTrue(r.body!!.contains("\"status\":\"OK\""), "body: ${r.body}")
    }

    @Test
    fun `bearer invalid ditolak 401`() {
        assertEquals(401, getStatus("Bearer token-ngawur").status)
    }

    // Advice catch-all TIDAK boleh menelan exception MVC standar jadi 500 (route salah, body rusak,
    // method salah → status aslinya). Gigit begitu ada endpoint POST ber-body (T-022).
    @Test
    fun `rute v1 tak dikenal balas 404, bukan 500`() {
        val r = RestClient.create("http://localhost:$port").get().uri("/v1/tak-ada")
            .header("Authorization", "Bearer dev:user-123")
            .exchange { _, res -> Resp(res.statusCode.value(), res.bodyTo(String::class.java)) }
        assertEquals(404, r.status, "body: ${r.body}")
    }
}
