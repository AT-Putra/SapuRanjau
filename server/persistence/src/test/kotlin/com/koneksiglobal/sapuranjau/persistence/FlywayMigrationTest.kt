package com.koneksiglobal.sapuranjau.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Verifikasi runtime (bukan cuma review manual) T-020: 15 migrasi Flyway jalan bersih di
// Postgres betulan (Testcontainers) + audit_event benar-benar append-only (ADR-0020).
@Testcontainers
class FlywayMigrationTest {

    // Container per-test (bukan static/shared): tiap test mulai dari DB kosong, jadi
    // migrationsExecuted selalu deterministik terlepas urutan eksekusi JUnit5.
    // Import dari package `org.testcontainers.postgresql` = rumah resmi TC 2.0 (non-deprecated);
    // BUKAN `org.testcontainers.containers.PostgreSQLContainer` lama yang jadi shim @Deprecated.
    @Container
    val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

    private fun flyway() =
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()

    // 15 entitas (V1..V15) + V16 progres level berjalan di `board` (ADR-0036, T-022)
    // + V17 jendela ban terbuka (ADR-0038, T-025) + V18 nama tampilan (ADR-0039, T-026)
    // + V19 cache verdict Play Integrity (ADR-0041, T-028).
    @Test
    fun migratesAllVersionsCleanly() {
        val result = flyway().migrate()
        assertEquals(19, result.migrationsExecuted)
    }

    @Test
    fun auditEventRejectsUpdateAndDelete() {
        flyway().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val id = conn.createStatement()
                .executeQuery("INSERT INTO audit_event (actor_type, event_type) VALUES ('system', 'test') RETURNING id")
                .let { it.next(); it.getLong("id") }

            assertFailsWith<SQLException> {
                conn.createStatement().execute("UPDATE audit_event SET event_type = 'x' WHERE id = $id")
            }
            assertFailsWith<SQLException> {
                conn.createStatement().execute("DELETE FROM audit_event WHERE id = $id")
            }
        }
    }
}
