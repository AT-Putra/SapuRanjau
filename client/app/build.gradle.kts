// client/app — satu-satunya modul yang menghasilkan APK (T-031 fase pertama: casual saja).
// Turnamen/dompet/leaderboard menyusul sebagai modul feature sendiri (T-032/033/034).
plugins {
    id("sapuranjau.android-app")
}

android {
    namespace = "com.koneksiglobal.sapuranjau"
    defaultConfig {
        applicationId = "com.koneksiglobal.sapuranjau" // ADR-0034
    }
}

dependencies {
    implementation(project(":client:ui-kit"))
    implementation(project(":client:feature-casual"))
    implementation(project(":client:feature-tournament"))
    implementation(project(":client:feature-wallet"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}
