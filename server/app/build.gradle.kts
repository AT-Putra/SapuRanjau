// server/app — entrypoint Spring Boot (bootRun). Wiring modul server lain masuk sini per-task.
plugins {
    id("sapuranjau.spring-boot-app")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable,
// ADR-0034) — migrasi produksi harus jalan di versi yang sama dengan yang dites.
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":server:api"))               // edge HTTP + auth (T-021) → di-scan app
    implementation(project(":server:game"))              // orkestrasi run turnamen (T-022)
    implementation(project(":server:lives"))             // dompet nyawa + GET /v1/wallet (T-023)
    implementation(project(":server:billing"))           // verifikasi pembelian + void/ban (T-025)
    runtimeOnly(project(":server:persistence"))          // migrasi Flyway V1..V16 jalan saat boot
    runtimeOnly("org.springframework.boot:spring-boot-flyway") // Boot 4: autoconfig Flyway = modul terpisah
}
