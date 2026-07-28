package com.koneksiglobal.sapuranjau.audit

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootApplication(scanBasePackages = ["com.koneksiglobal.sapuranjau"])
class AuditTestApp

// Bukti runtime T-027 di Postgres 18 asli: penulis audit bersama menghasilkan baris yang sah
// menurut CHECK `actor_type` dan `detail` yang benar-benar jsonb.
@Testcontainers
@SpringBootTest
class AuditServiceTest {

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

    @Autowired private lateinit var audit: AuditService
    @Autowired private lateinit var jdbc: JdbcClient

    @Test
    fun `record menulis baris lengkap dan detail terbaca sebagai jsonb`() {
        jdbc.sql("TRUNCATE audit_event RESTART IDENTITY").update()

        audit.record(Actor.SYSTEM, 42, "level_anomaly", "run:7", mapOf("signals" to listOf("too_fast"), "moves" to 12))

        val row = jdbc.sql(
            "SELECT actor_type, actor_id, event_type, target, detail->>'moves' AS moves, " +
                "detail->'signals'->>0 AS sinyal FROM audit_event",
        ).query().singleRow()
        assertEquals("system", row["actor_type"])
        assertEquals(42L, row["actor_id"])
        assertEquals("level_anomaly", row["event_type"])
        assertEquals("run:7", row["target"])
        assertEquals("12", row["moves"], "detail disimpan sebagai jsonb, bukan string")
        assertEquals("too_fast", row["sinyal"])
    }

    @Test
    fun `actor tanpa id dan tanpa target tetap sah`() {
        jdbc.sql("TRUNCATE audit_event RESTART IDENTITY").update()

        audit.record(Actor.SYSTEM, null, "winner_selected")

        val row = jdbc.sql("SELECT actor_id, target, detail::text AS d FROM audit_event").query().singleRow()
        assertEquals(null, row["actor_id"])
        assertEquals(null, row["target"])
        assertTrue((row["d"] as String).contains("{}"), "detail kosong = objek jsonb kosong: ${row["d"]}")
    }
}
