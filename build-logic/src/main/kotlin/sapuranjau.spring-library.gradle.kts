// Convention: modul library Spring (server/api, game, lives, dst) — menyumbang bean/controller,
// TAPI bukan app runnable (bootJar dimatikan). Pakai: plugins { id("sapuranjau.spring-library") }
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")   // buka kelas @Component/@Configuration utk proxy Spring
    id("org.springframework.boot")               // BOM + BTA stabil (tanpa ini: NPE getPluginClasspaths di Kotlin 2.4.10 BTA)
    id("io.spring.dependency-management")
    // Gate format/lint (T-002): ktlint lewat kotlinter. Task `check` otomatis memanggil lintKotlin,
    // jadi `./gradlew build` gagal kalau formatnya melenceng — bukan sekadar tersedia kalau diingat.
    id("org.jmailen.kotlinter")
}

kotlin {
    jvmToolchain(21)
}

// Library, bukan aplikasi: matikan bootJar, pakai jar biasa agar jadi project-dependency modul lain.
tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }

dependencies {
    // Spring Data & Jackson memakai refleksi Kotlin utk menemukan ctor data class (entity/DTO).
    // Tanpa ini: NoClassDefFoundError kotlin/reflect/full/KClasses saat context start.
    "implementation"(kotlin("reflect"))
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ── Gerbang paralelisme Testcontainers (ditambahkan setelah diukur) ─────────────────────────────
// Gradle menjalankan task `test` beberapa modul SEKALIGUS, dan tiap modul menyalakan container
// Postgres + ryuk sendiri. Di mesin Windows/Docker Desktop, ledakan container serentak itu membuat
// Testcontainers gagal `DockerClientProviderStrategy` — **6 kali dalam satu sesi**, di modul yang
// berbeda-beda, dan selalu hijau saat modulnya diulang sendirian. Gate yang merah acak bukan gate.
//
// Build service dengan `maxParallelUsages = 1` = cara Gradle sendiri untuk membatasi konkurensi
// SEKELOMPOK task tanpa menyeret seluruh build jadi serial: kompilasi tetap paralel, hanya task
// `test` yang antre. Nama service-nya sama di semua modul → antreannya global.
abstract class GerbangTestcontainers : BuildService<BuildServiceParameters.None>

val gerbangTestcontainers =
    gradle.sharedServices.registerIfAbsent("gerbangTestcontainers", GerbangTestcontainers::class) {
        maxParallelUsages.set(1)
    }

tasks.withType<Test>().configureEach {
    usesService(gerbangTestcontainers)
}
