// Convention: aplikasi Spring Boot (server/app).
// Pakai:  plugins { id("sapuranjau.spring-boot-app") }
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")   // buka kelas @Component/@Configuration utk proxy Spring
    id("org.springframework.boot")
    id("io.spring.dependency-management")       // BOM Spring Boot → versi dependency terkelola
    // Gate format/lint (T-002): ktlint lewat kotlinter. Task `check` otomatis memanggil lintKotlin,
    // jadi `./gradlew build` gagal kalau formatnya melenceng — bukan sekadar tersedia kalau diingat.
    id("org.jmailen.kotlinter")
}

kotlin {
    jvmToolchain(21)
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
