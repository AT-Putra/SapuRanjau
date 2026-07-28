// client/ui-kit — komponen bersama + design token (T-030, ADR-0042).
// Sengaja TIDAK bergantung `engine-core`: komponen di sini murni tampilan atas state UI-nya sendiri,
// supaya bisa dipakai casual maupun turnamen (yang state-nya datang dari server).
plugins {
    id("sapuranjau.android-library")
}

android {
    namespace = "com.koneksiglobal.sapuranjau.uikit"
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling) // @Preview hanya di build debug

    // Modul Android tak mendapat JUnit dari starter Spring seperti modul server → dideklarasikan.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
