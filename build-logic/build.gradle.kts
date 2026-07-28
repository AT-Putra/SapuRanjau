plugins {
    `kotlin-dsl`
}

// Artefak plugin di classpath agar convention plugin bisa memanggil id(...) tanpa versi.
// Versi datang dari version catalog (gradle/libs.versions.toml).
dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.springBoot.gradlePlugin)
    implementation(libs.springDependencyManagement.gradlePlugin)
    // Fase client: convention android-* memanggil id("com.android.…") & plugin Compose tanpa versi.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.composePlugin)
}
