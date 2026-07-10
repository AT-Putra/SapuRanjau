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

## Build & jalankan

```bash
# Build seluruh modul
./gradlew build

# Jalankan server (Spring Boot)
./gradlew :server:app:bootRun
# → http://localhost:8080/health  →  {"status":"UP"}
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
```

Modul lain (client Android, submodul server) ditambahkan bertahap seiring pengembangan. Peta lengkap ada di `settings.gradle.kts`.
