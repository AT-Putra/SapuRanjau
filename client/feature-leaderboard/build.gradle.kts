// client/feature-leaderboard — peringkat, inbox, dan form klaim hadiah (T-034, ADR-0021/0027/0039).
plugins {
    id("sapuranjau.android-library")
}

android {
    namespace = "com.koneksiglobal.sapuranjau.leaderboard"
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
