// Root build: SENGAJA kosong (tanpa build logic di sini).
// Tiap modul memakai convention plugin dari build-logic/, mis:
//   plugins { id("sapuranjau.kotlin-library") }

// Satu pengecualian (T-035): plugin yang dipakai LANGSUNG oleh build script modul harus dimuat sekali
// di root, kalau tidak KGP masuk lewat classloader kedua dan Gradle memperingatkan "Kotlin Gradle
// plugin was loaded multiple times ... may break the build". `apply false` = dimuat, tak diterapkan
// di sini; modulnya (`client/data`) yang memanggil alias-nya.
plugins {
    alias(libs.plugins.kotlin.serialization) apply false
}
