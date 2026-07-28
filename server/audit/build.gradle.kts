// server/audit — penulis `audit_event` bersama + sinyal anomali bot (T-027, ARCH §9/§10).
// Modul DAUN: tak bergantung pada modul feature mana pun, supaya semuanya boleh memanggilnya.
// Tanpa endpoint HTTP — pembacaan audit milik panel admin (T-040/T-042).
plugins {
    id("sapuranjau.spring-library")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable,
// ADR-0034) supaya versi yang dites = versi yang benar-benar jalan di sini.
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-json") // ObjectMapper utk `detail` jsonb
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc") // ADR-0020

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(project(":server:persistence")) // migrasi Flyway V1..V18 utk DB test
    // Boot 4 memecah autoconfiguration per-modul: flyway-core saja TAK cukup, integrasinya di sini.
    testRuntimeOnly("org.springframework.boot:spring-boot-flyway")
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
