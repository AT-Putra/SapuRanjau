// Convention: modul aplikasi Android (client/app — satu-satunya yang menghasilkan APK).
// Pakai:  plugins { id("sapuranjau.android-app") }
import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    // Kotlin built-in sejak AGP 9.0 — lihat catatan di sapuranjau.android-library.
    id("org.jetbrains.kotlin.plugin.compose")
    // Gate format/lint (T-002): ktlint lewat kotlinter. Task `check` otomatis memanggil lintKotlin,
    // jadi `./gradlew build` gagal kalau formatnya melenceng — bukan sekadar tersedia kalau diingat.
    id("org.jmailen.kotlinter")
}

extensions.configure<ApplicationExtension> {
    compileSdk = 37
    // AndroidX 2026.x (core 1.19, lifecycle 2.11) menuntut compile terhadap API 37+ — bukan pilihan
    // gaya: build ditolak `checkDebugAarMetadata` kalau lebih rendah. targetSdk sengaja dibiarkan
    // 36 (perilaku runtime) — menaikkannya urusan kebijakan Play, RELEASE §2.
    defaultConfig {
        minSdk = 26 // ADR-0014
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // Signing & minify rilis sengaja belum ada: kunci hidup di env-file 0600 (ADR-0015, RELEASE §2),
    // dan build rilis baru dibuat saat gate rilis dikerjakan. Fase ini debug saja.
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
