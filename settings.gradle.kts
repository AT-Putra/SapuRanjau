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

// Repo ini hidup di dalam folder OneDrive: OneDrive menyinkronkan `build/` sambil Gradle menulis ke
// sana, dan hasilnya kegagalan acak "Unable to delete directory … a process has files open".
// Obatnya = keluarkan output build dari pohon yang disinkronkan, TANPA memindahkan repo.
//
// Opt-in per mesin (default tak berubah): set `sapuranjau.buildDir` di `~/.gradle/gradle.properties`,
// mis. `sapuranjau.buildDir=C:\\Users\\<user>\\AppData\\Local\\sapuranjau-build`.
providers.gradleProperty("sapuranjau.buildDir").orNull?.let { root ->
    gradle.beforeProject {
        val nama = path.trim(':').replace(':', '-').ifEmpty { "root" }
        layout.buildDirectory.set(File(root, nama))
    }
}

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
include(":server:billing")
include(":server:tournament")
include(":server:audit")
include(":server:integrity")
// client/ — Android + Compose (ADR-0001), fase client dibuka ADR-0042 (palet/token)
include(":client:app")
include(":client:ui-kit")
include(":client:data")
include(":client:feature-casual")
include(":client:feature-tournament")
//
// Ditambah per-task saat diklaim (docs/04_TASKS.md) — tiap penambahan = 1 baris + 1 build.gradle.kts 3-baris:
//   shared:  contracts
//   server:  api (T-021), game, lives, billing, tournament, audit, admin
//   client:  app, ui-kit, data, feature-casual / -tournament / -wallet / -leaderboard  (fase client)
//   (data ditambah T-035)
