// server/integrity — gerbang device Play Integrity (T-028, ADR-0041).
//
// Modul sendiri, bukan di dalam `server/api`: gerbang ini menyimpan verdict di DB, sedangkan `api`
// adalah edge HTTP murni yang test spine-nya sengaja tak butuh Docker. `api` tetap tak tahu apa-apa
// soal database.
plugins {
    id("sapuranjau.spring-library")
}

extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation(project(":server:api")) // VerifiedUser + ApiException/ErrorCode (ADR-0035)
    implementation(project(":server:audit")) // jejak integrity_failed / integrity_unavailable (T-027)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc") // cache verdict (V19)
    implementation(libs.google.auth) // service-account JSON → access token utk Play Integrity API

    // Kebijakan lulus/tolak = fungsi murni → test modul ini TANPA Docker. Perilaku gerbangnya
    // (cache, 403, audit) diuji end-to-end di `game`/`lives` yang sudah punya harness Postgres.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
