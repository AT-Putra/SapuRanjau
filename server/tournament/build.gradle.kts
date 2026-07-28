// server/tournament — siklus periode, gerbang turnamen, leaderboard, pemenang (T-026;
// ADR-0021/0025/0026/0027/0038/0039/0040). Dipanggil `game` sebelum pemain menyentuh papan.
plugins {
    id("sapuranjau.spring-library")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable,
// ADR-0034) supaya versi yang dites = versi yang benar-benar jalan di sini.
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation(project(":server:api")) // VerifiedUser + ApiException/ErrorCode (ADR-0035)
    implementation(project(":server:lives")) // sapuan nyawa lewat saat periode berganti (T-023)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc") // ADR-0020

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(project(":server:persistence")) // migrasi Flyway V1..V18 utk DB test
    // Boot 4 memecah autoconfiguration per-modul: flyway-core saja TAK cukup, integrasinya di sini.
    testRuntimeOnly("org.springframework.boot:spring-boot-flyway")
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
