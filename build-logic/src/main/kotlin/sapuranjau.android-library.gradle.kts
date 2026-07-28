// Convention: modul library Android + Compose (client/ui-kit, client/feature-*).
// Pakai:  plugins { id("sapuranjau.android-library") }
//
// Sengaja TIPIS — hanya plugin, SDK level, dan toolchain. Dependency (termasuk BOM Compose) tetap
// dideklarasikan tiap modul, sama seperti modul server: yang dipakai modul terlihat di modul itu.
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
    // TANPA `org.jetbrains.kotlin.android`: sejak AGP 9.0 dukungan Kotlin sudah built-in dan
    // menerapkannya justru gagal ("no longer required for Kotlin support since AGP 9.0").
    id("org.jetbrains.kotlin.plugin.compose") // Compose compiler = plugin Kotlin sejak 2.0
}

// DSL baru AGP 9 (`android.newDsl=true` default) → tipe dari `com.android.build.api.dsl`,
// bukan `com.android.build.gradle.*` yang lama dan sudah deprecated.
extensions.configure<LibraryExtension> {
    compileSdk = 37
    // AndroidX 2026.x (core 1.19, lifecycle 2.11) menuntut compile terhadap API 37+ — bukan pilihan
    // gaya: build ditolak `checkDebugAarMetadata` kalau lebih rendah. targetSdk sengaja dibiarkan
    // 36 (perilaku runtime) — menaikkannya urusan kebijakan Play, RELEASE §2.
    defaultConfig {
        minSdk = 26 // ADR-0014
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21) // satu toolchain utk seluruh repo (sama dgn shared/server)
}

dependencies {
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
