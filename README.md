# Sapu Ranjau

Game Minesweeper untuk Android dengan mode **Casual** (latihan) dan **kompetitif** (turnamen). Skor dihitung dari efisiensi bermain.

| | |
|---|---|
| **Platform** | Android |
| **Stack** | Kotlin · Jetpack Compose · Spring Boot · PostgreSQL |

> Dokumen desain & arsitektur rinci bersifat internal dan tidak disertakan di repo ini.

---

## Prasyarat

- JDK 21 (LTS)
- Gradle via wrapper (`./gradlew`) — versi terpasang otomatis
- Node (`^20.19 || >=22.12`) — panel admin (`admin-web`) di-build sebagai bagian dari `./gradlew build`; tanpa Node build **gagal**, bukan diam-diam menghasilkan server tanpa panel

## Build & jalankan

```bash
# Build seluruh modul (termasuk SPA panel admin → static resource /admin)
./gradlew build

# Jalankan server (Spring Boot)
./gradlew :server:app:bootRun
# → http://localhost:8080/health  →  {"status":"UP"}
# → http://localhost:8080/admin   →  panel admin

# Panel admin dengan hot-reload (memakai server di atas lewat proxy)
cd admin-web && npm ci && npm run dev
```

## Struktur

Multi-modul Gradle; batas modul ditegakkan sebagai batas Gradle (modular monolith).

```
build-logic/         convention plugins (build config bersama)
gradle/              version catalog (satu sumber versi)
shared/
  engine-core        engine Minesweeper deterministik (dipakai client + server)
server/
  app                aplikasi Spring Boot (entrypoint)
admin-web/           panel admin (React + Vite) — bukan modul Gradle; hasil build-nya
                     ikut ke dalam jar `server/app` dan disajikan di `/admin`
```

Modul lain (client Android, submodul server) ditambahkan bertahap seiring pengembangan. Peta lengkap ada di `settings.gradle.kts`.
