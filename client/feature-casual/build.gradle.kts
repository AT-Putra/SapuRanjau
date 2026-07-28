// client/feature-casual — mode latihan OFFLINE (T-031, GDD §7.4).
// Tak menyentuh jaringan sama sekali: casual memang dirancang tanpa login & tanpa server (ADR-0030).
// Papan dihitung `engine-core` yang sama persis dengan yang dipakai server (ADR-0003).
plugins {
    id("sapuranjau.android-library")
}

android {
    namespace = "com.koneksiglobal.sapuranjau.casual"
}

dependencies {
    implementation(project(":shared:engine-core"))
    implementation(project(":client:ui-kit"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose) // collectAsStateWithLifecycle
    debugImplementation(libs.compose.ui.tooling)
}
