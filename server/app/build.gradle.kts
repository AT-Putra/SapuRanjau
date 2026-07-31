// server/app — entrypoint Spring Boot (bootRun). Wiring modul server lain masuk sini per-task.
plugins {
    id("sapuranjau.spring-boot-app")
}

// BOM Boot mem-pin Flyway lebih rendah dari catalog. Kembalikan ke versi catalog (latest-stable,
// ADR-0034) — migrasi produksi harus jalan di versi yang sama dengan yang dites.
extra["flyway.version"] = libs.versions.flyway.get()

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":server:api"))               // edge HTTP + auth (T-021) → di-scan app
    implementation(project(":server:game"))              // orkestrasi run turnamen (T-022)
    implementation(project(":server:lives"))             // dompet nyawa + GET /v1/wallet (T-023)
    implementation(project(":server:billing"))           // verifikasi pembelian + void/ban (T-025)
    implementation(project(":server:tournament"))        // periode, gerbang, leaderboard, pemenang (T-026)
    implementation(project(":server:audit"))             // audit_event + sinyal anomali (T-027)
    implementation(project(":server:integrity"))         // gerbang device Play Integrity (T-028)
    implementation(project(":server:admin"))             // API panel admin: sesi + 2FA + RBAC (T-040)
    runtimeOnly(project(":server:persistence"))          // migrasi Flyway V1..V21 jalan saat boot
    runtimeOnly("org.springframework.boot:spring-boot-flyway") // Boot 4: autoconfig Flyway = modul terpisah
}

// ── Panel admin: SPA `admin-web` ikut masuk artefak ini (T-041, ADR-0013) ──
//
// `admin-web` BUKAN modul Gradle (isinya npm/Vite, tak ada satu pun kelas JVM) — ia tak masuk peta
// include di settings.gradle.kts. Yang dibutuhkan cuma dua panggilan npm dan satu salin, dan itu
// tinggal di sini, di modul yang memang memuat hasilnya sebagai static resource `/admin`. ADR-0013
// menolak plugin `com.github.node-gradle.node`: rilis terakhirnya 7.1.0 (Sep-2024) tak menyatakan
// dukungan Gradle 9 sedangkan repo sudah di 9.6.1.
val adminWeb = rootProject.layout.projectDirectory.dir("admin-web")
val windows = System.getProperty("os.name").startsWith("Windows")
val npm = if (windows) "npm.cmd" else "npm"
val shell = if (windows) arrayOf("cmd", "/c") else arrayOf("sh", "-c")

val adminWebInstall = tasks.register<Exec>("adminWebInstall") {
    description = "Pasang dependency admin-web dari lockfile (npm ci — BUKAN npm install: ADR-0013)."
    workingDir = adminWeb.asFile
    // `npm ci` dijalankan lewat shell dengan `|| ulangi sekali`. `npm ci` MENGHAPUS node_modules
    // sebelum memasang ulang, dan di Windows penghapusan itu punya dua cara gagal yang berbeda —
    // keduanya sudah kejadian di sini:
    //   1. `ENOTEMPTY rmdir …` tepat setelah pemasangan baru = OneDrive masih memegang handle berkas
    //      yang sedang disinkronkan (penyakit yang sama dengan `sapuranjau.buildDir`). Transien —
    //      percobaan kedua lolos, dan itu yang dibeli baris ini.
    //   2. `EPERM unlink …rolldown-binding…node` = ada **`npm run dev` yang masih hidup** memegang
    //      binary native-nya. BUKAN transien: retry ikut gagal, dan memang seharusnya — obatnya
    //      matikan dev server, bukan mencoba lagi. Jangan build sambil `vite dev` jalan.
    commandLine(*shell, "npm ci || npm ci")
    inputs.files(adminWeb.file("package.json"), adminWeb.file("package-lock.json"))
    // Penanda murah yang ditulis npm sendiri. Mendaftarkan seluruh `node_modules` sebagai output
    // berarti Gradle mem-fingerprint puluhan ribu berkas tiap build — lebih mahal dari npm-nya.
    outputs.file(adminWeb.file("node_modules/.package-lock.json"))
    // ponytail: retry satu kali di atas = tambalan, bukan obat. Obatnya memindahkan `node_modules`
    // keluar dari pohon yang disinkronkan OneDrive (penyakit yang sama dengan `sapuranjau.buildDir`
    // di settings.gradle.kts), tapi npm tak punya cara portabel untuk itu — junction pun dihapus
    // `npm ci` sendiri. Bayar kalau retry mulai ikut gagal.
    doFirst {
        // ADR-0013: build tanpa Node harus GAGAL dengan pesan jelas, bukan diam-diam menerbitkan
        // server tanpa panel. Pesan bawaan Exec ("A problem occurred starting process") tak
        // memberi tahu siapa pun apa yang kurang.
        val ada = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, npm).canExecute() }
        check(ada) {
            "Node/npm tak ditemukan di PATH — panel admin (admin-web) tak bisa di-build. " +
                "Pasang Node LTS (engines: ^20.19 || >=22.12), lalu ulangi."
        }
    }
}

val adminWebBuild = tasks.register<Exec>("adminWebBuild") {
    description = "Build SPA admin-web (Vite) ke admin-web/dist."
    dependsOn(adminWebInstall)
    workingDir = adminWeb.asFile
    commandLine(npm, "run", "build")
    inputs.dir(adminWeb.dir("src"))
    inputs.files(
        adminWeb.file("index.html"),
        adminWeb.file("vite.config.ts"),
        adminWeb.file("tsconfig.json"),
        adminWeb.file("package.json"),
    )
    outputs.dir(adminWeb.dir("dist"))
}

tasks.processResources {
    dependsOn(adminWebBuild)
    // `/admin` = static resource biasa; tak ada rute Spring untuk panel. Deep-link aman karena SPA
    // memakai hash router (admin-web/src/main.tsx), jadi tiap URL panel tetap meminta index.html.
    from(adminWeb.dir("dist")) { into("static/admin") }
}
