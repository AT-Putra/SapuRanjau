// server/admin — API panel admin: sesi + 2FA + RBAC (T-040; ADR-0010/0013).
//
// Modul ini duduk di ATAS modul feature (ia mengorkestrasi periode/pemenang di T-042), jadi arah
// dependency-nya admin → tournament, tak pernah sebaliknya.
plugins {
    id("sapuranjau.spring-library")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable).
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation(project(":server:api")) // ApiException/ErrorCode + konvensi error (ADR-0035)
    implementation(project(":server:audit")) // tiap aksi admin wajib berjejak (ARCH §10, T-027)
    // Dibutuhkan T-040 untuk `PiiCipher` (enkripsi secret TOTP) dan T-042 untuk PeriodService/
    // WinnerService. Menyalin PiiCipher ke sini akan melanggar catatan di file itu sendiri; edge ini
    // memang akan ada begitu panel meng-CRUD periode, jadi ia bukan dependency yang dipaksakan.
    implementation(project(":server:tournament"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc") // ADR-0020

    // HANYA modul crypto-nya (BCrypt), BUKAN `spring-boot-starter-security`: starter itu memasang
    // auto-config filter chain untuk SELURUH aplikasi, termasuk `/v1` milik pemain yang sudah punya
    // gerbangnya sendiri (BearerAuthFilter, ADR-0035). Menukar 138 test yang hijau dengan satu
    // password encoder adalah harga yang salah.
    implementation("org.springframework.security:spring-security-crypto")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(project(":server:persistence")) // migrasi Flyway V1..V21 utk DB test
    // Boot 4 memecah autoconfiguration per-modul: flyway-core saja TAK cukup, integrasinya di sini.
    testRuntimeOnly("org.springframework.boot:spring-boot-flyway")
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
