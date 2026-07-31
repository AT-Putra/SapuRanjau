// Convention: modul Kotlin murni (shared/*, server/* non-Spring).
// Pakai:  plugins { id("sapuranjau.kotlin-library") }
plugins {
    id("org.jetbrains.kotlin.jvm")
    // Gate format/lint (T-002): ktlint lewat kotlinter. Task `check` otomatis memanggil lintKotlin,
    // jadi `./gradlew build` gagal kalau formatnya melenceng — bukan sekadar tersedia kalau diingat.
    id("org.jmailen.kotlinter")
}

kotlin {
    // Build reproducible: pakai JDK 21 apa pun JDK yang terpasang di mesin dev.
    jvmToolchain(21)
}

dependencies {
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
