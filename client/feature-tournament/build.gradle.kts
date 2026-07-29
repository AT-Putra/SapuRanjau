// client/feature-tournament — alur turnamen ONLINE (T-032). Kebalikan `feature-casual`: papan hidup
// di server (ADR-0002 server-authoritative), klien cuma mengirim aksi & merender jawabannya.
plugins {
    id("sapuranjau.android-library")
}

android {
    namespace = "com.koneksiglobal.sapuranjau.tournament"
}

dependencies {
    implementation(project(":client:ui-kit"))
    implementation(project(":client:data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose) // LocalActivity → window FLAG_SECURE (ADR-0028)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
