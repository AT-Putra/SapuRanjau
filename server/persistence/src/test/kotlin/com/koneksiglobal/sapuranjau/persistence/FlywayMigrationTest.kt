package com.koneksiglobal.sapuranjau.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
    @Container
    val postgres = PostgreSQLContainer("postgres:16-alpine")

    private fun flyway() =
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()

    @Test
    fun migratesAllFifteenEntitiesCleanly() {
        val result = flyway().migrate()
        assertEquals(15, result.migrationsExecuted)
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
