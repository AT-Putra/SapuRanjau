// client/feature-wallet — dompet nyawa + pembelian 3 paket (T-033, ADR-0022).
plugins {
    id("sapuranjau.android-library")
}

android {
    namespace = "com.koneksiglobal.sapuranjau.wallet"
}

dependencies {
    implementation(project(":client:ui-kit"))
    implementation(project(":client:data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
