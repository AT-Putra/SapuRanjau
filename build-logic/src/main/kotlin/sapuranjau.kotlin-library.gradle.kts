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
