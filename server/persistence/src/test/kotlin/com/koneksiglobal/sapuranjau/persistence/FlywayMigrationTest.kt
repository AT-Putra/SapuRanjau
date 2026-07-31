package com.koneksiglobal.sapuranjau.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.File
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

    // Jumlahnya DIHITUNG dari file, bukan ditulis tangan. Versi sebelumnya memakai konstanta (19) dan
    // konstanta itu tak ikut naik saat V20 lahir (T-036) — test ini merah tanpa ada yang salah dengan
    // migrasinya. Yang benar-benar diuji di sini adalah "semua migrasi yang ada jalan bersih di
    // Postgres sungguhan", dan itu tak butuh angka yang harus diingat manusia.
    @Test
    fun migratesAllVersionsCleanly() {
        val berkas = File(javaClass.classLoader.getResource("db/migration")!!.toURI())
            .listFiles { f -> f.name.endsWith(".sql") }!!.size
        val result = flyway().migrate()
        assertEquals(berkas, result.migrationsExecuted)
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
