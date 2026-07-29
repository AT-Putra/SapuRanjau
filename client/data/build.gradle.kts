// client/data — satu-satunya modul klien yang menyentuh jaringan (T-035, ADR-0030/0035).
// Feature modul memanggil `SapuRanjauApi`; tak ada satupun dari mereka yang tahu OkHttp/JSON.
//
// KOTLIN MURNI, bukan modul Android: isinya HTTP + JSON yang tak menyentuh satu pun API Android, dan
// convention `android-library` memaksa compiler Compose masuk (gagal tanpa runtime Compose). Modul
// ini jadi Android nanti hanya kalau `FirebaseTokenProvider` benar-benar ditaruh di sini — dan itu
// justru tak perlu: `TokenProvider` sudah interface, implnya boleh hidup di `client/app`.
plugins {
    id("sapuranjau.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // `api`, bukan `implementation`: fungsi publik di sini suspend → coroutines bocor ke signature.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
