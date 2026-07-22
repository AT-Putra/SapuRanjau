// persistence — migrasi Flyway 15 entitas (ADR-0020, docs/08_DATA_SCHEMA.md). T-020.
// Migrasi-only: SQL di src/main/resources/db/migration. Repository/entity Kotlin
// milik modul feature masing-masing (T-023/025/026/027, dst).
plugins {
    id("sapuranjau.kotlin-library")
}

dependencies {
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)

    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
