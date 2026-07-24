// server/app — entrypoint Spring Boot (bootRun). Wiring modul server lain masuk sini per-task.
plugins {
    id("sapuranjau.spring-boot-app")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":server:api"))               // edge HTTP + auth (T-021) → di-scan app
    // implementation(project(":shared:engine-core"))   // simulasi ulang skor (ADR-0003) — aktif di T-022
}
