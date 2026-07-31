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
