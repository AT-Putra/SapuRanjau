// server/billing — verifikasi pembelian Play + grant PaidLife, deteksi void → clawback + skor-0 + ban
// (T-025, ADR-0011/0022/0025). Nyawa dicetak lewat `lives`, tak pernah langsung ke `life_ledger`.
plugins {
    id("sapuranjau.spring-library")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable,
// ADR-0034) supaya versi yang dites = versi yang benar-benar jalan di sini.
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation(project(":server:api"))   // VerifiedUser + ApiException/ErrorCode (ADR-0035)
    implementation(project(":server:lives")) // grant PaidLife + clawback (T-023)
    implementation(project(":server:audit")) // penulis audit_event bersama (T-027)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc") // ADR-0020
    // Play Developer API dipanggil sebagai REST biasa lewat RestClient yang sudah ada — yang tak
    // dimiliki cuma penukaran service-account JSON → access token. Itu isi library ini; klien
    // androidpublisher generated (+google-api-client, +http-client) tak perlu ditarik.
    implementation(libs.google.auth)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(project(":server:persistence")) // migrasi Flyway V1..V17 utk DB test
    // Boot 4 memecah autoconfiguration per-modul: flyway-core saja TAK cukup, integrasinya di sini.
    testRuntimeOnly("org.springframework.boot:spring-boot-flyway")
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
