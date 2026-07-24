// server/api — edge HTTP bersama (auth Firebase ID token, error handler RFC 7807, prefix /v1,
// resolver current-user) + controller contoh. Bentuk endpoint inti = RATIFIED (05 §3, ADR-0033);
// konvensi follow-up = ADR-0035. Controller bisnis menyusul di modul feature (T-022/026/…).
plugins {
    id("sapuranjau.spring-library")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") // bawa Jackson 3 (tools.jackson) di Boot 4
    implementation(libs.firebase.admin)                                 // verifikasi ID token (ADR-0030)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
