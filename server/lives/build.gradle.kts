// server/lives — dompet nyawa: grant, saldo, konsumsi FIFO-expiry (T-023, ADR-0008/0037).
// Tak bergantung pada `game`: `game` yang memanggil `lives` saat pemain memakai nyawa.
plugins {
    id("sapuranjau.spring-library")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable,
// ADR-0034) supaya versi yang dites = versi yang benar-benar jalan di sini.
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation(project(":shared:engine-core")) // re-simulasi (seed, moves) casual (T-024, ADR-0023)
    implementation(project(":server:api")) // VerifiedUser + ApiException/ErrorCode (ADR-0035)
    implementation(project(":server:audit")) // penulis audit_event bersama (T-027)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc") // ADR-0020

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(project(":server:persistence")) // migrasi Flyway V1..V16 utk DB test
    // Boot 4 memecah autoconfiguration per-modul: flyway-core saja TAK cukup, integrasinya di sini.
    testRuntimeOnly("org.springframework.boot:spring-boot-flyway")
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
