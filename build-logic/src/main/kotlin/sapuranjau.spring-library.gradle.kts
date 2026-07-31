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
