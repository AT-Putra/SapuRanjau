// server/game — orkestrasi run turnamen one-shot (T-022, ADR-0024): start level, aksi, skor level,
// reveal seed. Progres level berjalan persisten di `board` (ADR-0036); engine & scoring dari shared/.
plugins {
    id("sapuranjau.spring-library")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable,
// ADR-0034) supaya versi yang dites `FlywayMigrationTest` = versi yang benar-benar jalan di sini.
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation(project(":shared:engine-core"))
    implementation(project(":shared:scoring"))
    implementation(project(":server:api")) // VerifiedUser + ApiException/ErrorCode (ADR-0035)
    implementation(project(":server:audit")) // penulis audit_event bersama (T-027)
    implementation(project(":server:integrity")) // gerbang device Play Integrity (T-028, ADR-0041)
    implementation(project(":server:lives")) // konsumsi nyawa FIFO-expiry (T-023, ADR-0008/0037)
    implementation(project(":server:tournament")) // gerbang periode/ban/consent (T-026, ADR-0040)
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
