// server/app — entrypoint Spring Boot (bootRun). Wiring modul server lain masuk sini per-task.
plugins {
    id("sapuranjau.spring-boot-app")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // implementation(project(":shared:engine-core"))   // simulasi ulang skor (ADR-0003) — aktif di T-022
}
