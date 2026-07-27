pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sapuranjau"

// ── Peta modul: daftar include INI = struktur repo (satu sumber kebenaran) ──
// shared/ — Kotlin murni, dipakai client + server (ADR-0003, ARCH §3)
include(":shared:engine-core")
include(":shared:scoring")
// server/ — Spring Boot, modular monolith (ADR-0005 / ADR-0012)
include(":server:app")
include(":server:api")
include(":server:persistence")
include(":server:game")
include(":server:lives")
//
// Ditambah per-task saat diklaim (docs/04_TASKS.md) — tiap penambahan = 1 baris + 1 build.gradle.kts 3-baris:
//   shared:  contracts
//   server:  api (T-021), game, lives, billing, tournament, audit, admin
//   client:  app, ui-kit, data, feature-casual / -tournament / -wallet / -leaderboard  (fase client)
